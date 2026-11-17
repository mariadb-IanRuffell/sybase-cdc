#!/usr/bin/env bash
# GridGain 9 3-node persistent cluster helper.
#   ./cluster.sh up       start all nodes, initialise the cluster once (license from gridgain-license.json), wait until it is ready
#   ./cluster.sh status   cluster state and topology
#   ./cluster.sh down     stop the nodes, KEEP the data volumes
#   ./cluster.sh wipe     stop the nodes and DELETE the data volumes (fresh, empty cluster)
# GG_VERSION selects the image (default in docker-compose.yml); GG_LICENSE_FILE overrides the license path.
set -euo pipefail
cd "$(dirname "$0")"
LICENSE_FILE="${GG_LICENSE_FILE:-./gridgain-license.json}"
REST="http://localhost:11300/management/v1"

case "${1:-status}" in
  up)
    docker compose up -d
    echo -n "waiting for the nodes"
    for p in 11300 11301 11302; do until curl -sf "http://localhost:$p/management/v1/node/state" >/dev/null 2>&1; do echo -n .; sleep 2; done; done; echo
    if curl -sf "$REST/cluster/state" >/dev/null 2>&1; then
      echo "cluster already initialised"
    else
      BODY='{"metaStorageNodes":["node1","node2","node3"],"cmgNodes":["node1","node2","node3"],"clusterName":"customer360"'
      if [ -f "$LICENSE_FILE" ]; then
        BODY="$BODY,\"license\":$(python3 -c 'import json,sys;print(json.dumps(open(sys.argv[1]).read()))' "$LICENSE_FILE")"
      else
        echo "no license at $LICENSE_FILE: initialising without one"
      fi
      curl -sf -X POST "$REST/cluster/init" -H 'Content-Type: application/json' -d "$BODY}" && echo "init sent"
      until curl -sf "$REST/cluster/state" >/dev/null 2>&1; do sleep 2; done
    fi
    for p in 11300 11301 11302; do until curl -s "http://localhost:$p/management/v1/node/state" | grep -q STARTED; do sleep 2; done; done
    echo "cluster ready: $(curl -s $REST/cluster/state | python3 -c 'import json,sys;d=json.load(sys.stdin);print(d["clusterTag"]["clusterName"], d["cmgNodes"], d["msNodes"])' 2>/dev/null)"
    ;;
  status)
    curl -s "$REST/cluster/state" | python3 -m json.tool 2>/dev/null | head -30 || echo "not initialised"
    for p in 11300 11301 11302; do echo "node on :$p $(curl -s http://localhost:$p/management/v1/node/state)"; done
    ;;
  down) docker compose down ;;
  wipe) docker compose down -v ;;
  *) echo "usage: $0 up|status|down|wipe"; exit 2 ;;
esac
