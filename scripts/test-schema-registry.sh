#!/usr/bin/env bash
# Start an isolated local broker/registry, run the explicit integration project,
# then remove only this invocation's containers, network, and anonymous volumes.
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"
if docker compose version >/dev/null 2>&1; then
  compose=(docker compose)
else
  compose=(docker-compose)
fi
project="avro2s-wire-registry-$$"
compose+=(-p "$project" -f registry-integration/compose.yaml)
cleanup() {
  result=$?
  trap - EXIT
  if [ "$result" -ne 0 ]; then
    "${compose[@]}" logs --tail=100 || true
  fi
  "${compose[@]}" down --volumes --remove-orphans || true
  exit "$result"
}
trap cleanup EXIT
"${compose[@]}" up -d
export AVRO2S_WIRE_REGISTRY_URL="http://localhost:${AVRO2S_WIRE_REGISTRY_PORT:-18081}"
export AVRO2S_WIRE_KAFKA_BOOTSTRAP="localhost:${AVRO2S_WIRE_KAFKA_PORT:-19092}"
ready=false
for attempt in {1..90}; do
  if curl --fail --silent --max-time 2 "$AVRO2S_WIRE_REGISTRY_URL/subjects" >/dev/null; then
    ready=true
    break
  fi
  sleep 2
done
if [ "$ready" != true ]; then
  echo "Schema Registry did not become ready at $AVRO2S_WIRE_REGISTRY_URL" >&2
  exit 1
fi
sbt 'registryIntegration/test'
