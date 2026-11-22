#!/usr/bin/env python3
"""
Bytecode indexer, obfuscation-resilient release differ, and symbol-mapping
propagator for UniFi Network Application (ace.jar) and unifi-core.

Zero third-party dependencies (Python 3 standard library only).
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import math
import os
import re
import struct
import sys
import zipfile
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

ROOT_DIR = Path(__file__).resolve().parent.parent
WORK_DIR = ROOT_DIR / "work"
RELEASES_META_DIR = ROOT_DIR / "releases"

# Obfuscated internal type prefix normalization so method signatures stay
# comparable across releases even when obfuscated class names shift.
OBF_TYPE_RE = re.compile(r"L(com/ubnt|com/unifi|com/ui|com/b)/[^;]+;")

# Heuristic for detecting obfuscated simple class names (e.g. WhKRqCtQbc, a, b, EHkKmC)
RANDOM_MIXED_CASE_RE = re.compile(r"^[A-Za-z]{4,24}$")


def resolve_release_id(ref: str) -> str:
    if ref == "current":
        link = WORK_DIR / "current"
        if not link.is_symlink():
            raise SystemExit("No work/current symlink found. Run scripts/extract.sh first.")
        return Path(os.readlink(link)).name
    return ref


def is_likely_obfuscated(class_name: str) -> bool:
    simple = class_name.rsplit("/", 1)[-1].split("$", 1)[0]
    parts = class_name.split("/")
    # Single-letter package segments like com/ubnt/k/... or com/b/d/...
    if any(len(p) == 1 for p in parts[:-1]):
        return True
    if len(simple) <= 2:
        return True
    # Unusual uppercase/lowercase transitions typical of ProGuard/Zelix/Allatori dictionaries
    if RANDOM_MIXED_CASE_RE.match(simple):
        transitions = sum(
            1 for a, b in zip(simple, simple[1:]) if a.islower() and b.isupper()
        )
        upper_runs = sum(1 for a, b in zip(simple, simple[1:]) if a.isupper() and b.isupper())
        if transitions >= 3 or (upper_runs >= 1 and any(c.islower() for c in simple) and len(simple) <= 12):
            return True
    return False


def normalize_descriptor(desc: str) -> str:
    return OBF_TYPE_RE.sub("L*;", desc)


def parse_class_bytes(data: bytes) -> dict[str, Any] | None:
    """Minimal JVM .class parser extracting structural + non-obfuscated anchors."""
    if len(data) < 24 or data[:4] != b"\xca\xfe\xba\xbe":
        return None

    off = 4
    minor, major, cp_count = struct.unpack_from(">HHH", data, off)
    off += 6

    cp: list[Any] = [None] * cp_count
    i = 1
    while i < cp_count:
        tag = data[off]
        off += 1
        if tag == 1:  # Utf8
            length = struct.unpack_from(">H", data, off)[0]
            off += 2
            raw = data[off : off + length]
            off += length
            cp[i] = ("utf8", raw.decode("utf-8", errors="replace"))
        elif tag in (3, 4):  # Integer, Float
            off += 4
        elif tag in (5, 6):  # Long, Double (occupies 2 constant pool slots)
            off += 8
            i += 1
        elif tag in (7, 8, 16, 19, 20):  # Class, String, MethodType, Module, Package
            idx = struct.unpack_from(">H", data, off)[0]
            off += 2
            cp[i] = (tag, idx)
        elif tag in (9, 10, 11, 12, 17, 18):  # Fieldref, Methodref, InterfaceMethodref, NameAndType, Dynamic, InvokeDynamic
            a, b = struct.unpack_from(">HH", data, off)
            off += 4
            cp[i] = (tag, a, b)
        elif tag == 15:  # MethodHandle
            off += 3
        else:
            return None
        i += 1

    def get_utf8(idx: int) -> str:
        if 0 < idx < cp_count and cp[idx] and cp[idx][0] == "utf8":
            return cp[idx][1]
        return ""

    def get_class(idx: int) -> str:
        if 0 < idx < cp_count and cp[idx] and cp[idx][0] == 7:
            return get_utf8(cp[idx][1])
        return ""

    access_flags, this_idx, super_idx, iface_count = struct.unpack_from(">HHHH", data, off)
    off += 8
    this_class = get_class(this_idx)
    super_class = get_class(super_idx)

    interfaces: list[str] = []
    for _ in range(iface_count):
        iface_idx = struct.unpack_from(">H", data, off)[0]
        off += 2
        interfaces.append(get_class(iface_idx))

    def parse_annotations(attr_bytes: bytes) -> tuple[list[str], list[str]]:
        anns: list[str] = []
        ann_strings: list[str] = []
        if len(attr_bytes) < 2:
            return anns, ann_strings
        num = struct.unpack_from(">H", attr_bytes, 0)[0]
        pos = 2

        def skip_elem_val(p: int) -> int:
            if p >= len(attr_bytes):
                return len(attr_bytes)
            t = chr(attr_bytes[p])
            p += 1
            if t == "s":
                if p + 2 <= len(attr_bytes):
                    s_idx = struct.unpack_from(">H", attr_bytes, p)[0]
                    s_val = get_utf8(s_idx)
                    if 2 <= len(s_val) <= 240 and "\x00" not in s_val:
                        ann_strings.append(s_val)
                return p + 2
            if t in "BCDFIJSZc":
                return p + 2
            if t == "e":
                return p + 4
            if t == "@":
                return read_ann(p)
            if t == "[":
                if p + 2 > len(attr_bytes):
                    return len(attr_bytes)
                cnt = struct.unpack_from(">H", attr_bytes, p)[0]
                p += 2
                for _ in range(cnt):
                    p = skip_elem_val(p)
                return p
            return len(attr_bytes)

        def read_ann(p: int) -> int:
            if p + 4 > len(attr_bytes):
                return len(attr_bytes)
            type_idx, num_pairs = struct.unpack_from(">HH", attr_bytes, p)
            p += 4
            ann_type = get_utf8(type_idx)
            if ann_type:
                anns.append(ann_type)
            for _ in range(num_pairs):
                p += 2  # element_name_index
                p = skip_elem_val(p)
            return p

        for _ in range(num):
            pos = read_ann(pos)
            if pos >= len(attr_bytes):
                break
        return anns, ann_strings

    def parse_members() -> tuple[list[str], list[str], list[str]]:
        nonlocal off
        count = struct.unpack_from(">H", data, off)[0]
        off += 2
        sigs: list[str] = []
        member_anns: list[str] = []
        member_ann_strs: list[str] = []
        for _ in range(count):
            _m_flags, name_idx, desc_idx, attr_count = struct.unpack_from(">HHHH", data, off)
            off += 8
            name = get_utf8(name_idx)
            desc = get_utf8(desc_idx)
            # Keep unobfuscated method names (constructors, Spring/Object overrides) and normalized desc
            norm_desc = normalize_descriptor(desc)
            if name in ("<init>", "<clinit>", "toString", "equals", "hashCode", "run", "call", "apply", "accept", "get"):
                sigs.append(f"{name}:{norm_desc}")
            else:
                sigs.append(norm_desc)
            for _ in range(attr_count):
                aname_idx, alen = struct.unpack_from(">HI", data, off)
                off += 6
                aname = get_utf8(aname_idx)
                abuf = data[off : off + alen]
                off += alen
                if aname in ("RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations"):
                    a_list, s_list = parse_annotations(abuf)
                    member_anns.extend(a_list)
                    member_ann_strs.extend(s_list)
        return sigs, member_anns, member_ann_strs

    field_sigs, field_anns, field_ann_strs = parse_members()
    method_sigs, method_anns, method_ann_strs = parse_members()

    class_anns: list[str] = []
    class_ann_strs: list[str] = []
    if off + 2 <= len(data):
        attr_count = struct.unpack_from(">H", data, off)[0]
        off += 2
        for _ in range(attr_count):
            if off + 6 > len(data):
                break
            aname_idx, alen = struct.unpack_from(">HI", data, off)
            off += 6
            aname = get_utf8(aname_idx)
            abuf = data[off : off + alen]
            off += alen
            if aname in ("RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations"):
                a_list, s_list = parse_annotations(abuf)
                class_anns.extend(a_list)
                class_ann_strs.extend(s_list)

    # Extract actual String literals (CONSTANT_String, tag 8) + annotation string values
    strings_set: set[str] = set()
    for entry in cp:
        if entry and entry[0] == 8:
            s = get_utf8(entry[1])
            if 3 <= len(s) <= 240 and "\x00" not in s:
                strings_set.add(s)
    for s in class_ann_strs + method_ann_strs + field_ann_strs:
        if 3 <= len(s) <= 240:
            strings_set.add(s)

    routes = sorted(
        s for s in strings_set
        if (
            s.startswith("/api/")
            or s.startswith("/v1/")
            or s.startswith("/v2/")
            or s.startswith("/proxy/")
            or s.startswith("/s/")
            or s.startswith("v2/api/")
        )
        and " " not in s
    )

    all_anns = sorted(set(class_anns + method_anns + field_anns))
    return {
        "name": this_class,
        "sha256": hashlib.sha256(data).hexdigest(),
        "java_major": major,
        "super": super_class,
        "interfaces": sorted(interfaces),
        "annotations": all_anns,
        "class_annotations": sorted(set(class_anns)),
        "strings": sorted(strings_set),
        "routes": routes,
        "field_sigs": sorted(field_sigs),
        "method_sigs": sorted(method_sigs),
        "obfuscated": is_likely_obfuscated(this_class),
    }


def structural_hash(cls: dict[str, Any]) -> str:
    """Hash of obfuscation-invariant features (ignores obfuscated class/method names)."""
    payload = json.dumps(
        {
            "super": normalize_descriptor(f"L{cls['super']};") if cls["super"] else "",
            "interfaces": sorted(normalize_descriptor(f"L{i};") for i in cls["interfaces"]),
            "annotations": cls["annotations"],
            "strings": cls["strings"],
            "field_sigs": cls["field_sigs"],
            "method_sigs": cls["method_sigs"],
        },
        sort_keys=True,
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def cmd_index(args: argparse.Namespace) -> None:
    release_id = resolve_release_id(args.release)
    rel_dir = WORK_DIR / "releases" / release_id
    ace_path = rel_dir / "raw" / "ace.jar"
    if not ace_path.exists():
        raise SystemExit(f"Missing {ace_path}")

    classes: dict[str, dict[str, Any]] = {}
    jar_inventory: dict[str, str] = {}

    with zipfile.ZipFile(ace_path) as ace:
        for info in ace.infolist():
            name = info.filename
            if name.startswith("BOOT-INF/classes/") and name.endswith(".class"):
                parsed = parse_class_bytes(ace.read(info))
                if parsed:
                    parsed["origin"] = "BOOT-INF/classes"
                    parsed["struct_hash"] = structural_hash(parsed)
                    classes[parsed["name"]] = parsed
            elif name.startswith("BOOT-INF/lib/") and name.endswith(".jar"):
                raw_jar = ace.read(info)
                jar_name = Path(name).name
                jar_inventory[jar_name] = hashlib.sha256(raw_jar).hexdigest()
                if jar_name == "internal-dependencies.jar":
                    with zipfile.ZipFile(io.BytesIO(raw_jar)) as inner:
                        for inner_info in inner.infolist():
                            if inner_info.filename.endswith(".class"):
                                parsed = parse_class_bytes(inner.read(inner_info))
                                if parsed:
                                    parsed["origin"] = "internal-dependencies.jar"
                                    parsed["struct_hash"] = structural_hash(parsed)
                                    classes[parsed["name"]] = parsed

    # Index unifi-core JS files if present
    core_files: dict[str, dict[str, Any]] = {}
    core_raw = rel_dir / "raw" / "unifi-core"
    core_dec = rel_dir / "decompiled" / "unifi-core"
    if core_raw.exists():
        for p in sorted(core_raw.rglob("*")):
            if p.is_file():
                rel = p.relative_to(core_raw).as_posix()
                b = p.read_bytes()
                dec_p = core_dec / rel
                dec_lines = len(dec_p.read_bytes().splitlines()) if dec_p.exists() else None
                core_files[rel] = {
                    "size_bytes": len(b),
                    "sha256": hashlib.sha256(b).hexdigest(),
                    "decompiled_lines": dec_lines,
                }

    index = {
        "schema_version": 1,
        "release_id": release_id,
        "class_count": len(classes),
        "jar_inventory": jar_inventory,
        "unifi_core": core_files,
        "classes": classes,
    }

    out_path = rel_dir / "index.json.gz"
    with gzip.open(out_path, "wt", encoding="utf-8") as f:
        json.dump(index, f)

    obf_count = sum(1 for c in classes.values() if c["obfuscated"])
    controllers = sum(
        1
        for c in classes.values()
        if any("Controller;" in a for a in c["class_annotations"])
    )
    route_count = sum(len(c["routes"]) for c in classes.values())
    print(
        f"[index] {release_id}: {len(classes)} classes ({obf_count} obfuscated), "
        f"{controllers} Spring controllers, {route_count} REST route strings, "
        f"{len(jar_inventory)} nested JARs, {len(core_files)} unifi-core files -> {out_path}"
    )


def load_index(release_ref: str) -> dict[str, Any]:
    release_id = resolve_release_id(release_ref)
    idx_path = WORK_DIR / "releases" / release_id / "index.json.gz"
    if not idx_path.exists():
        raise SystemExit(f"Missing index {idx_path}. Run: python3 scripts/release_diff.py index {release_id}")
    with gzip.open(idx_path, "rt", encoding="utf-8") as f:
        return json.load(f)


def cmd_search(args: argparse.Namespace) -> None:
    idx = load_index(args.release)
    q = args.query.lower()
    matches: list[tuple[str, str]] = []

    for cname, cls in idx["classes"].items():
        hits: list[str] = []
        if args.type in ("all", "class") and q in cname.lower():
            hits.append("class")
        if args.type in ("all", "annotation"):
            ann_hits = [a for a in cls["annotations"] if q in a.lower()]
            if ann_hits:
                hits.append(f"ann:{ann_hits[0]}")
        if args.type in ("all", "route"):
            route_hits = [r for r in cls["routes"] if q in r.lower()]
            if route_hits:
                hits.append(f"route:{route_hits[0]}")
        if args.type in ("all", "string"):
            str_hits = [s for s in cls["strings"] if q in s.lower()]
            if str_hits:
                hits.append(f"str:{str_hits[0]!r}")
        if hits:
            matches.append((cname, ", ".join(hits[:3])))

    matches.sort()
    print(f"Found {len(matches)} matching classes in {idx['release_id']} (showing up to {args.limit}):")
    for cname, reason in matches[: args.limit]:
        print(f"  {cname}  [{reason}]")


def feature_tokens(cls: dict[str, Any]) -> set[str]:
    toks: set[str] = set()
    for s in cls["strings"]:
        toks.add(f"S:{s}")
    for a in cls["annotations"]:
        toks.add(f"A:{a}")
    for r in cls["routes"]:
        toks.add(f"R:{r}")
    if cls["super"] and cls["super"] != "java/lang/Object":
        toks.add(f"P:{normalize_descriptor('L' + cls['super'] + ';')}")
    for i in cls["interfaces"]:
        toks.add(f"I:{normalize_descriptor('L' + i + ';')}")
    for m in cls["method_sigs"]:
        toks.add(f"M:{m}")
    return toks


def match_renamed_classes(
    removed: dict[str, dict[str, Any]],
    added: dict[str, dict[str, Any]],
    all_old: dict[str, dict[str, Any]],
    min_score: float = 0.65,
) -> tuple[list[dict[str, Any]], set[str], set[str]]:
    """
    Match removed obfuscated classes against added classes in two stages:
      1. Exact structural hash match (unchanged obfuscated class that was only renamed).
      2. IDF-weighted Jaccard over strings, annotations, routes, and normalized method signatures.
    """
    renames: list[dict[str, Any]] = []
    matched_old: set[str] = set()
    matched_new: set[str] = set()

    # Stage 1: unique exact structural hash matches
    added_by_shash: dict[str, list[str]] = defaultdict(list)
    for nname, ncls in added.items():
        if len(ncls["strings"]) + len(ncls["method_sigs"]) >= 2:
            added_by_shash[ncls["struct_hash"]].append(nname)

    removed_by_shash: dict[str, list[str]] = defaultdict(list)
    for oname, ocls in removed.items():
        if len(ocls["strings"]) + len(ocls["method_sigs"]) >= 2:
            removed_by_shash[ocls["struct_hash"]].append(oname)

    for sh, old_list in removed_by_shash.items():
        new_list = added_by_shash.get(sh, [])
        if len(old_list) == 1 and len(new_list) == 1:
            o, n = old_list[0], new_list[0]
            matched_old.add(o)
            matched_new.add(n)
            renames.append({"old": o, "new": n, "score": 1.0, "method": "exact-struct"})

    rem_old = {k: v for k, v in removed.items() if k not in matched_old}
    rem_new = {k: v for k, v in added.items() if k not in matched_new}
    if not rem_old or not rem_new:
        return renames, matched_old, matched_new

    # Stage 2: IDF-weighted Jaccard using inverted index over informative tokens
    df: Counter[str] = Counter()
    total_docs = max(len(all_old), 1)
    for cls in all_old.values():
        for tok in feature_tokens(cls):
            df[tok] += 1

    def idf(tok: str) -> float:
        # Weight string literals & REST routes higher than generic method signatures
        base = math.log((total_docs + 1) / (df.get(tok, 0) + 1)) + 1.0
        if tok.startswith("R:"):
            return base * 3.0
        if tok.startswith("S:"):
            return base * 2.0
        if tok.startswith("A:"):
            return base * 1.5
        return base * 0.5

    new_tokens = {n: feature_tokens(c) for n, c in rem_new.items()}
    new_weights = {n: sum(idf(t) for t in toks) for n, toks in new_tokens.items()}

    # Build inverted index on discriminative tokens (df <= 50)
    inv: dict[str, list[str]] = defaultdict(list)
    for n, toks in new_tokens.items():
        for t in toks:
            if (t.startswith("S:") or t.startswith("R:") or t.startswith("A:")) and df.get(t, 0) <= 50:
                inv[t].append(n)

    candidates: list[tuple[float, str, str]] = []
    for o, ocls in rem_old.items():
        otoks = feature_tokens(ocls)
        if not otoks:
            continue
        ow = sum(idf(t) for t in otoks)
        cand_counts: Counter[str] = Counter()
        for t in otoks:
            for n in inv.get(t, ()):
                cand_counts[n] += 1
        for n, _ in cand_counts.most_common(12):
            ntoks = new_tokens[n]
            inter = otoks & ntoks
            if not inter:
                continue
            iw = sum(idf(t) for t in inter)
            union = ow + new_weights[n] - iw
            score = iw / union if union > 0 else 0.0
            if score >= min_score:
                candidates.append((score, o, n))

    candidates.sort(reverse=True)
    for score, o, n in candidates:
        if o in matched_old or n in matched_new:
            continue
        matched_old.add(o)
        matched_new.add(n)
        renames.append({"old": o, "new": n, "score": round(score, 4), "method": "idf-jaccard"})

    # Stage 3: Infer global per-build obfuscator dictionary bijection from Stages 1-2 matches
    votes: dict[str, Counter[str]] = defaultdict(Counter)
    for r in renames:
        o_pkg, _, o_simple = r["old"].rpartition("/")
        n_pkg, _, n_simple = r["new"].rpartition("/")
        if o_pkg != n_pkg:
            continue
        o_parts = o_simple.split("$")
        n_parts = n_simple.split("$")
        if len(o_parts) != len(n_parts):
            continue
        for ot, nt in zip(o_parts, n_parts):
            if ot != nt and not ot.isdigit() and not nt.isdigit():
                votes[ot][nt] += 1

    # Greedy 1-to-1 consensus bijection (>= 3 votes and >= 70% unanimity)
    token_candidates: list[tuple[int, float, str, str]] = []
    for ot, cnts in votes.items():
        nt, top = cnts.most_common(1)[0]
        total = sum(cnts.values())
        ratio = top / total
        if top >= 3 and ratio >= 0.70:
            token_candidates.append((top, ratio, ot, nt))
    token_candidates.sort(reverse=True)

    dict_map: dict[str, str] = {}
    used_new_tokens: set[str] = set()
    for _top, _ratio, ot, nt in token_candidates:
        if ot in dict_map or nt in used_new_tokens:
            continue
        dict_map[ot] = nt
        used_new_tokens.add(nt)

    if dict_map:
        for o, ocls in sorted(removed.items()):
            if o in matched_old:
                continue
            o_pkg, sep, o_simple = o.rpartition("/")
            o_parts = o_simple.split("$")
            mapped_parts: list[str] = []
            changed = False
            for part in o_parts:
                if part in dict_map:
                    mapped_parts.append(dict_map[part])
                    changed = True
                else:
                    mapped_parts.append(part)
            if not changed:
                continue
            cand = f"{o_pkg}{sep}{'$'.join(mapped_parts)}"
            if cand not in added or cand in matched_new:
                continue
            ncls = added[cand]
            o_sup = normalize_descriptor(f"L{ocls['super']};") if ocls["super"] else ""
            n_sup = normalize_descriptor(f"L{ncls['super']};") if ncls["super"] else ""
            if o_sup != n_sup or len(ocls["interfaces"]) != len(ncls["interfaces"]):
                continue
            m_diff = abs(len(ocls["method_sigs"]) - len(ncls["method_sigs"]))
            f_diff = abs(len(ocls["field_sigs"]) - len(ncls["field_sigs"]))
            if m_diff == 0 and f_diff == 0:
                score = 0.95
            elif m_diff <= 1 and f_diff <= 1:
                score = 0.85
            else:
                continue
            matched_old.add(o)
            matched_new.add(cand)
            renames.append({"old": o, "new": cand, "score": score, "method": "dict-infer"})

    return renames, matched_old, matched_new


def load_manifest(release_id: str) -> dict[str, Any]:
    p = RELEASES_META_DIR / release_id / "manifest.json"
    if p.exists():
        return json.loads(p.read_text())
    return {}


def cmd_diff(args: argparse.Namespace) -> None:
    old_idx = load_index(args.old)
    new_idx = load_index(args.new)
    old_id, new_id = old_idx["release_id"], new_idx["release_id"]
    old_man, new_man = load_manifest(old_id), load_manifest(new_id)

    old_cls = old_idx["classes"]
    new_cls = new_idx["classes"]

    unchanged = []
    modified = []
    removed: dict[str, dict[str, Any]] = {}
    added: dict[str, dict[str, Any]] = {}

    for name, o in old_cls.items():
        if name in new_cls:
            n = new_cls[name]
            if o["sha256"] == n["sha256"]:
                unchanged.append(name)
            else:
                modified.append(name)
        else:
            removed[name] = o

    for name, n in new_cls.items():
        if name not in old_cls:
            added[name] = n

    renames, matched_old, matched_new = match_renamed_classes(
        removed, added, old_cls, min_score=args.min_score
    )
    truly_removed = sorted(k for k in removed if k not in matched_old)
    truly_added = sorted(k for k in added if k not in matched_new)

    # Dependency JAR diffs
    old_jars = old_idx.get("jar_inventory", {})
    new_jars = new_idx.get("jar_inventory", {})
    jars_added = sorted(set(new_jars) - set(old_jars))
    jars_removed = sorted(set(old_jars) - set(new_jars))
    jars_changed = sorted(k for k in set(old_jars) & set(new_jars) if old_jars[k] != new_jars[k])

    # REST routes diff
    old_routes = {r for c in old_cls.values() for r in c["routes"]}
    new_routes = {r for c in new_cls.values() for r in c["routes"]}
    routes_added = sorted(new_routes - old_routes)
    routes_removed = sorted(old_routes - new_routes)

    # unifi-core diff
    old_core = old_idx.get("unifi_core", {})
    new_core = new_idx.get("unifi_core", {})
    core_changed = sorted(
        k for k in set(old_core) & set(new_core) if old_core[k]["sha256"] != new_core[k]["sha256"]
    )
    core_added = sorted(set(new_core) - set(old_core))
    core_removed = sorted(set(old_core) - set(new_core))

    lines: list[str] = [
        f"# Release Diff: `{old_id}` -> `{new_id}`",
        "",
        "## Package Versions",
        "",
    ]
    old_pkgs = old_man.get("packages", {})
    new_pkgs = new_man.get("packages", {})
    all_pkgs = sorted(set(old_pkgs) | set(new_pkgs))
    if all_pkgs:
        lines.append("| Package | Old | New |")
        lines.append("| --- | --- | --- |")
        for pkg in all_pkgs:
            ov, nv = old_pkgs.get(pkg, "-"), new_pkgs.get(pkg, "-")
            if ov != nv:
                lines.append(f"| `{pkg}` | `{ov}` | `{nv}` |")
    else:
        lines.append("_No package metadata recorded._")

    lines.extend([
        "",
        "## UniFi Network (`ace.jar`) Summary",
        "",
        f"- Unchanged classes: **{len(unchanged)}**",
        f"- Modified classes (same name): **{len(modified)}**",
        f"- Matched obfuscated renames: **{len(renames)}**",
        f"- Truly added classes: **{len(truly_added)}**",
        f"- Truly removed classes: **{len(truly_removed)}**",
        f"- Nested JARs: +{len(jars_added)} / -{len(jars_removed)} / ~{len(jars_changed)}",
        f"- REST route strings: +{len(routes_added)} / -{len(routes_removed)}",
        "",
    ])

    if routes_added or routes_removed:
        lines.append("## REST Route Changes")
        lines.append("")
        for r in routes_added[:50]:
            lines.append(f"- `+ {r}`")
        for r in routes_removed[:50]:
            lines.append(f"- `- {r}`")
        lines.append("")

    if jars_added or jars_removed or jars_changed:
        lines.append("## Nested JAR Dependency Changes")
        lines.append("")
        for j in jars_added:
            lines.append(f"- `+ {j}`")
        for j in jars_removed:
            lines.append(f"- `- {j}`")
        for j in jars_changed:
            lines.append(f"- `~ {j}`")
        lines.append("")

    if modified:
        lines.append(f"## Modified Classes (showing up to 50 of {len(modified)})")
        lines.append("")
        for c in sorted(modified)[:50]:
            lines.append(f"- `{c}`")
        lines.append("")

    if renames:
        lines.append(f"## Matched Obfuscated Renames (showing up to 50 of {len(renames)})")
        lines.append("")
        for r in sorted(renames, key=lambda x: (-x["score"], x["old"]))[:50]:
            lines.append(f"- `{r['old']}` -> `{r['new']}` ({r['method']}, score={r['score']})")
        lines.append("")

    if truly_added:
        lines.append(f"## Added Classes (showing up to 50 of {len(truly_added)})")
        lines.append("")
        for c in truly_added[:50]:
            lines.append(f"- `+ {c}`")
        lines.append("")

    if core_changed or core_added or core_removed:
        lines.append("## `unifi-core` Changes")
        lines.append("")
        for f in core_added:
            lines.append(f"- `+ {f}`")
        for f in core_removed:
            lines.append(f"- `- {f}`")
        for f in core_changed:
            lines.append(
                f"- `~ {f}` ({old_core[f]['size_bytes']}B -> {new_core[f]['size_bytes']}B)"
            )
        lines.append("")

    report = "\n".join(lines)
    if args.out:
        out_path = Path(args.out)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(report)
        print(f"[diff] Wrote report: {out_path}")
    else:
        print(report)


def cmd_carry_mappings(args: argparse.Namespace) -> None:
    old_idx = load_index(args.old)
    new_idx = load_index(args.new)
    map_path = Path(args.mappings)
    data = json.loads(map_path.read_text())

    old_cls = old_idx["classes"]
    new_cls = new_idx["classes"]

    removed = {k: v for k, v in old_cls.items() if k not in new_cls}
    added = {k: v for k, v in new_cls.items() if k not in old_cls}
    renames, _, _ = match_renamed_classes(removed, added, old_cls, min_score=args.min_score)
    rename_map = {r["old"]: r for r in renames}

    updated = 0
    missing = 0
    for sym in data.get("symbols", []):
        cname = sym["class"]
        if cname in new_cls:
            status = "unchanged" if (cname in old_cls and old_cls[cname]["sha256"] == new_cls[cname]["sha256"]) else "present-modified"
            print(f"[ok]      {sym['id']}: {cname} ({status})")
            continue
        if cname in rename_map:
            r = rename_map[cname]
            print(f"[remapped] {sym['id']}: {cname} -> {r['new']} ({r['method']}, score={r['score']})")
            sym["class"] = r["new"]
            updated += 1
            continue
        # Fallback: match by explicit anchor strings/annotations in symbols.json
        anchors = sym.get("anchors", {})
        req_strings = set(anchors.get("strings", []))
        req_anns = set(anchors.get("annotations", []))
        if req_strings or req_anns:
            hits = [
                n for n, c in new_cls.items()
                if req_strings.issubset(set(c["strings"])) and req_anns.issubset(set(c["annotations"]))
            ]
            if len(hits) == 1:
                print(f"[anchor]  {sym['id']}: {cname} -> {hits[0]}")
                sym["class"] = hits[0]
                updated += 1
                continue
        print(f"[MISSING] {sym['id']}: {cname} (needs manual inspection)")
        missing += 1

    data["release_id"] = new_idx["release_id"]
    if args.write:
        map_path.write_text(json.dumps(data, indent=2) + "\n")
        print(f"[carry-mappings] Updated {map_path} ({updated} remapped, {missing} missing)")
    else:
        print(f"[carry-mappings] Dry run ({updated} remapped, {missing} missing). Pass --write to save.")


def main() -> None:
    p = argparse.ArgumentParser(description="UniFi release bytecode indexer and diff tool")
    sub = p.add_subparsers(dest="cmd", required=True)

    p_idx = sub.add_parser("index", help="Build bytecode fingerprint index for an extracted release")
    p_idx.add_argument("release", help="Release ID or 'current'")
    p_idx.set_defaults(func=cmd_index)

    p_search = sub.add_parser("search", help="Search indexed bytecode by string, route, annotation, or class")
    p_search.add_argument("release", help="Release ID or 'current'")
    p_search.add_argument("query", help="Case-insensitive substring query")
    p_search.add_argument("--type", choices=("all", "string", "class", "annotation", "route"), default="all")
    p_search.add_argument("--limit", type=int, default=40)
    p_search.set_defaults(func=cmd_search)

    p_diff = sub.add_parser("diff", help="Diff two releases with obfuscation-resilient rename matching")
    p_diff.add_argument("old", help="Old release ID")
    p_diff.add_argument("new", help="New release ID or 'current'")
    p_diff.add_argument("--out", help="Write Markdown report to file")
    p_diff.add_argument("--min-score", type=float, default=0.65)
    p_diff.set_defaults(func=cmd_diff)

    p_map = sub.add_parser("carry-mappings", help="Propagate mappings/symbols.json from old to new release")
    p_map.add_argument("old", help="Old release ID")
    p_map.add_argument("new", help="New release ID or 'current'")
    p_map.add_argument("--mappings", default=str(ROOT_DIR / "mappings" / "symbols.json"))
    p_map.add_argument("--min-score", type=float, default=0.65)
    p_map.add_argument("--write", action="store_true")
    p_map.set_defaults(func=cmd_carry_mappings)

    args = p.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
