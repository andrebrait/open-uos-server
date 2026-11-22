#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AGENT_JAR="$ROOT_DIR/agent/build/libs/open-uos-agent.jar"
CONTAINER_AGENT_DIR="/var/lib/unifi/open-uos"
CONTAINER_AGENT_JAR="$CONTAINER_AGENT_DIR/open-uos-agent.jar"
CONTAINER_MODELS_JSON="$CONTAINER_AGENT_DIR/custom-models.json"
CONTAINER_ENV_FILE="/var/lib/unifi/env-overrides"

HOST=""
ACTION="deploy"
BUILD=1
ALLOW_PROD=0
AGENT_ARGS=""
MODELS_FILE=""

usage() {
  cat <<'EOF'
Usage:
  scripts/deploy-ssh.sh <ssh-host> [options]

Deploys open-uos-agent.jar to an official uosserver host using the persistent
/var/lib/unifi volume and unifi.service's native EnvironmentFile hook
(/var/lib/unifi/env-overrides). Survives uosserver container upgrades.

Options:
  --no-build          Skip `gradle build` before deploying
  --models <path>     Also upload custom-models.json to /var/lib/unifi/open-uos/custom-models.json
  --args <str>        Pass agent arguments (-javaagent:jar=<str>)
  --disable           Remove JAVA_TOOL_OPTIONS override and restart unifi
  --status            Show installed agent status and recent journal lines
  --allow-prod        Allow targeting hosts listed in OPEN_UOS_PROTECTED_HOSTS

Environment:
  OPEN_UOS_PROTECTED_HOSTS  Comma-separated SSH hosts/IPs blocked unless --allow-prod
                            (default: "unifi")
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-build)
      BUILD=0
      shift
      ;;
    --models)
      MODELS_FILE="${2:?missing models json path}"
      shift 2
      ;;
    --args)
      AGENT_ARGS="${2:?missing agent args}"
      shift 2
      ;;
    --disable)
      ACTION="disable"
      shift
      ;;
    --status)
      ACTION="status"
      shift
      ;;
    --allow-prod)
      ALLOW_PROD=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    -*)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
    *)
      if [[ -z "$HOST" ]]; then
        HOST="$1"
      else
        echo "Unexpected argument: $1" >&2
        exit 1
      fi
      shift
      ;;
  esac
done

if [[ -z "$HOST" ]]; then
  usage >&2
  exit 1
fi

if [[ "$ACTION" != "status" && "$ALLOW_PROD" -eq 0 ]]; then
  PROTECTED_HOSTS="${OPEN_UOS_PROTECTED_HOSTS:-unifi}"
  HOST_NO_USER="${HOST#*@}"
  IFS=',' read -r -a _protected <<< "$PROTECTED_HOSTS"
  for _p in "${_protected[@]}"; do
    [[ -z "$_p" ]] && continue
    if [[ "$HOST" == "$_p" || "$HOST_NO_USER" == "$_p" ]]; then
      echo "Refusing to modify protected host '$HOST' without --allow-prod (OPEN_UOS_PROTECTED_HOSTS=$PROTECTED_HOSTS)." >&2
      exit 1
    fi
  done
fi

run_in_uos() {
  ssh -o BatchMode=yes "$HOST" "su - uosserver -c 'XDG_RUNTIME_DIR=/run/user/1000 podman exec -i uosserver bash -s'"
}

case "$ACTION" in
  status)
    run_in_uos <<EOF
echo "=== Agent JAR & Models ==="
ls -lh "$CONTAINER_AGENT_JAR" "$CONTAINER_MODELS_JSON" 2>&1 || true
echo "=== $CONTAINER_ENV_FILE ==="
cat "$CONTAINER_ENV_FILE" 2>&1 || echo "(not present)"
echo "=== unifi.service ==="
systemctl is-active unifi.service || true
echo "=== Recent agent logs ==="
journalctl -u unifi.service -n 500 --no-pager | grep -E 'open-uos-agent|Picked up JAVA_TOOL_OPTIONS' | tail -n 20 || echo "(no matching log lines)"
EOF
    ;;

  disable)
    echo "[deploy] Removing JAVA_TOOL_OPTIONS override on $HOST..."
    run_in_uos <<EOF
