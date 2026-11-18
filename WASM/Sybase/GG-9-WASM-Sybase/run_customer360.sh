#!/usr/bin/env bash
set -euo pipefail
export SYBASE_CUSTOMER360=true
exec "$(cd "$(dirname "$0")" && pwd)/run.sh" "$@"
