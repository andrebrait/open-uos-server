# Project Findings & Post-Mortem

## 1. Status & Why the Project Was Halted

**Status:** Halted / Archived (November 2026).

`open-uos-server` set out to answer two practical questions about Ubiquiti's UniFi OS Server (`uosserver`) and UniFi Network Application (`ace.jar`):

1. Can we non-invasively patch switch provisioning defaults (copper-port EEE and Flow Control enabled by default, with a strict guard preventing either from ever being emitted on SFP/fiber ports)?
2. Can we register first-class custom device models (such as OpenWrt-based switches or pfSense gateways) natively in UniFi—with their own model IDs, names, port layouts, icons, and suppressed Ubiquiti firmware checks—without spoofing official UniFi hardware?

Both goals were **implemented and verified end-to-end on a live test VM** running official `uosserver 6.0.21` (`unifi 11.1.56-38121-1`, `unifi-core 6.0.131`), working cleanly in both the **local Web UI** and the **`unifi.ui.com` remote portal**.

However, live testing with the **UniFi Mobile App (Android)** uncovered a hard architectural boundary in Ubiquiti's client ecosystem:

- The mobile app **never queries the controller's model catalog** (`/proxy/network/v2/api/site/<site>/models/*`).
- Instead, it validates every device's `model` field against Ubiquiti's own cloud/bundled UIDB catalog and **silently drops any device whose `model` is unknown**.
- Tapping an adoption push notification for a custom model fails ("device not found" / sheet immediately dismisses), and adopted custom devices disappear completely from the mobile device list—even though the controller returns them with `HTTP 200` on both `/v2/api/site/default/device` and `/api/s/default/stat/device`.

We evaluated the remaining options and concluded that continuing down this path is the wrong trade-off:

- **Patching the Android/iOS apps** is impractical (R8-obfuscated APK, re-signing breaks Play Store updates and FCM push delivery, iOS requires sideloading/re-signing on every update).
- **Mobile-only server-side `model` aliasing** (rewriting `model` to a donor UniFi model only when `x-client-platform` is `android` or `ios`) introduces a permanent desktop-versus-mobile model mismatch.
- **`sysid`-keyed overrides on a donor UniFi model** (letting non-UniFi hardware identify as an existing UniFi donor model on both desktop and mobile while keying firmware suppression and overrides off a private `sysid`) works cleanly across all clients, but sacrifices native custom model identity and still forces non-UniFi platforms through Ubiquiti's proprietary `TNBU` inform protocol and `system.cfg` schema.

Rather than maintaining ongoing bytecode patches against Ubiquiti's obfuscated builds and fighting closed mobile clients, the project was halted here in favor of preserving these findings and considering a cleaner, native, driver-based control/info/diagnostics plane.

---

## 2. UniFi OS Server (`uosserver`) Runtime Architecture

Inspection of official `uosserver` installations (`5.1.42` / `unifi 10.5.67` and `6.0.21` / `unifi 11.1.50` → `11.1.56`) established the following runtime layout:

- **Container model:** Runs rootless `podman` under a dedicated `uosserver` user (`XDG_RUNTIME_DIR=/run/user/1000`). The container root filesystem (`/`) is an ephemeral overlay replaced on upgrades, while `/data` (and `/var/lib/unifi` bind-mapped into persistent storage) survives container upgrades.
- **UniFi Network Application (`unifi.service`):** OpenJDK 25 Spring Boot fat JAR (`/usr/lib/unifi/lib/ace.jar`).
  - `BOOT-INF/classes/`: thin launcher (`com.ubnt.ace.*`).
  - `BOOT-INF/lib/internal-dependencies.jar`: ~25,000 heavily obfuscated Java/Kotlin `.class` files.
  - **Persistence hook:** `/var/lib/unifi/env-overrides` is sourced by `unifi.service` on startup, allowing `JVM_OPTS="$JVM_OPTS -javaagent:/var/lib/unifi/open-uos/open-uos-agent.jar"` to attach a Java agent cleanly without modifying `/usr/lib/unifi/lib/ace.jar` or systemd unit files.
  - **Database:** PostgreSQL listening on port `5434`, database `unifi-network` (notably `config.device`, which stores indexed columns such as `mac`, `model`, `type`, `serial`, `adopted` alongside a `dynamic_part` JSONB column).
