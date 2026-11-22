#!/usr/bin/env python3
"""
Synthetic UniFi switch inform probe for testing custom models and verifying
provisioned system.cfg (copper EEE/Flow Control defaults and SFP guards).

Zero third-party dependencies (Python 3 stdlib + system openssl).
"""

from __future__ import annotations

import argparse
import ctypes
import ctypes.util
import json
import os
import re
import struct
import subprocess
import time
import urllib.error
import urllib.request
import zlib

DEFAULT_KEY_HEX = "ba86f2bbe107c7c57eb5f2690775c712"  # md5("ubnt")
EVP_CTRL_AEAD_SET_IVLEN = 0x9
EVP_CTRL_GCM_GET_TAG = 0x10
EVP_CTRL_GCM_SET_TAG = 0x11

_LIBCRYPTO = None


def _get_libcrypto():
    global _LIBCRYPTO
    if _LIBCRYPTO is not None:
        return _LIBCRYPTO
    candidates = [
        "/opt/homebrew/opt/openssl@3/lib/libcrypto.dylib",
        "/opt/homebrew/lib/libcrypto.dylib",
        "/usr/local/opt/openssl@3/lib/libcrypto.dylib",
        ctypes.util.find_library("crypto"),
    ]
    for path in candidates:
        if not path:
            continue
        try:
            lib = ctypes.CDLL(path)
            lib.EVP_CIPHER_CTX_new.restype = ctypes.c_void_p
            lib.EVP_CIPHER_CTX_free.argtypes = [ctypes.c_void_p]
            lib.EVP_aes_128_gcm.restype = ctypes.c_void_p
            lib.EVP_EncryptInit_ex.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_char_p, ctypes.c_char_p]
            lib.EVP_DecryptInit_ex.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_char_p, ctypes.c_char_p]
            lib.EVP_CIPHER_CTX_ctrl.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_void_p]
            lib.EVP_EncryptUpdate.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.POINTER(ctypes.c_int), ctypes.c_char_p, ctypes.c_int]
            lib.EVP_DecryptUpdate.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.POINTER(ctypes.c_int), ctypes.c_char_p, ctypes.c_int]
            lib.EVP_EncryptFinal_ex.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.POINTER(ctypes.c_int)]
            lib.EVP_DecryptFinal_ex.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.POINTER(ctypes.c_int)]
            _LIBCRYPTO = lib
            return lib
        except OSError:
            continue
    raise RuntimeError("Could not load OpenSSL libcrypto for AES-128-GCM")


def aes_gcm_encrypt(key: bytes, iv: bytes, aad: bytes, plaintext: bytes) -> bytes:
    lib = _get_libcrypto()
    ctx = lib.EVP_CIPHER_CTX_new()
    try:
        assert lib.EVP_EncryptInit_ex(ctx, lib.EVP_aes_128_gcm(), None, None, None) == 1
        assert lib.EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_AEAD_SET_IVLEN, len(iv), None) == 1
        assert lib.EVP_EncryptInit_ex(ctx, None, None, key, iv) == 1
        outlen = ctypes.c_int(0)
        assert lib.EVP_EncryptUpdate(ctx, None, ctypes.byref(outlen), aad, len(aad)) == 1
        out = ctypes.create_string_buffer(len(plaintext) + 16)
        assert lib.EVP_EncryptUpdate(ctx, out, ctypes.byref(outlen), plaintext, len(plaintext)) == 1
        ct_len = outlen.value
        fin_len = ctypes.c_int(0)
        assert lib.EVP_EncryptFinal_ex(ctx, ctypes.byref(out, ct_len), ctypes.byref(fin_len)) == 1
        tag = ctypes.create_string_buffer(16)
        assert lib.EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_GET_TAG, 16, tag) == 1
        return out.raw[: ct_len + fin_len.value] + tag.raw
    finally:
        lib.EVP_CIPHER_CTX_free(ctx)


