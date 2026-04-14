#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${ROOT_DIR}/out/stack"
ROLE="${1:-}"

if [[ -z "${ROLE}" ]]; then
  echo "Usage: $0 <laptop-a|laptop-b|laptop-c|laptop-d>" >&2
  exit 1
fi

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

required_vars=(DVRMS_FE_HOST DVRMS_RM1_HOST DVRMS_RM2_HOST DVRMS_RM3_HOST DVRMS_RM4_HOST)
for var_name in "${required_vars[@]}"; do
  if [[ -z "${!var_name:-}" ]]; then
    echo "Missing required environment variable: ${var_name}" >&2
    exit 1
  fi
done

SEQ_HOST="${DVRMS_SEQUENCER_HOST:-${DVRMS_FE_HOST}}"
R1_HOST="${DVRMS_R1_HOST:-${DVRMS_RM1_HOST}}"
R2_HOST="${DVRMS_R2_HOST:-${DVRMS_RM2_HOST}}"
R3_HOST="${DVRMS_R3_HOST:-${DVRMS_RM3_HOST}}"
R4_HOST="${DVRMS_R4_HOST:-${DVRMS_RM4_HOST}}"

LOG_DIR="${ROOT_DIR}/out/distributed-logs/${ROLE}"
PID_FILE="${LOG_DIR}/pids.txt"
mkdir -p "${OUT_DIR}" "${LOG_DIR}"
: > "${PID_FILE}"

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

COMMON_PROPS=(
  "-Ddvrms.fe.host=${DVRMS_FE_HOST}"
  "-Ddvrms.sequencer.host=${SEQ_HOST}"
  "-Ddvrms.rm1.host=${DVRMS_RM1_HOST}"
  "-Ddvrms.rm2.host=${DVRMS_RM2_HOST}"
  "-Ddvrms.rm3.host=${DVRMS_RM3_HOST}"
  "-Ddvrms.rm4.host=${DVRMS_RM4_HOST}"
  "-Ddvrms.replica1.host=${R1_HOST}"
  "-Ddvrms.replica2.host=${R2_HOST}"
  "-Ddvrms.replica3.host=${R3_HOST}"
  "-Ddvrms.replica4.host=${R4_HOST}"
)

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

case "${ROLE}" in
  laptop-a)
    start_process "orbd" "${JAVA_CMD}" -cp "${OUT_DIR}" com.sun.corba.se.impl.naming.cosnaming.TransientNameServer -ORBInitialPort 1050
    sleep 2
    start_process "sequencer" "${JAVA_CMD}" "${COMMON_PROPS[@]}" -cp "${OUT_DIR}" com.dvrms.sequencer.Sequencer
    start_process "rm4" "${JAVA_CMD}" "${COMMON_PROPS[@]}" -Dsequencer.host="${SEQ_HOST}" -Dorb.host="${DVRMS_FE_HOST}" -Drm.host="${DVRMS_RM4_HOST}" -cp "${OUT_DIR}" com.dvrms.replica_manager.ReplicaManager 4 1 "${DVRMS_RM1_HOST}" 7001 2 "${DVRMS_RM2_HOST}" 7002 3 "${DVRMS_RM3_HOST}" 7003
    start_process "replica4" "${JAVA_CMD}" "${COMMON_PROPS[@]}" -cp "${OUT_DIR}" com.dvrms.replica4.Replica4Server
    sleep 2
    start_process "frontend" "${JAVA_CMD}" "${COMMON_PROPS[@]}" -Dorb.host="${DVRMS_FE_HOST}" -Dorb.port=1050 -cp "${OUT_DIR}" com.dvrms.frontend.CORBAServer
    ;;
  laptop-b)
    start_process "rm1" "${JAVA_CMD}" "${COMMON_PROPS[@]}" -Dsequencer.host="${SEQ_HOST}" -Dorb.host="${DVRMS_FE_HOST}" -Drm.host="${DVRMS_RM1_HOST}" -cp "${OUT_DIR}" com.dvrms.replica_manager.ReplicaManager 1 2 "${DVRMS_RM2_HOST}" 7002 3 "${DVRMS_RM3_HOST}" 7003 4 "${DVRMS_RM4_HOST}" 7004
    sleep 1
    start_process "replica1" "${JAVA_CMD}" "${COMMON_PROPS[@]}" -cp "${OUT_DIR}" com.dvrms.replica1.Replica1Server
    ;;
  laptop-c)
    start_process "rm2" "${JAVA_CMD}" "${COMMON_PROPS[@]}" -Dsequencer.host="${SEQ_HOST}" -Dorb.host="${DVRMS_FE_HOST}" -Drm.host="${DVRMS_RM2_HOST}" -cp "${OUT_DIR}" com.dvrms.replica_manager.ReplicaManager 2 1 "${DVRMS_RM1_HOST}" 7001 3 "${DVRMS_RM3_HOST}" 7003 4 "${DVRMS_RM4_HOST}" 7004
    sleep 1
    for city in MTL WPG BNF; do
      start_process "replica2_${city}" "${JAVA_CMD}" "${COMMON_PROPS[@]}" -Dorb.host="${DVRMS_FE_HOST}" -cp "${OUT_DIR}" com.dvrms.replica2.VehicleServer "${city}" "2" "${DVRMS_RM2_HOST}" "7002"
    done
    ;;
  laptop-d)
    start_process "rm3" "${JAVA_CMD}" "${COMMON_PROPS[@]}" -Dsequencer.host="${SEQ_HOST}" -Dorb.host="${DVRMS_FE_HOST}" -Drm.host="${DVRMS_RM3_HOST}" -cp "${OUT_DIR}" com.dvrms.replica_manager.ReplicaManager 3 1 "${DVRMS_RM1_HOST}" 7001 2 "${DVRMS_RM2_HOST}" 7002 4 "${DVRMS_RM4_HOST}" 7004
    sleep 1
    start_process "replica3" "${JAVA_CMD}" "${COMMON_PROPS[@]}" -cp "${OUT_DIR}" com.dvrms.replica3.Replica3Server
    ;;
  *)
    echo "Unknown role: ${ROLE}" >&2
    echo "Usage: $0 <laptop-a|laptop-b|laptop-c|laptop-d>" >&2
    exit 1
    ;;
esac

cat <<EOF

Started ${ROLE}.

Logs:
  ${LOG_DIR}

PID file:
  ${PID_FILE}

CLI command:
  ${JAVA_CMD} -Dorb.host=${DVRMS_FE_HOST} -Dorb.port=1050 -cp ${OUT_DIR} com.dvrms.frontend.FrontEndCLI
EOF