- **`unifi-core` (`unifi-core.service`):** Node.js 24 ESM bundle (`/usr/share/unifi-core/app/service.js`) handling auth, nginx/WebRTC proxying, cloud sync (`unifi.ui.com`), push notifications, and console device lists.
  - Sets `NODE_CONFIG_DIR="/usr/share/unifi-core/app/config/:/data/unifi-core/config/overrides/"` (persistent across container upgrades).
  - Maintains its own `/data/unifi-core/uidb.json` independently from `unifi-network`'s `/data/unifi/data/uidb.json`.
- **Host/container daemons:** Stripped x86-64 ELF binaries (`uosserver`, `uosserver-service`, `uos-agent`, `uos`, etc.).

---

## 3. Release Tracking & Obfuscation Analysis

Ubiquiti's obfuscator rotates identifier names across builds using a small pool of pseudo-random tokens, which causes naive path/name diffs to break completely between releases.

We built a zero-dependency Python stdlib JVM `.class` parser and three-stage matcher in [`scripts/release_diff.py`](../scripts/release_diff.py):

1. **Stage 1 — Structural & anchor fingerprinting:** Indexes every class directly from `.class` constant pools and annotations (non-obfuscated string literals, Spring/REST route annotations, superclass/interface hierarchies, normalized method descriptors).
2. **Stage 2 — Package-local bipartite matching:** Matches renamed obfuscated classes within stable packages using weighted Jaccard similarity over anchors.
3. **Stage 3 — Dictionary bijection inference:** Infers the per-build obfuscator rename permutation (`oldToken → newToken`) from high-confidence Stage 1/2 matches and applies it to disambiguate remaining classes with sparse literals.

**Measured result (`unifi 11.1.50-38040-1 → 11.1.56-38121-1`):**
- **98.97%** class match rate across ~25,000 classes, enabling automated propagation of [`mappings/symbols.json`](../mappings/symbols.json) and quick pinpointing of behavioral changes.

For full local decompilation (`work/`, gitignored), **Vineflower** (with all `BOOT-INF/lib/*.jar` dependencies on the external classpath) handled OpenJDK 25 and Kotlin bytecode cleanly, and **Prettier** formatted `unifi-core`'s ESM bundles.

---

## 4. Runtime Bytecode Patching (`agent/`)

All instrumentation in [`agent/`](../agent/) uses ByteBuddy (`1.18.17`) and shaded ASM 9 targeting Java 25, resolving target classes and methods by **unobfuscated method names, parameter shapes, and literal string anchors** rather than hardcoded obfuscated names.

### 4.1 Copper EEE + Flow Control Defaults & SFP Guard

Implemented in `SwitchAndUidbTransformer.java` and `PortMediaHelper.java`:

- **Default settings:** Patched `GlobalSwitchSettingService` and `GlobalSwitchDefaultBuilder` so new sites default `flowctrl_enabled` to `true`, and copper ports (`FE`, `GE`, `2.5GE`, `5GE`, `10GE`) default both `eee_enabled` and `flowctrl_enabled` to `true` unless explicitly overridden to `false` in `port_overrides`.
- **Strict SFP/fiber guard:** Intercepted `Device.hasPortEeeCapability(int)`, the USW `system.cfg` generator (`rywXKdLGZvAktNSA` in 11.1.56), legacy switch emitters (`MJmYip`, `mvDN`), and the port diff detector (`bUvuLwmOeU`) so ports whose media is `SFP`, `SFP+`, `SFP28`, or `QSFP28` **never** emit `switch.port.<idx>.eee` or `switch.port.<idx>.flowctrl`, even if:
  - the device advertises `port_caps: 1` (EEE capable) on an SFP port, or
  - a user explicitly sets `eee_enabled: true` / `flow_control_enabled: true` in `port_overrides`.
- **Existing-site behavior:** Sites created before deploying the agent retain `global_switch.flowctrl_enabled = false` in PostgreSQL until toggled on via the UI or REST API (`PUT /proxy/network/api/s/default/rest/setting/global_switch/<id>`).
- **Live verification (`11.1.56`):**
  - With default `port_overrides: []`: copper ports `1..8` all emitted both `switch.port.<N>.flowctrl=enabled` and `switch.port.<N>.eee=enabled`; SFP+ ports `9..10` (deliberately advertising `port_caps=1`) emitted neither.
  - With port `2` overridden OFF and SFP+ port `9` forced ON: port `2` emitted `switch.port.2.eee=disabled` with no `flowctrl`; SFP+ port `9` still emitted zero `eee`/`flowctrl` lines.

### 4.2 Custom Device Models & Suppressed Firmware Updates

