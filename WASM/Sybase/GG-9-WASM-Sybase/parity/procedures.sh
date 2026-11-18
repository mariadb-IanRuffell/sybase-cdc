#!/usr/bin/env bash
# The other five Customer 360 procedures: the real Sybase procedures on ASE vs their WASM compilations on GridGain 9.
# Needs the stack from the repo README (ASE on llama, GridGain cluster, CDC reader) and the unit deployed (../deploy.sh).
# It stops the CDC reader, creates two open orders in Sybase, resyncs GridGain (cdc init), runs the cases, resyncs again
# and restarts the reader.
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(cd ../../../.. && pwd)"
CP="$ROOT/phase2/target/lib/*:$(cd .. && pwd)/target/sybase-wasm-jobs.jar"   # the unit jar carries Jackson
[ -d "$ROOT/phase2/target/lib" ] || (cd "$ROOT/phase2" && mvn -q -DskipTests package)
run() { java $(grep -v '^#' "$ROOT/phase2/jvm.args" | tr '\n' ' ') -cp "$CP" "$@" 2>&1 | grep -v -E 'IgniteLoggerImpl|^INFO'; }
docker stop gg-cdc >/dev/null
trap 'docker start gg-cdc >/dev/null' EXIT
read -r W E <<< "$(run ProcedureParity.java prepare | tail -1)"
(cd "$ROOT/cdc" && ./run.sh init >/dev/null 2>&1)
set +e
run ProcedureParity.java compare "$W" "$E"
rc=${PIPESTATUS[0]}
set -e
(cd "$ROOT/cdc" && ./run.sh init >/dev/null 2>&1)
exit $rc
