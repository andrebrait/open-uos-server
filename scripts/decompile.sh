#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="$ROOT_DIR/work"
TOOLS_DIR="$WORK_DIR/.tools"

VINEFLOWER_VERSION="1.12.0"
VINEFLOWER_URL="https://repo1.maven.org/maven2/org/vineflower/vineflower/${VINEFLOWER_VERSION}/vineflower-${VINEFLOWER_VERSION}.jar"
VINEFLOWER_JAR="$TOOLS_DIR/vineflower-${VINEFLOWER_VERSION}.jar"

RELEASE_REF="${1:-current}"
shift || true

SKIP_JAVA=0
SKIP_JS=0
SKIP_INDEX=0
FILTER_PREFIX=""
THREADS="$(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo 4)"
HEAP="6g"

usage() {
  cat <<'EOF'
Usage:
  scripts/decompile.sh [release-id|current] [options]

Options:
  --filter <prefix>   Decompile only matching class path prefix (e.g. com/ubnt/ace)
  --threads <n>       Vineflower worker threads (default: CPU count)
  --heap <size>       JVM max heap for Vineflower (default: 6g)
  --skip-java         Skip Java decompilation
  --skip-js           Skip unifi-core JS prettification
  --skip-index        Skip bytecode indexing (release_diff.py index)
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --filter)
      FILTER_PREFIX="${2:?missing filter prefix}"
      shift 2
      ;;
    --threads)
      THREADS="${2:?missing thread count}"
      shift 2
      ;;
    --heap)
      HEAP="${2:?missing heap size}"
      shift 2
      ;;
    --skip-java)
      SKIP_JAVA=1
      shift
      ;;
    --skip-js)
      SKIP_JS=1
      shift
      ;;
    --skip-index)
      SKIP_INDEX=1
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

if [[ "$RELEASE_REF" == "current" ]]; then
  if [[ ! -L "$WORK_DIR/current" ]]; then
    echo "No work/current symlink found. Run scripts/extract.sh first." >&2
    exit 1
  fi
  RELEASE_ID="$(basename "$(readlink "$WORK_DIR/current")")"
else
  RELEASE_ID="$RELEASE_REF"
fi

REL_DIR="$WORK_DIR/releases/$RELEASE_ID"
RAW_DIR="$REL_DIR/raw"
UNPACK_DIR="$REL_DIR/unpacked"
DECOMP_DIR="$REL_DIR/decompiled"

if [[ ! -f "$RAW_DIR/ace.jar" ]]; then
  echo "Missing $RAW_DIR/ace.jar. Run scripts/extract.sh first." >&2
  exit 1
fi

ensure_vineflower() {
  mkdir -p "$TOOLS_DIR"
  if [[ ! -f "$VINEFLOWER_JAR" ]]; then
    echo "[decompile] Downloading Vineflower ${VINEFLOWER_VERSION}..."
    curl -fsSL "$VINEFLOWER_URL" -o "${VINEFLOWER_JAR}.tmp"
    mv "${VINEFLOWER_JAR}.tmp" "$VINEFLOWER_JAR"
  fi
}

unpack_ace() {
  if [[ -f "$UNPACK_DIR/.unpacked" ]]; then
    return
  fi
  echo "[decompile] Unpacking ace.jar..."
  rm -rf "$UNPACK_DIR"
  mkdir -p "$UNPACK_DIR/boot-classes" "$UNPACK_DIR/libs"
  python3 - "$RAW_DIR/ace.jar" "$UNPACK_DIR" <<'PY'
import sys, zipfile
from pathlib import Path

ace_jar, out_dir = Path(sys.argv[1]), Path(sys.argv[2])
boot_dir = out_dir / "boot-classes"
libs_dir = out_dir / "libs"

with zipfile.ZipFile(ace_jar) as z:
    for info in z.infolist():
        name = info.filename
        if name.endswith("/"):
            continue
        if name == "BOOT-INF/lib/internal-dependencies.jar":
            (out_dir / "internal-dependencies.jar").write_bytes(z.read(info))
        elif name.startswith("BOOT-INF/lib/") and name.endswith(".jar"):
            (libs_dir / Path(name).name).write_bytes(z.read(info))
        elif name.startswith("BOOT-INF/classes/"):
            rel = name[len("BOOT-INF/classes/"):]
            dest = boot_dir / rel
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(z.read(info))
PY
  touch "$UNPACK_DIR/.unpacked"
}