Implemented in `CustomModelEnumTransformer.java`, `CustomModelRuntime.java`, and `SwitchAndUidbTransformer.java`:

- **Enum constant injection:** Transformed UniFi's `DeviceModel` enum (`com.ubnt.k.g.a.JXjpchbRPAQlZmA` in 11.1.56) at class-load time before `<clinit>` runs, appending custom enum entries loaded from `/var/lib/unifi/open-uos/custom-models.json` into both the static fields and `$VALUES` array. All downstream enum consumers (`values()`, `valueOf()`, `EnumSet`, `EnumMap`, `sysid` reverse-lookup maps, and `productInfoTableGenerator`) recognized custom models natively.
- **Firmware update suppression:** Forced `upgradable = false`, `upgradableOnAdoption = false`, and `unsupported = false` for custom models so any reported firmware version (e.g., `24.10.1-rtl`) is treated as current with zero upgrade prompts.
- **UIDB injection & icon resolution:**
  - Inspected the React Web UI bundle (`61748.*.js`) and discovered that `getRemoteImagePath` and `getLocalImagePath` build image paths from **`record.id`** (`fingerprint/ui/images/<id>/<variant>/<imageHash>.png`), **not** `record.icon.id`.
  - Inheriting the donor model's UIDB `id` (e.g., `USL8LP`'s `b6a7f692-39e2-4942-989e-874e5166ef3b`) alongside `icon` and `images` caused icons to load cleanly on both local Web UI and `unifi.ui.com` without 404s. Because `UiDbUtils` indexes records by `sysids`, sharing a donor's `id` caused no collisions.
  - Stale cached custom entries in `/data/unifi/data/uidb.json` must be replaced on reload whenever `custom-models.json` changes.
- **`unifi-core` UIDB sync:**
  - Without merging custom models into `/data/unifi-core/uidb.json`, `unifi-core` logged `Unknown device reported. MAC: ..., shortname: RTL_LG510S` in `controllers.deviceList.log`.
  - Merging the custom entry into `/data/unifi-core/uidb.json` made `unifi-core` cleanly recognize and sync the device (`Adding LG510S (02510A000001) from network report`) to `unifi.ui.com`.
  - Note: `unifi-core` fetches upstream UIDB ~3 seconds after boot and overwrites `/data/unifi-core/uidb.json` on disk (while retaining the merged in-memory table until restart if the upstream version matches). Persisting this across restarts would have required intercepting `unifi-core`'s UIDB read/write path via a hook under `/data/unifi-core/config/overrides/`.

---

## 5. UniFi Inform Protocol (`TNBU`) & AES-128-GCM Discovery

To test custom models and `system.cfg` generation without hardware, we built a zero-dependency synthetic switch probe in [`scripts/probe_switch.py`](../scripts/probe_switch.py).

Key protocol findings on UniFi Network `11.1`:

- **40-byte `TNBU` binary header:** `>4sI6sH16sII` (`magic="TNBU"`, `version=0`, 6-byte `mac`, 16-bit `flags`, 16-byte `iv`, `data_version=1`, `payload_len`).
- **Mandatory AES-128-GCM after adoption:**
  - Unadopted discovery informs use AES-128-CBC (`flags=0x0001`) with the factory key `md5("ubnt")`.
  - On adoption, the controller sends `setparam` with `mgmt_cfg` containing the new 16-byte `authkey` **and `use_aes_gcm=true`**.
  - In `com.ubnt.service.devmgr.m.nPDRmaIXlYuMFtu`, if a device configured for `aesGcmInformEncryptionOnly()` continues sending CBC informs, the controller repeatedly responds with `mgmt_cfg` alone and **never transitions past `ADOPTING` (`state=7`), never emits `system_cfg`, and never populates `port_table`**.
  - Switching to **AES-128-GCM** (`flags=0x0009`, 16-byte IV, the **40-byte `TNBU` header as AAD**, and the 16-byte GCM authentication tag appended to ciphertext) immediately completes adoption, triggers full `system_cfg` provisioning, and transitions the device to `CONNECTED` (`state=1`).
- **Inform payload fields ingested by `devmgr`:** `serial` (uppercase MAC without colons), `ip`, `version`, `required_version`, `kernel_version`, `architecture`, `board_rev`, `hash_id`, `sysid`, `model`, `cfgversion`, `default`, `hostname`, `inform_url`, `uptime`, `sys_stats`, `system-stats`, `switch_caps`, `port_table`.
  - Note: `default: true` remains set in `config.device.dynamic_part` even on genuine adopted Ubiquiti switches (verified read-only against a real `USL8LP`).

