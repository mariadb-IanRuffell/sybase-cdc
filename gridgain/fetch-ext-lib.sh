#!/usr/bin/env bash
# Build the Sybase WASM job jar and copy it, and the Chicory WASM runtime, (version from the sybase-wasm-example pom) into ext-lib/, where docker-compose.yml mounts it into
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
# The job classes themselves go on the node classpath too, so they and the ABI/module caches they hold stay warm between calls
# (a deployment unit's classes are reloaded for every job). Rerun this and restart the nodes after changing a procedure or the host.
cd ..
mvn -q -DskipTests -f ../WASM/Sybase/GG-9-WASM-Sybase/pom.xml package
cp ../WASM/Sybase/GG-9-WASM-Sybase/target/sybase-wasm-jobs.jar ext-lib/sybase-wasm-jobs.jar
ls -1 ext-lib
