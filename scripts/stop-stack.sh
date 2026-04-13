#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_FILE="${ROOT_DIR}/out/stack-logs/pids.txt"
STACK_PORTS=(
  1050
  5000 5001
  6001 6002 6003 6004
  7001 7002 7003 7004
  7101 7102 7103
  7201 7202 7203
  7401 7402 7403
)
STACK_PATTERNS=(
  "com.sun.corba.se.impl.naming.cosnaming.TransientNameServer"
  "com.dvrms.sequencer.Sequencer"
  "com.dvrms.replica_manager.ReplicaManager"
  "com.dvrms.replica1.Replica1Server"
  "com.dvrms.replica2.VehicleServer"
  "com.dvrms.replica3.Replica3Server"
  "com.dvrms.replica4.Replica4Server"
  "com.dvrms.frontend.CORBAServer"
  "com.dvrms.frontend.FrontEndCLI"
  "com.dvrms.frontend.FrontEndClient"
)

if [[ ! -f "${PID_FILE}" ]]; then
  echo "PID file not found: ${PID_FILE}" >&2
  touch "${PID_FILE}"
fi

collect_pids() {
  local collected=()

  while IFS= read -r pid _; do
    if [[ -n "${pid}" ]]; then
      collected+=("${pid}")
    fi
  done < "${PID_FILE}"

  if command -v lsof >/dev/null 2>&1; then
    for port in "${STACK_PORTS[@]}"; do
      while IFS= read -r pid; do
        if [[ -n "${pid}" ]]; then
          collected+=("${pid}")
        fi
      done < <(lsof -tiTCP:"${port}" -sTCP:LISTEN 2>/dev/null || true)

      while IFS= read -r pid; do
        if [[ -n "${pid}" ]]; then
          collected+=("${pid}")
        fi
      done < <(lsof -tiUDP:"${port}" 2>/dev/null || true)
    done
  fi

  if command -v pgrep >/dev/null 2>&1; then
    for pattern in "${STACK_PATTERNS[@]}"; do
      while IFS= read -r pid; do
        if [[ -n "${pid}" ]]; then
          collected+=("${pid}")
        fi
      done < <(pgrep -f "${pattern}" 2>/dev/null || true)
    done
  fi

  if [[ "${#collected[@]}" -eq 0 ]]; then
    return 0
  fi

  local unique=()
  local seen=" "
  local pid
  for pid in "${collected[@]}"; do
    if [[ "${seen}" != *" ${pid} "* ]]; then
      unique+=("${pid}")
      seen+="$(printf '%s ' "${pid}")"
    fi
  done

  printf '%s\n' "${unique[@]}"
}

signal_pids() {
  local signal="$1"
  shift
  local pid
  for pid in "$@"; do
    [[ -n "${pid}" ]] || continue
    kill "-${signal}" "${pid}" 2>/dev/null || true
  done
}

mapfile_pids=()
while IFS= read -r pid; do
  [[ -n "${pid}" ]] && mapfile_pids+=("${pid}")
done < <(collect_pids)

if [[ "${#mapfile_pids[@]}" -eq 0 ]]; then
  echo "No stack PIDs found"
  exit 0
fi

signal_pids TERM "${mapfile_pids[@]}"

for _ in 1 2 3; do
  sleep 1
  mapfile_pids=()
  while IFS= read -r pid; do
    [[ -n "${pid}" ]] && mapfile_pids+=("${pid}")
  done < <(collect_pids)

  if [[ "${#mapfile_pids[@]}" -eq 0 ]]; then
    break
  fi

  signal_pids TERM "${mapfile_pids[@]}"
done

if [[ "${#mapfile_pids[@]}" -gt 0 ]]; then
  signal_pids KILL "${mapfile_pids[@]}"
  sleep 1
fi

mapfile_pids=()
while IFS= read -r pid; do
  [[ -n "${pid}" ]] && mapfile_pids+=("${pid}")
done < <(collect_pids)

: > "${PID_FILE}"

if [[ "${#mapfile_pids[@]}" -gt 0 ]]; then
  echo "Some stack processes are still alive: ${mapfile_pids[*]}" >&2
  exit 1
fi

echo "Stop signal sent to stack PIDs from ${PID_FILE}"
