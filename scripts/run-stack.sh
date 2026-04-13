#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${ROOT_DIR}/out/stack"
LOG_DIR="${ROOT_DIR}/out/stack-logs"
PID_FILE="${LOG_DIR}/pids.txt"

mkdir -p "${OUT_DIR}" "${LOG_DIR}"
: > "${PID_FILE}"

if [[ -n "${JAVA8_HOME:-}" ]]; then
  JAVA_CMD="${JAVA8_HOME}/bin/java"
  JAVAC_CMD="${JAVA8_HOME}/bin/javac"
elif [[ -n "${JAVA_HOME:-}" ]]; then
  JAVA_CMD="${JAVA_HOME}/bin/java"
  JAVAC_CMD="${JAVA_HOME}/bin/javac"
else
  JAVA_CMD="java"
  JAVAC_CMD="javac"
fi

JAVA_VERSION="$("${JAVA_CMD}" -version 2>&1 | head -n 1)"
if [[ "${JAVA_VERSION}" != *"1.8"* && "${JAVA_VERSION}" != *" 8."* && "${JAVA_VERSION}" != *" version \"8"* ]]; then
  echo "This stack expects a Java 8 runtime with CORBA support." >&2
  echo "Current java version: ${JAVA_VERSION}" >&2
  echo "Set JAVA8_HOME or JAVA_HOME to a JDK 8 installation and retry." >&2
  exit 1
fi

compile_sources() {
  local sources=()

  while IFS= read -r file; do
    sources+=("${file}")
  done < <(find "${ROOT_DIR}/src/main/java/com/dvrms/common" -name "*.java" | sort)

  while IFS= read -r file; do
    sources+=("${file}")
  done < <(find "${ROOT_DIR}/src/main/java/com/dvrms/frontend" -name "*.java" | sort)

  while IFS= read -r file; do
    sources+=("${file}")
  done < <(find "${ROOT_DIR}/src/main/java/com/dvrms/sequencer" -name "*.java" | sort)

  while IFS= read -r file; do
    sources+=("${file}")
  done < <(find "${ROOT_DIR}/src/main/java/com/dvrms/replica_manager" -name "*.java" | sort)

  while IFS= read -r file; do
    sources+=("${file}")
  done < <(find "${ROOT_DIR}/src/main/java/com/dvrms/replica1" -name "*.java" | sort)

  while IFS= read -r file; do
    sources+=("${file}")
  done < <(find "${ROOT_DIR}/src/main/java/com/dvrms/replica2" -name "*.java" | sort)

  while IFS= read -r file; do
    sources+=("${file}")
  done < <(find "${ROOT_DIR}/src/main/java/com/dvrms/replica3" -name "*.java" | sort)

  while IFS= read -r file; do
    sources+=("${file}")
  done < <(find "${ROOT_DIR}/src/main/java/com/dvrms/replica4" -name "*.java" | sort)

  "${JAVAC_CMD}" -d "${OUT_DIR}" "${sources[@]}"
}

start_process() {
  local name="$1"
  shift
  local log_file="${LOG_DIR}/${name}.log"
  (
    cd "${ROOT_DIR}"
    exec "$@"
  ) >"${log_file}" 2>&1 &
  local pid=$!
  echo "${pid} ${name}" >> "${PID_FILE}"
  echo "started ${name} (pid=${pid})"
}

compile_sources

start_process "orbd" "${JAVA_CMD}" -cp "${OUT_DIR}" com.sun.corba.se.impl.naming.cosnaming.TransientNameServer -ORBInitialPort 1050
sleep 2

start_process "sequencer" "${JAVA_CMD}" -cp "${OUT_DIR}" com.dvrms.sequencer.Sequencer

start_process "rm1" "${JAVA_CMD}" -cp "${OUT_DIR}" com.dvrms.replica_manager.ReplicaManager 1 2 localhost 7002 3 localhost 7003 4 localhost 7004
start_process "rm2" "${JAVA_CMD}" -cp "${OUT_DIR}" com.dvrms.replica_manager.ReplicaManager 2 1 localhost 7001 3 localhost 7003 4 localhost 7004
start_process "rm3" "${JAVA_CMD}" -cp "${OUT_DIR}" com.dvrms.replica_manager.ReplicaManager 3 1 localhost 7001 2 localhost 7002 4 localhost 7004
start_process "rm4" "${JAVA_CMD}" -cp "${OUT_DIR}" com.dvrms.replica_manager.ReplicaManager 4 1 localhost 7001 2 localhost 7002 3 localhost 7003

start_process "replica1" "${JAVA_CMD}" -cp "${OUT_DIR}" com.dvrms.replica1.Replica1Server

for city in MTL WPG BNF; do
  start_process "replica2_${city}" \
    "${JAVA_CMD}" -cp "${OUT_DIR}" com.dvrms.replica2.VehicleServer "${city}" "2" localhost "7002"
done

start_process "replica3" "${JAVA_CMD}" -cp "${OUT_DIR}" com.dvrms.replica3.Replica3Server
start_process "replica4" "${JAVA_CMD}" -cp "${OUT_DIR}" com.dvrms.replica4.Replica4Server

sleep 3
start_process "frontend" "${JAVA_CMD}" -Dorb.host=localhost -Dorb.port=1050 -cp "${OUT_DIR}" com.dvrms.frontend.CORBAServer
sleep 2

cat <<EOF

Stack started.

Logs:
  ${LOG_DIR}

PID file:
  ${PID_FILE}

Frontend client examples:
  ${JAVA_CMD} -cp "${OUT_DIR}" com.dvrms.frontend.FrontEndClient listAvailableVehicles MTLM1111
  ${JAVA_CMD} -cp "${OUT_DIR}" com.dvrms.frontend.FrontEndClient addVehicle MTLM1111 AAA111 Sedan MTL1001 120
  ${JAVA_CMD} -cp "${OUT_DIR}" com.dvrms.frontend.FrontEndClient reserveVehicle MTLU1111 MTL1001 2026-04-20 2026-04-22

Interactive UI:
  ${JAVA_CMD} -cp "${OUT_DIR}" com.dvrms.frontend.FrontEndCLI

Stop the stack:
  ./scripts/stop-stack.sh
EOF
