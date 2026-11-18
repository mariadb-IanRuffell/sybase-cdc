#!/usr/bin/env bash
set -euo pipefail
export SYBASE_CORPUS=true
export SYBASE_CUSTOMER360=true
# Large result/text traces contain complete payloads; enable explicitly when needed.
export SYBASE_TRACE=${SYBASE_TRACE:-false}
exec "$(cd "$(dirname "$0")" && pwd)/run.sh" "$@"
