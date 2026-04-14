#!/usr/bin/env bash

set -euo pipefail

detect_primary_interface() {
  route -n get default 2>/dev/null | awk '/interface:/{print $2; exit}'
}

detect_local_ip() {
  local iface="$1"
  local ip=""

  if [[ -n "${iface}" ]]; then
    ip="$(ipconfig getifaddr "${iface}" 2>/dev/null || true)"
  fi

  if [[ -z "${ip}" ]]; then
    ip="$(ifconfig 2>/dev/null | awk '/inet / && $2 != "127.0.0.1" {print $2; exit}')"
  fi

  printf '%s\n' "${ip}"
}

INTERFACE="$(detect_primary_interface)"
LOCAL_IP="$(detect_local_ip "${INTERFACE}")"

if [[ -z "${LOCAL_IP}" ]]; then
  echo "Could not detect a non-loopback local IP address." >&2
  exit 1
fi

echo "Detected local IP: ${LOCAL_IP}"

if [[ -n "${INTERFACE}" ]]; then
  echo "Primary interface: ${INTERFACE}"
fi

cat <<EOF

Suggested exports for this laptop:
export DVRMS_FE_HOST=${LOCAL_IP}
export DVRMS_SEQUENCER_HOST=${LOCAL_IP}
export DVRMS_RM4_HOST=${LOCAL_IP}
export DVRMS_R4_HOST=${LOCAL_IP}

If this laptop is not laptop-a, use ${LOCAL_IP} only for the matching RM/replica host variables.
EOF