decompile_java() {
  ensure_vineflower
  unpack_ace

  local java_out="$DECOMP_DIR/unifi-network"
  mkdir -p "$java_out"

  local vf_flags=(
    "--folder"
    "--decompile-generics=1"
    "--remove-bridge=1"
    "--remove-synthetic=1"
    "--include-runtime=current"
    "--indent-string=    "
    "--threads=${THREADS}"
    "--log-level=WARN"
    "-e=${UNPACK_DIR}/libs"
  )

  if [[ -n "$FILTER_PREFIX" ]]; then
    local filtered_jar="$UNPACK_DIR/filtered-$(echo "$FILTER_PREFIX" | tr '/' '_').jar"
    echo "[decompile] Filtering classes matching prefix '$FILTER_PREFIX'..."
    python3 - "$UNPACK_DIR/internal-dependencies.jar" "$UNPACK_DIR/boot-classes" "$FILTER_PREFIX" "$filtered_jar" <<'PY'
import sys, zipfile
from pathlib import Path

inner_jar, boot_dir, prefix, out_jar = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3].strip("/"), Path(sys.argv[4])
count = 0
with zipfile.ZipFile(out_jar, "w", compression=zipfile.ZIP_DEFLATED) as dst:
    if inner_jar.exists():
        with zipfile.ZipFile(inner_jar) as src:
            for info in src.infolist():
                if info.filename.startswith(prefix) and not info.filename.endswith("/"):
                    dst.writestr(info.filename, src.read(info))
                    count += 1
    for p in boot_dir.rglob("*"):
        if p.is_file():
            rel = p.relative_to(boot_dir).as_posix()
            if rel.startswith(prefix):
                dst.writestr(rel, p.read_bytes())
                count += 1
print(f"[decompile] Matched {count} entries for prefix '{prefix}'")
if count == 0:
    sys.exit(1)
PY
    echo "[decompile] Running Vineflower on filtered jar (${THREADS} threads)..."
    java "-Xmx${HEAP}" -jar "$VINEFLOWER_JAR" "${vf_flags[@]}" "$filtered_jar" "$java_out"
  else
    echo "[decompile] Decompiling BOOT-INF/classes..."
    java "-Xmx${HEAP}" -jar "$VINEFLOWER_JAR" "${vf_flags[@]}" \
      "-e=${UNPACK_DIR}/internal-dependencies.jar" \
      "$UNPACK_DIR/boot-classes" "$java_out"

    if [[ -f "$UNPACK_DIR/internal-dependencies.jar" ]]; then
      echo "[decompile] Decompiling internal-dependencies.jar (${THREADS} threads, heap=${HEAP})..."
      java "-Xmx${HEAP}" -jar "$VINEFLOWER_JAR" "${vf_flags[@]}" \
        "$UNPACK_DIR/internal-dependencies.jar" "$java_out"
    fi
  fi
  echo "[decompile] Java output: $java_out"
}

decompile_js() {
  if [[ ! -d "$RAW_DIR/unifi-core" ]]; then
    echo "[decompile] No unifi-core extracted for $RELEASE_ID, skipping JS."
    return
  fi

  local js_out="$DECOMP_DIR/unifi-core"
  rm -rf "$js_out"
  mkdir -p "$js_out"
  cp -R "$RAW_DIR/unifi-core/." "$js_out/"

  local js_files=()
  for f in "$js_out"/*.js; do
    [[ -f "$f" ]] && js_files+=("$f")
  done

  if [[ "${#js_files[@]}" -gt 0 ]]; then
    if command -v npx >/dev/null 2>&1; then
      echo "[decompile] Prettifying unifi-core JS bundles with Prettier..."
      npx --yes prettier@3 --no-config --ignore-path /dev/null --write --parser babel "${js_files[@]}" >/dev/null
    else
      echo "[decompile] npx not found; leaving unifi-core JS unformatted." >&2
    fi
  fi
  echo "[decompile] JS output: $js_out"
}

if [[ "$SKIP_JAVA" -eq 0 ]]; then
  decompile_java
fi

if [[ "$SKIP_JS" -eq 0 ]]; then
  decompile_js
fi

if [[ "$SKIP_INDEX" -eq 0 ]]; then
  python3 "$ROOT_DIR/scripts/release_diff.py" index "$RELEASE_ID"
fi
