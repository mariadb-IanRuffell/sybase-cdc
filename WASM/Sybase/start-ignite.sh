#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

release=apache-ignite-2.18.0-bin
if [[ ! -x ".ignite/$release/bin/ignite.sh" ]]; then
    mkdir -p .ignite
    (
        cd .ignite
        staging=$(mktemp -d install.XXXXXX)
        trap 'rm -rf "$staging"' EXIT
        curl -fLsS --retry 2 "https://archive.apache.org/dist/ignite/2.18.0/$release.zip" -o "$staging/ignite.zip"
        sha=f1eba2c899e3aa4a3f50f9db9da1342ce6a0009de8e1fb2c9e751cb84346a764fe2385e2753eac1a90e500ff1a7fd531ea1b0251072ba7b57373b63b829a68bd
        echo "$sha  $staging/ignite.zip" | shasum -a 512 -c -
        unzip -q "$staging/ignite.zip" -d "$staging"
        mv "$staging/$release" .
    )
fi

if [[ -z "${JAVA_HOME:-}" && "$(uname -s)" == Darwin ]]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 17)
    export JAVA_HOME
fi
export JVM_OPTS="${JVM_OPTS:--Xms128m -Xmx512m -XX:ActiveProcessorCount=4}"
exec ".ignite/$release/bin/ignite.sh" "$PWD/ignite-local.xml"