set -euo pipefail
if [ -f "$CONTAINER_ENV_FILE" ]; then
  grep -v '^JAVA_TOOL_OPTIONS=' "$CONTAINER_ENV_FILE" > "${CONTAINER_ENV_FILE}.tmp" || true
  if [ -s "${CONTAINER_ENV_FILE}.tmp" ]; then
    mv "${CONTAINER_ENV_FILE}.tmp" "$CONTAINER_ENV_FILE"
    chown unifi:unifi "$CONTAINER_ENV_FILE"
  else
    rm -f "${CONTAINER_ENV_FILE}.tmp" "$CONTAINER_ENV_FILE"
  fi
fi
systemctl restart unifi.service
systemctl is-active unifi.service
EOF
    echo "[deploy] Agent disabled and unifi.service restarted on $HOST."
    ;;

  deploy)
    if [[ "$BUILD" -eq 1 ]]; then
      echo "[deploy] Building open-uos-agent.jar..."
      (cd "$ROOT_DIR/agent" && gradle -q build)
    fi
    if [[ ! -f "$AGENT_JAR" ]]; then
      echo "Missing $AGENT_JAR. Build failed or run without --no-build." >&2
      exit 1
    fi

    AGENT_SPEC="-javaagent:${CONTAINER_AGENT_JAR}"
    if [[ -n "$AGENT_ARGS" ]]; then
      AGENT_SPEC="${AGENT_SPEC}=${AGENT_ARGS}"
    fi

    echo "[deploy] Uploading $AGENT_JAR to $HOST:$CONTAINER_AGENT_JAR..."
    ssh -o BatchMode=yes "$HOST" \
      "su - uosserver -c 'XDG_RUNTIME_DIR=/run/user/1000 podman exec -i uosserver bash -c \"mkdir -p $CONTAINER_AGENT_DIR && cat > ${CONTAINER_AGENT_JAR}.tmp && mv ${CONTAINER_AGENT_JAR}.tmp $CONTAINER_AGENT_JAR && chown -R unifi:unifi $CONTAINER_AGENT_DIR && chmod 0644 $CONTAINER_AGENT_JAR\"'" \
      < "$AGENT_JAR"

    if [[ -n "$MODELS_FILE" ]]; then
      if [[ ! -f "$MODELS_FILE" ]]; then
        echo "Missing models file: $MODELS_FILE" >&2
        exit 1
      fi
      echo "[deploy] Uploading $MODELS_FILE to $HOST:$CONTAINER_MODELS_JSON..."
      ssh -o BatchMode=yes "$HOST" \
        "su - uosserver -c 'XDG_RUNTIME_DIR=/run/user/1000 podman exec -i uosserver bash -c \"mkdir -p $CONTAINER_AGENT_DIR && cat > ${CONTAINER_MODELS_JSON}.tmp && mv ${CONTAINER_MODELS_JSON}.tmp $CONTAINER_MODELS_JSON && chown unifi:unifi $CONTAINER_MODELS_JSON && chmod 0644 $CONTAINER_MODELS_JSON\"'" \
        < "$MODELS_FILE"
    fi

    echo "[deploy] Configuring $CONTAINER_ENV_FILE and restarting unifi.service..."
    run_in_uos <<EOF
set -euo pipefail
touch "$CONTAINER_ENV_FILE"
grep -v '^JAVA_TOOL_OPTIONS=' "$CONTAINER_ENV_FILE" > "${CONTAINER_ENV_FILE}.tmp" || true
printf 'JAVA_TOOL_OPTIONS="%s"\n' "$AGENT_SPEC" >> "${CONTAINER_ENV_FILE}.tmp"
mv "${CONTAINER_ENV_FILE}.tmp" "$CONTAINER_ENV_FILE"
chown unifi:unifi "$CONTAINER_ENV_FILE"
chmod 0644 "$CONTAINER_ENV_FILE"
systemctl restart unifi.service
EOF

    echo "[deploy] Checking startup logs..."
    run_in_uos <<'EOF'
journalctl -u unifi.service -n 500 --no-pager | grep -E 'open-uos-agent|Picked up JAVA_TOOL_OPTIONS' | tail -n 10 || true
EOF
    echo "[deploy] Done."
    ;;
esac
