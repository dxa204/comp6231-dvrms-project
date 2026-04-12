#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${ROOT_DIR}/out/sequencer-test"

mkdir -p "${OUT_DIR}"

javac --release 8 \
  -d "${OUT_DIR}" \
  -cp "${ROOT_DIR}/src/main/java:${ROOT_DIR}/src/test/java" \
  "${ROOT_DIR}/src/main/java/com/dvrms/common/Config.java" \
  "${ROOT_DIR}/src/main/java/com/dvrms/sequencer/RMNotifier.java" \
  "${ROOT_DIR}/src/main/java/com/dvrms/sequencer/ReliableUDPSender.java" \
  "${ROOT_DIR}/src/main/java/com/dvrms/sequencer/ReplicaTarget.java" \
  "${ROOT_DIR}/src/main/java/com/dvrms/sequencer/Sequencer.java" \
  "${ROOT_DIR}/src/test/java/com/dvrms/sequencer/SequencerTest.java"

java -cp "${OUT_DIR}" com.dvrms.sequencer.SequencerTest
