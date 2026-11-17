#!/usr/bin/env bash
# Regenerate openapi.yaml from the RUNNING service (the spec is generated from the controllers and DTOs by
# springdoc, so it cannot drift from the code). Swagger UI: http://localhost:8081/swagger-ui.html
set -euo pipefail
cd "$(dirname "$0")"
curl -sf http://localhost:${API_PORT:-8081}/v3/api-docs.yaml -o openapi.yaml
echo "wrote openapi.yaml: $(grep -c '^  /api' openapi.yaml) paths, $(grep -cE '^    [A-Z][A-Za-z]+:$' openapi.yaml) schemas"