---

## 6. Mobile App Validation: The A/B Experiment

Once `RTL_LG510S` (`02:51:0a:00:00:01`) was adopted and verified in both the local Web UI and `unifi.ui.com`, the UniFi Android app still displayed an empty device list (even after force-stopping and clearing app cache).

### Controlled A/B Test

We ran two adopted probes side-by-side on the same test controller using the exact same `probe_switch.py` script:

| Probe | MAC | `model` | `sysid` | Local Web UI | `unifi.ui.com` | UniFi Android App |
|---|---|---|---|---|---|---|
| Custom model | `02:51:0a:00:00:01` | `RTL_LG510S` | `0xf901` | Shown (10 ports, donor icon, `upgradable=false`) | Shown | **Hidden** (empty list; adoption push fails) |
| Control (real model) | `02:51:0a:00:00:08` | `USL8LP` | `0xed2a` | Shown | Shown | **Shown immediately** |

Diffing `/proxy/network/v2/api/site/default/device` and `/proxy/network/api/s/default/stat/device` confirmed that both devices returned structurally identical JSON objects—differing only in `model`, `name`, `upgradable`, and `port_table`.

### Why It Happens

Inspecting `/data/unifi-core/logs/http.log` during WebRTC sessions from both clients showed:

- **Web UI (local & `unifi.ui.com`)** fetches `/proxy/network/v2/api/site/default/models/<model>` from the controller, which serves our injected UIDB record.
- **UniFi Mobile App (Android)** calls `/proxy/network/v2/api/site/default/device` (no query params) and `/proxy/network/api/s/default/stat/device`, receives `HTTP 200` containing `RTL_LG510S`, and **never requests `/models/*` from the controller**. It validates `model` against Ubiquiti's cloud/bundled UIDB and discards unrecognized models client-side.

### Technical Footnotes (For Future Reference)

Two findings from `ace.jar` are worth recording if anyone ever revisits UniFi-only server work:

1. **Client platform headers:** `com.ubnt.ace.view.PJXgBbUiXtqUGyl` parses `x-client-platform` and `x-unifi-client-platform` into `ANDROID` (`unifi-network-android`, `network_android`, `android`), `IOS` (`unifi-network-ios`, `network_ios`, `ios`), or `WEB`. Server-side mobile-only response rewriting is therefore detectable, though rejected here because it causes desktop and mobile views to diverge.
2. **`sysid`-keyed donor overrides:** In `com.ubnt.service.devmgr.m.nPDRmaIXlYuMFtu` (lines 374–377), inform ingestion looks up `DeviceModel` by `sysid` first and falls back to `model` when `sysid` is unknown, while still storing the reported `sysid` on the device record. A device reporting an official donor `model` (for example, `US68P` — Switch Enterprise 8 PoE, 8× 2.5GE + 2× SFP+) with a private `sysid` (such as `0xf901`) would appear consistently on Web, `unifi.ui.com`, Android, and iOS while still allowing per-`sysid` firmware suppression and capability overrides without affecting genuine Ubiquiti hardware of the same model.

---

## 7. Summary of Artifacts Preserved in This Repository

- [`scripts/extract.sh`](../scripts/extract.sh), [`scripts/decompile.sh`](../scripts/decompile.sh), [`scripts/release_diff.py`](../scripts/release_diff.py): release extraction, Vineflower/Prettier decompilation, bytecode indexing, and 3-stage obfuscated rename diffing/mapping propagation.
- [`releases/`](../releases/) & [`mappings/symbols.json`](../mappings/symbols.json): release manifests (`10.5.67`, `11.1.50`, `11.1.56`) and curated symbol mappings.
- [`agent/`](../agent/): tested ByteBuddy/ASM Java 25 agent implementing copper EEE + Flow Control defaults, SFP guard, custom `DeviceModel` enum injection, UIDB merging, and firmware update suppression (`gradle test build` passing).
- [`scripts/probe_switch.py`](../scripts/probe_switch.py): standalone Python stdlib + `libcrypto` `TNBU` inform probe supporting both AES-128-CBC (`0x0001`) and AES-128-GCM (`0x0009`).
- [`scripts/deploy-ssh.sh`](../scripts/deploy-ssh.sh) & [`docker/`](../docker/): non-invasive `env-overrides` deployment and container overlay recipes.
