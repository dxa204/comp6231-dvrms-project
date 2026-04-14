#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${ROOT_DIR}/out/rm-recovery-test"

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

mkdir -p "${OUT_DIR}"

sources=()
while IFS= read -r file; do
  sources+=("${file}")
done < <(find "${ROOT_DIR}/src/main/java/com/dvrms/common" -name "*.java" | sort)

while IFS= read -r file; do
  sources+=("${file}")
done < <(find "${ROOT_DIR}/src/main/java/com/dvrms/replica_manager" -name "*.java" | sort)

while IFS= read -r file; do
  sources+=("${file}")
done < <(find "${ROOT_DIR}/src/test/java/com/dvrms/replica_manager" -name "*.java" | sort)

"${JAVAC_CMD}" -d "${OUT_DIR}" "${sources[@]}"
"${JAVA_CMD}" -cp "${OUT_DIR}" com.dvrms.replica_manager.ReplicaManagerRecoveryTest
