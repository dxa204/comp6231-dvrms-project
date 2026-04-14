#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROLE="${1:-}"

if [[ -z "${ROLE}" ]]; then
  echo "Usage: $0 <laptop-a|laptop-b|laptop-c|laptop-d>" >&2
  exit 1
fi

LOG_DIR="${ROOT_DIR}/out/distributed-logs/${ROLE}"
PID_FILE="${LOG_DIR}/pids.txt"

kill_pid() {
  local pid="$1"
  if kill -0 "${pid}" 2>/dev/null; then
    kill "${pid}" 2>/dev/null || true
  fi
}

kill_port() {
  local port="$1"
  local pids
  pids="$(lsof -ti :"${port}" 2>/dev/null || true)"
  if [[ -n "${pids}" ]]; then
    while IFS= read -r pid; do
      [[ -n "${pid}" ]] && kill "${pid}" 2>/dev/null || true
    done <<< "${pids}"
  fi
}

force_kill_port() {
  local port="$1"
  local pids
  pids="$(lsof -ti :"${port}" 2>/dev/null || true)"
  if [[ -n "${pids}" ]]; then
    while IFS= read -r pid; do
      [[ -n "${pid}" ]] && kill -9 "${pid}" 2>/dev/null || true
    done <<< "${pids}"
  fi
}

port_is_active() {
  local port="$1"
  lsof -ti :"${port}" >/dev/null 2>&1
}

if [[ -f "${PID_FILE}" ]]; then
  while read -r pid _name; do
    [[ -n "${pid}" ]] && kill_pid "${pid}"
  done < "${PID_FILE}"
fi

sleep 2

case "${ROLE}" in
  laptop-a)
    PORTS=(1050 5000 5001 6004 7004 7401 7402 7403)
    ;;
  laptop-b)
    PORTS=(6001 7001 7201 7202 7203)
    ;;
  laptop-c)
    PORTS=(6002 6012 6022 7002)
    ;;
  laptop-d)
    PORTS=(6003 7003 7101 7102 7103)
    ;;
  *)
    echo "Unknown role: ${ROLE}" >&2
    exit 1
    ;;
esac

for port in "${PORTS[@]}"; do
  kill_port "${port}"
done

sleep 2

for port in "${PORTS[@]}"; do
  force_kill_port "${port}"
done

sleep 1

remaining_ports=()
for port in "${PORTS[@]}"; do
  if port_is_active "${port}"; then
    remaining_ports+=("${port}")
  fi
done

if [[ ${#remaining_ports[@]} -eq 0 ]]; then
  rm -f "${PID_FILE}"
  echo "Stopped ${ROLE}. All expected ports are clear: ${PORTS[*]}"
else
  echo "Stopped ${ROLE}, but some ports are still active: ${remaining_ports[*]}"
  echo "Check each one with: lsof -nP -i :<PORT>"
  echo "If needed, force-kill with: kill -9 <PID>"
fi
