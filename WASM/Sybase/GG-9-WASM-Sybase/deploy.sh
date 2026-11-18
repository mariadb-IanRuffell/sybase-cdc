#!/usr/bin/env bash
# Build the job jar (Maven, against the released GridGain 9 artifacts) and deploy it to a running GridGain 9 cluster
# as the deployment unit "sybase-wasm-example". Every deploy is a new version (1.0.<epoch>); callers use "latest".
#   ./deploy.sh        REST port of node1 is localhost:11300 (override with GG_REST)
# Regenerate the WASM first if a procedure changed:  ../.venv/bin/python prepare.py
set -euo pipefail
cd "$(dirname "$0")"
REST="${GG_REST:-http://localhost:11300}/management/v1/deployment"
UNIT=sybase-wasm-example
mvn -q -DskipTests package
VER="1.0.$(date +%s)"
echo -n "deploying $UNIT $VER "
out=$(mktemp)
code=$(curl -s -o "$out" -w '%{http_code}' -X POST "$REST/units/$UNIT/$VER?deployMode=ALL" -H 'Content-Type: multipart/form-data' -F "unitContent=@target/sybase-wasm-jobs.jar")
[ "$code" = 200 ] || { echo "FAILED ($code): $(cat "$out")"; exit 1; }
for _ in $(seq 1 60); do
  st=$(curl -s "$REST/cluster/units/$UNIT" | python3 -c "import json,sys;d=json.load(sys.stdin);print([v['status'] for u in d for v in u['versionToStatus'] if v['version']=='$VER'][0])" 2>/dev/null || true)
  [ "$st" = DEPLOYED ] && break
  echo -n .; sleep 1
done
[ "${st:-}" = DEPLOYED ] || { echo " not deployed (status ${st:-unknown})"; exit 1; }
echo " deployed"
for old in $(curl -s "$REST/cluster/units/$UNIT" | python3 -c "import json,sys;[print(v['version']) for u in json.load(sys.stdin) for v in u['versionToStatus'] if v['version']!='$VER']" 2>/dev/null); do
  curl -s -o /dev/null -X DELETE "$REST/units/$UNIT/$old" && echo "removed $UNIT $old"
done