def aes_gcm_decrypt(key: bytes, iv: bytes, aad: bytes, ct_and_tag: bytes) -> bytes:
    lib = _get_libcrypto()
    ct, tag = ct_and_tag[:-16], ct_and_tag[-16:]
    ctx = lib.EVP_CIPHER_CTX_new()
    try:
        assert lib.EVP_DecryptInit_ex(ctx, lib.EVP_aes_128_gcm(), None, None, None) == 1
        assert lib.EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_AEAD_SET_IVLEN, len(iv), None) == 1
        assert lib.EVP_DecryptInit_ex(ctx, None, None, key, iv) == 1
        outlen = ctypes.c_int(0)
        assert lib.EVP_DecryptUpdate(ctx, None, ctypes.byref(outlen), aad, len(aad)) == 1
        out = ctypes.create_string_buffer(len(ct) + 16)
        assert lib.EVP_DecryptUpdate(ctx, out, ctypes.byref(outlen), ct, len(ct)) == 1
        pt_len = outlen.value
        tag_buf = ctypes.create_string_buffer(tag, 16)
        assert lib.EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_TAG, 16, tag_buf) == 1
        fin_len = ctypes.c_int(0)
        if lib.EVP_DecryptFinal_ex(ctx, ctypes.byref(out, pt_len), ctypes.byref(fin_len)) != 1:
            raise ValueError("AES-GCM authentication failed")
        return out.raw[: pt_len + fin_len.value]
    finally:
        lib.EVP_CIPHER_CTX_free(ctx)


def aes_cbc(mode: str, key_hex: str, iv: bytes, data: bytes) -> bytes:
    flag = "-e" if mode == "enc" else "-d"
    return subprocess.check_output(
        ["openssl", "enc", "-aes-128-cbc", flag, "-K", key_hex, "-iv", iv.hex()],
        input=data,
    )


def build_port_table(copper_ports: int, sfp_ports: int) -> list[dict]:
    ports = []
    for i in range(1, copper_ports + 1):
        ports.append({
            "port_idx": i,
            "media": "2.5GE",
            "port_poe": False,
            "up": (i == 1),
            "enable": True,
            "speed": 2500 if i == 1 else 0,
            "full_duplex": True,
            "autoneg": True,
            "speed_caps": 0x1F,
            "port_caps": 1,  # EEE capable
            "is_uplink": (i == 1),
            "name": f"Port {i}",
        })
    for idx in range(copper_ports + 1, copper_ports + sfp_ports + 1):
        ports.append({
            "port_idx": idx,
            "media": "SFP+",
            "port_poe": False,
            "up": False,
            "enable": True,
            "speed": 0,
            "full_duplex": True,
            "autoneg": True,
            "speed_caps": 0x1010,
            "port_caps": 1,  # Deliberately advertise EEE cap to verify SFP guard blocks it
            "is_uplink": False,
            "name": f"SFP+ {idx}",
        })
    return ports


