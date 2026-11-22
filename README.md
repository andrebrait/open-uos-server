# open-uos-server

> **Project Status: Halted / Archived**
>
> This project has been halted and is preserved as an archive of tooling and reverse-engineering findings. While the Java agent patches (copper EEE/Flow Control defaults with SFP guard, native custom `DeviceModel` enum injection, UIDB merging, and firmware update suppression) worked end-to-end on both the local Web UI and `unifi.ui.com`, live A/B testing proved that the **UniFi Mobile App validates `device.model` client-side against Ubiquiti's own cloud/bundled UIDB** (never querying the controller's `/models/*` endpoint) and silently drops unrecognized custom models. Because patching mobile apps is unsustainable and client-specific aliasing causes desktop/mobile mismatch, development was stopped here.
>
> See **[docs/FINDINGS.md](docs/FINDINGS.md)** for the full post-mortem, architecture notes, `TNBU`/AES-128-GCM inform protocol discoveries, and A/B test results.

Tooling to extract, decompile, diff across releases, map obfuscated symbols, and non-invasively patch UniFi OS Server (`uosserver`) and the UniFi Network Application (`ace.jar`).

## Runtime Architecture

Inspection of a live `uosserver` installation shows three distinct runtimes:

1. **UniFi Network Application (`unifi`)**: OpenJDK 25 Spring Boot fat JAR (`/usr/lib/unifi/lib/ace.jar`).
   - `BOOT-INF/classes/`: launcher classes (`com.ubnt.ace.*`).
   - `BOOT-INF/lib/internal-dependencies.jar`: Ubiquiti application code (~25,000 Java/Kotlin `.class` files, heavily obfuscated).
2. **`unifi-core`**: Node.js 24 ESM bundle (`/usr/share/unifi-core/app/service.js` plus workers, hooks, and migrations).
3. **Host `uosserver` and container `uos-*` daemons**: stripped x86-64 ELF binaries (`/usr/local/bin/uosserver`, `uosserver-service`, `uos-agent`, `uos`, etc.).

Because `internal-dependencies.jar` contains ~25k obfuscated Java/Kotlin classes, full decompile-and-recompile is brittle. This repository instead uses a hybrid workflow:

