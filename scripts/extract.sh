#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="$ROOT_DIR/work"
RELEASES_META_DIR="$ROOT_DIR/releases"

SOURCE_MODE=""
TARGET=""
CUSTOM_ID=""
WITH_NATIVE=0

usage() {
  cat <<'EOF'
Usage:
  scripts/extract.sh --ssh <host> [--id <release-id>] [--with-native]
  scripts/extract.sh --docker-network <image> [--id <release-id>]
  scripts/extract.sh --docker-uos <image> [--id <release-id>] [--with-native]

Examples:
  scripts/extract.sh --ssh unifi
  scripts/extract.sh --docker-network ghcr.io/jamesbraid/unifi-network:latest
  scripts/extract.sh --docker-network lscr.io/linuxserver/unifi-network-application:latest
  scripts/extract.sh --docker-uos ghcr.io/lemker/unifi-os-server:latest
EOF
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --ssh)
      SOURCE_MODE="ssh"
      TARGET="${2:?missing ssh host}"
      shift 2
      ;;
    --docker-network)
      SOURCE_MODE="docker-network"
      TARGET="${2:?missing docker image}"
      shift 2
      ;;
    --docker-uos)
      SOURCE_MODE="docker-uos"
      TARGET="${2:?missing docker image}"
      shift 2
      ;;
    --id)
      CUSTOM_ID="${2:?missing release id}"
      shift 2
      ;;
    --with-native)
      WITH_NATIVE=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if [[ -z "$SOURCE_MODE" ]]; then
  usage >&2
  exit 1
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

mkdir -p "$TMP_DIR/raw"