def send_inform(url: str, mac: str, key_hex: str, payload: dict, use_gcm: bool = False) -> tuple[int, dict | None]:
    mac_bytes = bytes.fromhex(mac.replace(":", ""))
    key_bytes = bytes.fromhex(key_hex)
    iv = os.urandom(16)
    raw = json.dumps(payload).encode("utf-8")
    if use_gcm:
        hdr = struct.pack(">4sI6sH16sII", b"TNBU", 0, mac_bytes, 0x0009, iv, 1, len(raw) + 16)
        ct = aes_gcm_encrypt(key_bytes, iv, hdr, raw)
    else:
        ct = aes_cbc("enc", key_hex, iv, raw)
        hdr = struct.pack(">4sI6sH16sII", b"TNBU", 0, mac_bytes, 0x0001, iv, 1, len(ct))

    req = urllib.request.Request(
        url,
        data=hdr + ct,
        headers={"Content-Type": "application/x-binary", "User-Agent": "AirControl Agent v1.0"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            body = resp.read()
            if len(body) >= 40 and body[:4] == b"TNBU":
                _, _, _, rflags, riv, _, rdlen = struct.unpack(">4sI6sH16sII", body[:40])
                enc = body[40 : 40 + rdlen]
                if rflags & 0x0008:
                    pt = aes_gcm_decrypt(key_bytes, riv, body[:40], enc)
                else:
                    pt = aes_cbc("dec", key_hex, riv, enc)
                if rflags & 0x0002:
                    pt = zlib.decompress(pt)
                return resp.status, json.loads(pt.decode("utf-8"))
            return resp.status, None
    except urllib.error.HTTPError as e:
        return e.code, None
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        print(f"  [warn] network error: {e}")
        return -1, None


def main() -> None:
    p = argparse.ArgumentParser(description="Synthetic UniFi switch inform probe")
    p.add_argument("--url", default="http://127.0.0.1:8080/inform")
    p.add_argument("--mac", default="02:51:0a:00:00:01")
    p.add_argument("--ip", default="192.0.2.10")
    p.add_argument("--model", default="RTL_LG510S")
    p.add_argument("--sysid", default="f901", help="Hex sysid (e.g. f901)")
    p.add_argument("--version", default="24.10.1-rtl")
    p.add_argument("--hostname", default="lg510s-probe")
    p.add_argument("--copper", type=int, default=8)
    p.add_argument("--sfp", type=int, default=2)
    p.add_argument("--interval", type=int, default=10)
    p.add_argument("--count", type=int, default=1, help="Number of informs (0 = run forever)")
    p.add_argument("--authkey", default=DEFAULT_KEY_HEX, help="32-char hex authkey (defaults to factory md5('ubnt'))")
    p.add_argument("--gcm", action="store_true", help="Start with AES-128-GCM inform encryption")
    args = p.parse_args()

    sysid_int = int(args.sysid.lower().removeprefix("0x"), 16)
    key_hex = args.authkey.lower()
    use_gcm = args.gcm
    cfgversion = "?"
    is_default = key_hex == DEFAULT_KEY_HEX
    state = 2
    uptime = 100
    port_table = build_port_table(args.copper, args.sfp)

    serial = args.mac.replace(":", "").upper()
    i = 0
    while args.count == 0 or i < args.count:
        i += 1
        payload = {
            "mac": args.mac,
            "serial": serial,
            "ip": args.ip,
            "model": args.model,
            "sysid": sysid_int,
            "version": args.version,
            "required_version": "1.0.0",
            "kernel_version": "6.6.73",
            "architecture": "mips",
            "board_rev": 1,
            "cfgversion": cfgversion,
            "default": is_default,
            "discovery_response": False,
            "hostname": args.hostname,
            "inform_url": args.url,
            "uptime": uptime,
            "has_eth1": False,
            "has_fan": False,
            "has_temperature": True,
            "general_temperature": 42,
            "state": state,
            "sys_stats": {
                "loadavg_1": 0.12,
                "loadavg_5": 0.10,
                "loadavg_15": 0.08,
                "mem_buffer": 4194304,
                "mem_total": 134217728,
                "mem_used": 33554432,
            },
            "system-stats": {
                "cpu": "4.2",
                "mem": "25.0",
                "uptime": str(uptime),
            },
            "switch_caps": {
                "feature_caps": 0xFFFF,
                "max_vlan_count": 256,
            },
            "port_table": port_table,
        }
        code, resp = send_inform(args.url, args.mac, key_hex, payload, use_gcm=use_gcm)
        enc_mode = "gcm" if use_gcm else "cbc"
        print(f"[probe #{i}] model={args.model} sysid=0x{sysid_int:x} default={is_default} enc={enc_mode} cfg={cfgversion} -> HTTP {code}")
        if resp:
            rtype = resp.get("_type")
            print(f"  response _type={rtype} keys={sorted(resp.keys())}")
            if rtype == "setparam":
                mgmt = resp.get("mgmt_cfg", "")
                m = re.search(r"authkey=([0-9a-fA-F]{32})", mgmt)
                if m:
                    key_hex = m.group(1)
                    is_default = False
                    print(f"  ADOPTED! Switched to controller authkey={key_hex[:8]}...")
                if "use_aes_gcm=true" in mgmt and not use_gcm:
                    use_gcm = True
                    print("  Switched to AES-128-GCM inform encryption")
                mc = re.search(r"cfgversion=([^\s]+)", mgmt)
                syscfg = resp.get("system_cfg", "")
                if syscfg:
                    if mc:
                        cfgversion = mc.group(1)
                    state = 1
                    print("  === Provisioned switch.port.*.(eee|flowctrl) ===")
                    for line in syscfg.splitlines():
                        if re.search(r"switch\.port\.\d+\.(eee|flowctrl)", line):
                            print("   ", line)
            elif rtype == "upgrade":
                print("  WARNING: Controller sent upgrade command:", resp)
        uptime += args.interval
        if args.count == 0 or i < args.count:
            time.sleep(args.interval)


if __name__ == "__main__":
    main()
