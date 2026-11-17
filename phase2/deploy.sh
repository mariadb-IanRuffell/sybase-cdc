#!/usr/bin/env bash
# Build the job jar and deploy it to the GridGain 9 cluster as the deployment unit "customer360-jobs".
# Every deploy is a new version (1.0.<epoch>); callers use "latest", so nothing else changes. Older versions are removed afterwards.
#   ./deploy.sh            REST port of node1 is localhost:11300 (override with GG_REST)
set -euo pipefail
cd "$(dirname "$0")"
REST="${GG_REST:-http://localhost:11300}/management/v1/deployment"
UNIT=customer360-jobs
JAR="${1:-target/gg-compute.jar}"
[ -n "${1:-}" ] || mvn -q -DskipTests package
VER="1.0.$(date +%s)"

echo -n "deploying $UNIT $VER "
code=$(curl -s -o /tmp/deploy.out -w '%{http_code}' -X POST "$REST/units/$UNIT/$VER?deployMode=ALL" -H 'Content-Type: multipart/form-data' -F "unitContent=@$JAR")
[ "$code" = 200 ] || { echo "FAILED ($code): $(cat /tmp/deploy.out)"; exit 1; }
for _ in $(seq 1 60); do
  st=$(curl -s "$REST/cluster/units/$UNIT" | python3 -c "import json,sys;d=json.load(sys.stdin);print([v['status'] for u in d for v in u['versionToStatus'] if v['version']=='$VER'][0])" 2>/dev/null || true)
  [ "$st" = DEPLOYED ] && break
  echo -n .; sleep 1
done
[ "${st:-}" = DEPLOYED ] || { echo " not deployed (status ${st:-unknown})"; exit 1; }
echo " deployed"
# remove older versions
for old in $(curl -s "$REST/cluster/units/$UNIT" | python3 -c "import json,sys;[print(v['version']) for u in json.load(sys.stdin) for v in u['versionToStatus'] if v['version']!='$VER']" 2>/dev/null); do
  curl -s -o /dev/null -X DELETE "$REST/units/$UNIT/$old" && echo "removed $UNIT $old"
done
