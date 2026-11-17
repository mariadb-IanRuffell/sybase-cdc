#!/usr/bin/env bash
# Build the jar and (re)deploy the API container.   ./build.sh
set -euo pipefail
cd "$(dirname "$0")"
mvn -q -DskipTests package
docker compose up -d --build
echo -n "waiting for the API"
for _ in $(seq 1 60); do curl -sf localhost:${API_PORT:-8081}/actuator/health >/dev/null 2>&1 && break; echo -n .; sleep 2; done; echo
curl -s localhost:${API_PORT:-8081}/actuator/health; echo
