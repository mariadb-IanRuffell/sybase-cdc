#!/usr/bin/env bash
# Copy the Chicory WASM runtime (version from the sybase-wasm-example pom) into ext-lib/, where docker-compose.yml mounts it into
# every node's lib/. Jars on the node classpath are loaded once and stay warm; jars bundled in a deployment unit are loaded cold
# on every compute job (about 14 ms for Chicory, about 58 ms for Jackson).
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p ext-lib
mvn -q -f ../WASM/Sybase/GG-9-WASM-Sybase/pom.xml dependency:copy-dependencies \
    -DincludeArtifactIds=runtime,wasm -DincludeGroupIds=com.dylibso.chicory -DoutputDirectory="$PWD/ext-lib"
cd ext-lib
for f in runtime wasm; do for j in "$f"-*.jar; do mv -f "$j" "chicory-$j"; done; done
ls -1
