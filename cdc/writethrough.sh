#!/usr/bin/env bash
# GridGain -> Sybase write-through (reverse CDC).   Usage: ./writethrough.sh run | once | status | verify
# Env: ASE_URL, ASE_USER, ASE_PASSWORD, GG_URL, WT_POLL_MS (default 2000), WT_ALLOW_MASS_DELETE (default false)
set -euo pipefail
cd "$(dirname "$0")"
[ -d target/lib ] || mvn -q -DskipTests package
exec java @jvm.args -cp "target/classes:target/lib/*" cdc.WriteThroughMain "$@"
