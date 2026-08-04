#!/usr/bin/env bash
# Private Speech-style RAG를 별도 일회성 컨테이너로 적재한다.
# 이 스크립트는 production Environment secret이 주입된 CI job에서만 실행한다.
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-compose.yml}"
APP_SERVICE="${APP_SERVICE:-central-server}"
DB_SERVICE="${DB_SERVICE:-db}"
IMPORT_ARTIFACT="${IMPORT_ARTIFACT:-}"

fail() {
  echo "❌ $1" >&2
  exit 1
}

require_env() {
  local name="$1"
  [ -n "${!name:-}" ] || fail "required production secret is unavailable: $name"
}

require_private_permissions() {
  local path="$1"
  local type="$2"
  local mode
  case "$type" in
    directory) [ -d "$path" ] || fail "private directory is unavailable" ;;
    file) [ -f "$path" ] || fail "private artifact is unavailable" ;;
    *) fail "internal permission target type is invalid" ;;
  esac
  mode="$(stat -c '%a' "$path")"
  [ $((8#$mode & 077)) -eq 0 ] || fail "private $type permissions are too broad"
}

[ -f "$COMPOSE_FILE" ] || fail "compose file is unavailable"
[ -n "$IMPORT_ARTIFACT" ] || fail "IMPORT_ARTIFACT is required"
IMPORT_ARTIFACT="$(realpath -e "$IMPORT_ARTIFACT")"
IMPORT_DIRECTORY="$(dirname "$IMPORT_ARTIFACT")"
IMPORT_MANIFEST="$IMPORT_DIRECTORY/manifest.json"

require_private_permissions "$IMPORT_DIRECTORY" directory
require_private_permissions "$IMPORT_ARTIFACT" file
require_private_permissions "$IMPORT_MANIFEST" file

for required_secret in \
  CENTRAL_DB_PASSWORD DISCORD_BOT_TOKEN DISCORD_ENABLED RELAY_PUBLIC_URL CENTRAL_DURABLE_SECRET \
  NEXA_FIELD_ENC_KEY OPENAI_API_KEY CONNECT_DISCORD_CLIENT_ID CONNECT_DISCORD_CLIENT_SECRET \
  CENTRAL_OAUTH_ENABLED CENTRAL_DASHBOARD_ADMIN_USER_IDS CENTRAL_METRICS_SCRAPE_TOKEN; do
  require_env "$required_secret"
done

artifact_metadata="$(python3 - "$IMPORT_ARTIFACT" "$IMPORT_MANIFEST" <<'PY'
import hashlib
import json
import re
import sys
from pathlib import Path

artifact = Path(sys.argv[1])
manifest_path = Path(sys.argv[2])
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
records = [json.loads(line) for line in artifact.read_text(encoding="utf-8").splitlines() if line.strip()]
expected_modes = {"REACTION", "ALIGNMENT", "PLAY", "FOLLOW_UP", "SPECULATION", "CARE", "COORDINATION"}

if manifest.get("schema") != "nia-human-speech-style-import-manifest.v1":
    raise SystemExit("unsupported private import manifest")
if manifest.get("record_count") != len(records) or len(records) == 0:
    raise SystemExit("private import record count mismatch")
if manifest.get("source_count") != len({record.get("source_fingerprint") for record in records}):
    raise SystemExit("private import source fingerprint count mismatch")
if manifest.get("source_fingerprint_count") != manifest.get("source_count"):
    raise SystemExit("private import source manifest mismatch")
if manifest.get("all_expected_sources_present") is not True:
    raise SystemExit("private import source coverage is incomplete")
if hashlib.sha256(artifact.read_bytes()).hexdigest() != manifest.get("jsonl_sha256"):
    raise SystemExit("private import JSONL digest mismatch")
if len({record.get("example_id") for record in records}) != len(records):
    raise SystemExit("private import example ids are duplicated")
if {record.get("response_mode") for record in records} != expected_modes:
    raise SystemExit("private import response mode coverage is invalid")
for record in records:
    if record.get("schema") != "nia-human-speech-style-import-card.v1":
        raise SystemExit("private import card schema is invalid")
    if not record.get("context_bubbles") or not record.get("response_bubbles"):
        raise SystemExit("private import card bubbles are incomplete")
    if not re.fullmatch(r"human-style-[0-9]{6}", str(record.get("example_id"))):
        raise SystemExit("private import example id is invalid")

print(len(records), manifest["source_count"])
PY
)"
read -r EXPECTED_COUNT EXPECTED_SOURCE_COUNT <<<"$artifact_metadata"

[[ "$EXPECTED_COUNT" =~ ^[1-9][0-9]*$ ]] || fail "private import expected count is invalid"
[[ "$EXPECTED_SOURCE_COUNT" =~ ^[1-9][0-9]*$ ]] || fail "private import expected source count is invalid"
echo "▶ private artifact verified: cards=$EXPECTED_COUNT sources=$EXPECTED_SOURCE_COUNT"

compose=(docker compose --env-file /dev/null -f "$COMPOSE_FILE")
"${compose[@]}" ps --status running "$DB_SERVICE" | grep -q "$DB_SERVICE" || fail "database service is not running"
"${compose[@]}" exec -T "$DB_SERVICE" psql -U central -d central -Atc \
  "SELECT EXISTS (SELECT 1 FROM flyway_schema_history WHERE script = 'V91__nia_human_speech_style_rag.sql' AND success);" \
  | grep -qx 't' || fail "V91 migration is not applied; deploy the supporting image first"

container_name="central-server-style-rag-import-${GITHUB_RUN_ID:-manual}"
echo "▶ one-shot Speech-style RAG import starts (Discord and autonomous send disabled)"
"${compose[@]}" run --rm --no-deps --name "$container_name" \
  -v "$IMPORT_ARTIFACT:/private/human-speech-style-cards.jsonl:ro" \
  -e SPRING_MAIN_WEB_APPLICATION_TYPE=none \
  -e CENTRAL_DISCORD_ENABLED=false \
  -e NEXA_AUTONOMOUS_SEND_ENABLED=false \
  -e CENTRAL_NEXA_PARTICIPATION_GLOBAL_DEFAULT_LANE=OFF \
  -e NIA_WEB_DEMO_ENABLED=false \
  -e NEXA_SPEECH_STYLE_RAG_ENABLED=false \
  -e NEXA_SPEECH_STYLE_RAG_IMPORT_ON_STARTUP=true \
  -e NEXA_SPEECH_STYLE_RAG_IMPORT_EXIT_AFTER_COMPLETION=true \
  -e NEXA_SPEECH_STYLE_RAG_IMPORT_FILE=/private/human-speech-style-cards.jsonl \
  "$APP_SERVICE"

read -r IMPORTED_COUNT ENCRYPTED_PAYLOADS ENCRYPTED_VECTORS SOURCE_COUNT MODE_COUNT <<EOF
$("${compose[@]}" exec -T "$DB_SERVICE" psql -U central -d central -At -F ' ' -c \
  "SELECT count(*), count(*) FILTER (WHERE payload_json LIKE 'enc1:%'), count(*) FILTER (WHERE embedding_json LIKE 'enc1:%'), count(DISTINCT source_fingerprint), count(DISTINCT response_mode) FROM nia_human_speech_style_example;")
EOF

[ "$IMPORTED_COUNT" = "$EXPECTED_COUNT" ] || fail "imported card count mismatch"
[ "$ENCRYPTED_PAYLOADS" = "$EXPECTED_COUNT" ] || fail "plaintext payload rows detected"
[ "$ENCRYPTED_VECTORS" = "$EXPECTED_COUNT" ] || fail "plaintext embedding rows detected"
[ "$SOURCE_COUNT" = "$EXPECTED_SOURCE_COUNT" ] || fail "imported source count mismatch"
[ "$MODE_COUNT" = "7" ] || fail "imported response mode coverage mismatch"

echo "✅ Speech-style RAG import complete: cards=$IMPORTED_COUNT sources=$SOURCE_COUNT encrypted_payloads=$ENCRYPTED_PAYLOADS encrypted_vectors=$ENCRYPTED_VECTORS"