extract_from_ssh() {
  local host="$1"
  echo "[extract] Querying package versions on $host..."
  ssh -o BatchMode=yes "$host" bash -s <<'REMOTE' > "$TMP_DIR/packages.tsv" || true
su - uosserver -c 'XDG_RUNTIME_DIR=/run/user/1000 podman exec uosserver dpkg-query -W' \
  | grep -E '^(unifi|uos|ubnt|ucs-|mongodb-)'
REMOTE

  echo "[extract] Recording host & container ELF binary inventory..."
  ssh -o BatchMode=yes "$host" bash -s <<'REMOTE' > "$TMP_DIR/raw/elf-inventory.tsv"
echo "# host"
for f in /usr/local/bin/uosserver /var/lib/uosserver/bin/*; do
  [ -f "$f" ] || continue
  sz="$(stat -c %s "$f")"
  sha="$(sha256sum "$f" | cut -d' ' -f1)"
  ft="$(file -b "$f" 2>/dev/null || echo binary)"
  printf "host\t%s\t%s\t%s\t%s\n" "$f" "$sz" "$sha" "$ft"
done
echo "# container"
su - uosserver -c 'XDG_RUNTIME_DIR=/run/user/1000 podman exec -i uosserver bash -s' <<'INNER'
for f in /usr/bin/uos /usr/bin/uos-agent /usr/bin/uos-discovery-client /usr/sbin/unifi-directory-app /usr/sbin/unifi-identity-update-app /usr/share/unifi-matter-controller/bin/unifi-matter-controller; do
  [ -f "$f" ] || continue
  sz="$(stat -c %s "$f")"
  sha="$(sha256sum "$f" | cut -d' ' -f1)"
  ft="$(file -b "$f" 2>/dev/null || echo binary)"
  printf "container\t%s\t%s\t%s\t%s\n" "$f" "$sz" "$sha" "$ft"
done
INNER
REMOTE

  echo "[extract] Copying /usr/lib/unifi/lib/ace.jar..."
  ssh -o BatchMode=yes "$host" \
    'su - uosserver -c "XDG_RUNTIME_DIR=/run/user/1000 podman exec uosserver cat /usr/lib/unifi/lib/ace.jar"' \
    > "$TMP_DIR/raw/ace.jar"

  echo "[extract] Copying /usr/share/unifi-core/app (excluding node_modules)..."
  mkdir -p "$TMP_DIR/raw/unifi-core"
  local tar_excludes="--exclude=node_modules"
  if [[ "$WITH_NATIVE" -eq 0 ]]; then
    tar_excludes="$tar_excludes --exclude=*.node"
  fi
  ssh -o BatchMode=yes "$host" \
    "su - uosserver -c \"XDG_RUNTIME_DIR=/run/user/1000 podman exec uosserver tar -C /usr/share/unifi-core/app $tar_excludes -cf - .\"" \
    | tar -C "$TMP_DIR/raw/unifi-core" -xf -

  echo "[extract] Recording container metadata..."
  ssh -o BatchMode=yes "$host" \
    'su - uosserver -c "XDG_RUNTIME_DIR=/run/user/1000 podman inspect uosserver --format \"{{.ImageName}} {{.Image}}\""' \
    > "$TMP_DIR/image.txt" 2>/dev/null || true
}

extract_from_docker() {
  local image="$1"
  local mode="$2"
  echo "[extract] Creating temporary container from $image..."
  local cid
  cid="$(docker create "$image")"
  trap 'docker rm -f "$cid" >/dev/null 2>&1 || true; rm -rf "$TMP_DIR"' EXIT

  docker inspect "$image" --format '{{.Id}}' > "$TMP_DIR/image.txt" 2>/dev/null || true

  # Locate ace.jar inside container (/usr/lib/unifi/lib/ace.jar or /app/unifi/lib/ace.jar)
  echo "[extract] Copying ace.jar..."
  if ! docker cp "$cid:/usr/lib/unifi/lib/ace.jar" "$TMP_DIR/raw/ace.jar" 2>/dev/null; then
    docker cp "$cid:/app/unifi/lib/ace.jar" "$TMP_DIR/raw/ace.jar"
  fi

  # Try extracting dpkg status for package versions
  if docker cp "$cid:/var/lib/dpkg/status" "$TMP_DIR/dpkg-status" >/dev/null 2>&1; then
    python3 - "$TMP_DIR/dpkg-status" > "$TMP_DIR/packages.tsv" <<'PY'
import sys, re
text = open(sys.argv[1], errors="replace").read()
for block in text.split("\n\n"):
    pkg = re.search(r"^Package:\s*(\S+)", block, re.M)
    ver = re.search(r"^Version:\s*(\S+)", block, re.M)
    if pkg and ver and re.match(r"^(unifi|uos|ubnt|ucs-|mongodb-)", pkg.group(1)):
        print(f"{pkg.group(1)}\t{ver.group(1)}")
PY
  else
    : > "$TMP_DIR/packages.tsv"
  fi

  if [[ "$mode" == "docker-uos" ]]; then
    echo "[extract] Copying /usr/share/unifi-core/app..."
    mkdir -p "$TMP_DIR/raw/unifi-core"
    if docker cp "$cid:/usr/share/unifi-core/app/." "$TMP_DIR/raw/unifi-core/" >/dev/null 2>&1; then
      rm -rf "$TMP_DIR/raw/unifi-core/node_modules"
      if [[ "$WITH_NATIVE" -eq 0 ]]; then
        find "$TMP_DIR/raw/unifi-core" -name '*.node' -delete
      fi
    fi
  fi
  : > "$TMP_DIR/raw/elf-inventory.tsv"
}

case "$SOURCE_MODE" in
  ssh) extract_from_ssh "$TARGET" ;;
  docker-network) extract_from_docker "$TARGET" "docker-network" ;;
  docker-uos) extract_from_docker "$TARGET" "docker-uos" ;;
esac

UNIFI_VER="$(awk -F'\t' '$1=="unifi" {print $2; exit}' "$TMP_DIR/packages.tsv" || true)"
CORE_VER="$(awk -F'\t' '$1=="unifi-core" {print $2; exit}' "$TMP_DIR/packages.tsv" || true)"
ACE_SHA="$(sha256_file "$TMP_DIR/raw/ace.jar")"

if [[ -n "$CUSTOM_ID" ]]; then
  RELEASE_ID="$CUSTOM_ID"
elif [[ -n "$UNIFI_VER" && -n "$CORE_VER" ]]; then
  RELEASE_ID="unifi-${UNIFI_VER}_core-${CORE_VER}"
elif [[ -n "$UNIFI_VER" ]]; then
  RELEASE_ID="unifi-${UNIFI_VER}"
else
  RELEASE_ID="unifi-${ACE_SHA:0:12}"
fi

# Sanitize release id for filesystem safety
RELEASE_ID="$(echo "$RELEASE_ID" | tr '/: ' '---')"

DEST_WORK="$WORK_DIR/releases/$RELEASE_ID"
DEST_META="$RELEASES_META_DIR/$RELEASE_ID"
mkdir -p "$DEST_WORK" "$DEST_META"
rm -rf "$DEST_WORK/raw"
mv "$TMP_DIR/raw" "$DEST_WORK/raw"

python3 - "$RELEASE_ID" "$SOURCE_MODE" "$TARGET" "$DEST_WORK/raw" "$TMP_DIR/packages.tsv" "$TMP_DIR/image.txt" "$DEST_META/manifest.json" <<'PY'
import hashlib, json, os, sys
from datetime import datetime, timezone
from pathlib import Path

release_id, source_mode, target, raw_dir, pkgs_file, image_file, out_file = sys.argv[1:]
raw_path = Path(raw_dir)

def sha256(p: Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

packages = {}
if os.path.exists(pkgs_file):
    for line in open(pkgs_file):
        line = line.strip()
        if not line or "\t" not in line:
            continue
        k, v = line.split("\t", 1)
        packages[k] = v

elf_inventory = []
elf_path = raw_path / "elf-inventory.tsv"
if elf_path.exists():
    for line in elf_path.read_text().splitlines():
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) >= 5:
            elf_inventory.append({
                "scope": parts[0],
                "path": parts[1],
                "size_bytes": int(parts[2]),
                "sha256": parts[3],
                "file_type": parts[4],
            })

ace_path = raw_path / "ace.jar"
artifacts = {
    "ace_jar": {
        "size_bytes": ace_path.stat().st_size,
        "sha256": sha256(ace_path),
    }
}

core_service = raw_path / "unifi-core" / "service.js"
if core_service.exists():
    artifacts["unifi_core_service_js"] = {
        "size_bytes": core_service.stat().st_size,
        "sha256": sha256(core_service),
    }

image_info = Path(image_file).read_text().strip() if os.path.exists(image_file) else ""

manifest = {
    "release_id": release_id,
    "extracted_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "source": {
        "mode": source_mode,
        "target": target if source_mode != "ssh" else None,
        "image": image_info or None,
    },
    "packages": packages,
    "artifacts": artifacts,
    "elf_inventory": elf_inventory,
}

Path(out_file).write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
print(f"[extract] Wrote manifest: {out_file}")
PY

ln -sfn "releases/$RELEASE_ID" "$WORK_DIR/current"
echo "[extract] Done: $RELEASE_ID"
echo "[extract] Raw files: $DEST_WORK/raw"
echo "[extract] Symlink:   $WORK_DIR/current -> releases/$RELEASE_ID"
