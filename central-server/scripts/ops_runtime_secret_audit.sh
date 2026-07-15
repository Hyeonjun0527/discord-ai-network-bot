#!/usr/bin/env bash
# 운영 런타임이 host .env나 Docker metadata의 평문 시크릿 없이 secret file만 사용하는지 확인한다.
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-compose.yml}"
APP_SERVICE="${APP_SERVICE:-central-server}"
DB_SERVICE="${DB_SERVICE:-db}"

fail() {
  echo "❌ $1" >&2
  exit 1
}

check_secret_file() {
  service="$1"
  file_env="$2"
  expected_path="$3"
  docker compose -f "$COMPOSE_FILE" exec -T "$service" sh -c '
    actual_path="$(printenv "$1")"
    [ "$actual_path" = "$2" ] && [ -s "$actual_path" ]
  ' sh "$file_env" "$expected_path" || fail "$service $file_env secret file이 비었거나 경로가 다릅니다"
}

check_raw_secret_absent() {
  service="$1"
  secret_name="$2"
  docker compose -f "$COMPOSE_FILE" exec -T "$service" sh -c '
    [ -z "$(printenv "$1")" ]
  ' sh "$secret_name" || fail "$service Docker 환경에 평문 $secret_name 값이 남아 있습니다"
}

[ -f "$COMPOSE_FILE" ] || fail "compose 파일이 없습니다: $COMPOSE_FILE"
if find . -type f -name '.env*' -print -quit | grep -q .; then
  fail "host .env 계열 파일이 남아 있습니다"
fi
if find . -type f -name '.durable-secret*' -print -quit | grep -q .; then
  fail "legacy .durable-secret 계열 파일이 남아 있습니다"
fi
echo "✅ active_env=absent"

app_secret_files=(
  "DB_PASSWORD_FILE:/run/secrets/spring.datasource.password"
  "DISCORD_ENABLED_FILE:/run/secrets/central.discord.enabled"
  "DISCORD_BOT_TOKEN_FILE:/run/secrets/central.discord.bot-token"
  "RELAY_PUBLIC_URL_FILE:/run/secrets/central.relay.public-url"
  "CENTRAL_DURABLE_SECRET_FILE:/run/secrets/central.durable.secret"
  "NEXA_FIELD_ENC_KEY_FILE:/run/secrets/nexa.field-enc-key"
  "ZAI_API_KEY_FILE:/run/secrets/central.cloud.zai-api-key"
  "CONNECT_DISCORD_CLIENT_ID_FILE:/run/secrets/central.connect.discord-client-id"
  "CONNECT_DISCORD_CLIENT_SECRET_FILE:/run/secrets/central.connect.discord-client-secret"
  "CENTRAL_OAUTH_ENABLED_FILE:/run/secrets/central.oauth.enabled"
  "CENTRAL_DASHBOARD_ADMIN_USER_IDS_FILE:/run/secrets/central.dashboard.admin-user-ids"
)
for spec in "${app_secret_files[@]}"; do
  check_secret_file "$APP_SERVICE" "${spec%%:*}" "${spec#*:}"
done

app_raw_secrets=(
  DB_PASSWORD
  DISCORD_ENABLED
  DISCORD_BOT_TOKEN
  RELAY_PUBLIC_URL
  CENTRAL_DURABLE_SECRET
  NEXA_FIELD_ENC_KEY
  ZAI_API_KEY
  CONNECT_DISCORD_CLIENT_ID
  CONNECT_DISCORD_CLIENT_SECRET
  CENTRAL_OAUTH_ENABLED
  CENTRAL_DASHBOARD_ADMIN_USER_IDS
  STABILITY_API_KEY
  CENTRAL_DASHBOARD_ADMIN_TOKEN
)
for secret_name in "${app_raw_secrets[@]}"; do
  check_raw_secret_absent "$APP_SERVICE" "$secret_name"
done

check_secret_file "$DB_SERVICE" POSTGRES_PASSWORD_FILE /run/secrets/DB_PASSWORD
check_raw_secret_absent "$DB_SERVICE" POSTGRES_PASSWORD

echo "✅ runtime secret files present"
echo "✅ inline secret env absent"