- **Decompile & track releases locally** (in gitignored `work/releases/<id>/`):
  - **Vineflower** (actively maintained community fork of JetBrains' Fernflower with Java 25 and Kotlin support) decompiles `ace.jar` (`BOOT-INF/classes` + `internal-dependencies.jar`) with all `BOOT-INF/lib/*.jar` dependencies on the external classpath for accurate generics and overrides.
  - **Prettier** prettifies `unifi-core` JavaScript bundles.
  - **`scripts/release_diff.py`** parses JVM `.class` constant pools and annotations directly in Python stdlib to fingerprint every class by non-obfuscated anchors (string literals, annotation values/routes, superclass, interfaces, normalized method descriptors). Across Ubiquiti upgrades, it detects renamed obfuscated classes, generates a Markdown changelog, and propagates `mappings/symbols.json`.
- **Patch non-invasively at runtime**:
  - **Java Agent (`agent/`)**: ByteBuddy/ASM `-javaagent` loaded via `JAVA_TOOL_OPTIONS` (works on standalone [`ghcr.io/jamesbraid/unifi-network`](https://github.com/jamesbraid/unifi-containers/pkgs/container/unifi-network), `lscr.io/linuxserver/unifi-network-application`, and `uosserver` systemd `unifi.service` drop-ins).
  - **Docker Overlays (`docker/`)**: builds patched images on top of [`ghcr.io/jamesbraid/unifi-network`](https://github.com/jamesbraid/unifi-containers/pkgs/container/unifi-network) (or `lscr.io/linuxserver/unifi-network-application`) and [`ghcr.io/lemker/unifi-os-server`](https://github.com/lemker/unifi-os-server).

## Quick Start

### 1. Extract a Release

From a live `uosserver` host over SSH (default host alias `unifi`):

```bash
./scripts/extract.sh --ssh unifi
```

From a Docker image:

```bash
./scripts/extract.sh --docker-network ghcr.io/jamesbraid/unifi-network:latest
./scripts/extract.sh --docker-network lscr.io/linuxserver/unifi-network-application:latest
./scripts/extract.sh --docker-uos ghcr.io/lemker/unifi-os-server:latest
```

Extraction saves binaries into gitignored `work/releases/<id>/raw/` and records version/checksum metadata in `releases/<id>/manifest.json`.

### 2. Decompile and Index

```bash
./scripts/decompile.sh current
```

Optional flags:

- `--skip-java` / `--skip-js`: run only one side.
- `--filter <prefix>`: decompile only matching class paths (for example, `com/ubnt/ace`) for a quick pass.

### 3. Search, Diff Releases, and Carry Mappings

```bash
# Instant bytecode search by string literal, REST path, annotation, or class name
python3 scripts/release_diff.py search current "RestController"
python3 scripts/release_diff.py search current "/api/s/"

# Compare two extracted releases (tracks obfuscated class renames)
python3 scripts/release_diff.py diff <old-id> <new-id> --out work/diff.md

# Propagate human-curated mappings/symbols.json to the new release
python3 scripts/release_diff.py carry-mappings <old-id> <new-id> --write
```

### 4. Build and Deploy the Java Agent

```bash
# Run unit tests and build fat agent JAR (targets Java 25 bytecode)
(cd agent && gradle test build)

# Deploy agent + custom model definitions to a test VM running official uosserver
# (persists across upgrades via /var/lib/unifi/env-overrides)
./scripts/deploy-ssh.sh <test-vm-ssh-host> --models config/custom-models.example.json
./scripts/deploy-ssh.sh <test-vm-ssh-host> --status
./scripts/deploy-ssh.sh <test-vm-ssh-host> --disable
```

### Active Runtime Patches

All ASM/ByteBuddy instrumentation in `agent/` resolves targets by unobfuscated method names, parameter shapes, and literal string anchors so it survives Ubiquiti's per-build obfuscator dictionary rotation:

1. **Copper-Port EEE + Flow Control (Pause) Defaults & SFP Guard**:
   - Defaults EEE (`eee_enabled`) and Flow Control (`flowctrl_enabled` / `flow_control_enabled`) to **enabled** on copper ports (`FE`, `GE`, `2.5GE`, `5GE`, `10GE`), while still respecting explicit per-port `false` overrides.
   - Never enables EEE or Flow Control on SFP/fiber ports (`SFP`, `SFP+`, `SFP28`, `QSFP28`): suppresses `Device.hasPortEeeCapability(idx)` and blocks `switch.port.<idx>.flowctrl=enabled` in both USW and legacy switch `system.cfg` generators.
2. **Custom Device / Switch Models & Suppressed Firmware Updates**:
   - Reads external definitions from `/var/lib/unifi/open-uos/custom-models.json` (see [config/custom-models.example.json](config/custom-models.example.json); override via `-Dopenuos.models.file=...` or agent arg `models=...`).
   - Injects real enum constants into UniFi's `DeviceModel` enum bytecode before `<clinit>` runs so `values()`, `valueOf()`, `EnumSet`, `EnumMap`, `sysid` lookups, and `productInfoTableGenerator` recognize custom models natively.
   - Merges synthetic UIDB entries into loaded `uidb.json` snapshots (supporting `icon_model` icon/image inheritance from existing UniFi models).
   - Suppresses firmware updates for custom models (`setUpgradable` / `setUpgradableOnAdoption` forced `false`, and no upstream firmware entry), treating any reported firmware version as up to date.

Optional Docker overlays are also provided under `docker/`:

```bash
# Build patched UniFi Network Application overlay
docker compose -f docker/network/compose.yml build

# Build patched UniFi OS Server overlay
docker compose -f docker/uosserver/compose.yml build
```

## License & Disclaimer

This repository's scripts, agent framework, mappings, and Docker overlays are licensed under the [GNU Affero General Public License v3.0](LICENSE) (`AGPL-3.0-or-later`).

**Non-affiliation & Proprietary Boundary:** This project is an independent community effort and is not affiliated with, endorsed by, or sponsored by Ubiquiti Inc. Ubiquiti binaries (`ace.jar`, `unifi-core`, ELF executables) and raw decompiled source trees are excluded via `.gitignore` (`work/`) and must never be committed to version control. Users must supply their own lawfully obtained upstream installation.
