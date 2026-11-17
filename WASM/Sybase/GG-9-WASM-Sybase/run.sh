#!/usr/bin/env bash
set -euo pipefail
example_dir=$(cd "$(dirname "$0")" && pwd)
gg_source=${GG_SOURCE:-/Users/oscar/Projects/MariaDB/gridgain-9}
python_bin=${PYTHON:-"$example_dir/../.venv/bin/python"}
if [[ -z "${JAVA_HOME:-}" && "$(uname -s)" == Darwin ]]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 21)
    export JAVA_HOME
fi
"$python_bin" "$example_dir/prepare.py"
exec "$gg_source/gradlew" -p "$gg_source" -I "$example_dir/source-build.gradle" \
    -Dsybase.example.dir="$example_dir" :sybase-wasm-example:run --console=plain
