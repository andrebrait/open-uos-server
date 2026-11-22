# Repository Instructions

## Proprietary Boundary

- Never commit Ubiquiti binaries (`ace.jar`, `internal-dependencies.jar`, `unifi-core` bundles, ELF executables) or raw decompiled source trees into git.
- All extracted artifacts, decompiled sources, downloaded tools (such as Vineflower), and bytecode indices live under `work/`, which is gitignored.
- Tracked in git:
  - `scripts/`: extraction, decompilation, bytecode indexing, release diffing, and symbol-mapping propagation tools.
  - `releases/<release-id>/manifest.json`: package versions, SHA-256 checksums, and ELF binary inventory (metadata only).
  - `mappings/symbols.json`: human-authored reverse-engineered symbol names and anchor metadata.
  - `agent/`: Java agent (`-javaagent`) runtime patches built with ByteBuddy/ASM.
  - `docker/`: container overlays for running patched UniFi Network Application and UniFi OS Server.

## Standard Workflow

1. Extract a release from a live `uosserver` host or Docker image:
   ```bash
   ./scripts/extract.sh --ssh unifi
   # or
   ./scripts/extract.sh --docker-network lscr.io/linuxserver/unifi-network-application:latest
   # or
   ./scripts/extract.sh --docker-uos ghcr.io/lemker/unifi-os-server:latest
   ```
2. Decompile Ubiquiti Java classes (Vineflower) and prettify `unifi-core` JS, then index bytecode:
   ```bash
   ./scripts/decompile.sh current
   ```
3. Search indexed bytecode without grepping raw decompiled trees:
   ```bash
   python3 scripts/release_diff.py search current "/api/s/"
   ```
4. Compare two releases (handles obfuscated class renames via string/annotation/signature matching):
   ```bash
   python3 scripts/release_diff.py diff <old-release-id> <new-release-id>
   python3 scripts/release_diff.py carry-mappings <old-release-id> <new-release-id> --write
   ```
5. Build the Java agent targeting OpenJDK 25:
   ```bash
   (cd agent && gradle build)
   ```
