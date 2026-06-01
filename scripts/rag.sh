#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NETWORK="${DISCORD_AI_EDGE_NETWORK:-discord-ai-edge}"
IMAGE="${DISCORD_AI_RAG_IMAGE:-discord-ai-rag:local}"
QDRANT_URL="${QDRANT_URL:-http://discord-ai-qdrant:6333}"

case "${1:-build-local}" in
  build-local|build|rebuild)
    cd "$ROOT"
    python3 rag/build_index.py "${@:2}"
    ;;
  build-vector|rebuild-vector)
    cd "$ROOT"
    python3 rag/build_index.py --with-vector "${@:2}"
    ;;
  docker-build)
    docker build -f "$ROOT/docker/rag/Dockerfile" -t "$IMAGE" "$ROOT"
    ;;
  docker-index|docker-rebuild)
    docker network inspect "$NETWORK" >/dev/null 2>&1 || docker network create "$NETWORK"
    docker build -f "$ROOT/docker/rag/Dockerfile" -t "$IMAGE" "$ROOT"
    docker run --rm --network "$NETWORK" \
      --env-file "$ROOT/.env" \
      -e QDRANT_URL="$QDRANT_URL" \
      -v "$ROOT:/app" \
      "$IMAGE" --with-vector "${@:2}"
    ;;
  search)
    cd "$ROOT"
    python3 rag/search.py "${@:2}"
    ;;
  *)
    echo "Usage: scripts/rag.sh {rebuild|build-local|build-vector|docker-build|docker-rebuild|search} [--guild ID --space ID --collection NAME --embedding-model MODEL --input PATH]" >&2
    exit 2
    ;;
esac
