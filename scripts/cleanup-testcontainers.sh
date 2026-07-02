#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: scripts/cleanup-testcontainers.sh [--prune]

Audits Docker resources created by Testcontainers. With --prune, removes only
resources carrying Testcontainers labels. It does not touch application compose
resources or unlabeled Docker objects.
USAGE
}

prune=false
if [[ "${1:-}" == "--prune" ]]; then
  prune=true
elif [[ "$#" -gt 0 ]]; then
  usage >&2
  exit 64
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "docker is not installed; skipping Testcontainers cleanup audit"
  exit 0
fi

if ! docker info >/dev/null 2>&1; then
  echo "docker daemon is unavailable; skipping Testcontainers cleanup audit"
  exit 0
fi

list_containers() {
  docker ps -a \
    --filter "label=org.testcontainers=true" \
    --format '{{.ID}} {{.Image}} {{.Status}} {{.Names}}'
}

list_networks() {
  docker network ls \
    --filter "label=org.testcontainers=true" \
    --format '{{.ID}} {{.Name}}'
}

list_volumes() {
  docker volume ls \
    --filter "label=org.testcontainers=true" \
    --format '{{.Name}}'
}

echo "=== Testcontainers Docker resource audit ==="
echo "--- containers ---"
containers="$(list_containers)"
printf '%s\n' "${containers:-none}"
echo "--- networks ---"
networks="$(list_networks)"
printf '%s\n' "${networks:-none}"
echo "--- volumes ---"
volumes="$(list_volumes)"
printf '%s\n' "${volumes:-none}"

if [[ "$prune" != true ]]; then
  exit 0
fi

mapfile -t container_ids < <(docker ps -a --filter "label=org.testcontainers=true" -q)
if [[ "${#container_ids[@]}" -gt 0 ]]; then
  docker rm -f "${container_ids[@]}"
fi

mapfile -t network_ids < <(docker network ls --filter "label=org.testcontainers=true" -q)
for network_id in "${network_ids[@]}"; do
  docker network rm "$network_id" >/dev/null 2>&1 || true
done

mapfile -t volume_names < <(docker volume ls --filter "label=org.testcontainers=true" -q)
if [[ "${#volume_names[@]}" -gt 0 ]]; then
  docker volume rm -f "${volume_names[@]}" >/dev/null 2>&1 || true
fi

echo "=== Testcontainers cleanup complete ==="
echo "--- remaining containers ---"
remaining="$(list_containers)"
printf '%s\n' "${remaining:-none}"
