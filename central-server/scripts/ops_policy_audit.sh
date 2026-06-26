#!/usr/bin/env bash
# 운영 정책 감사: 자동응답/니아 자동 채널과 LLM allow-list 사이의 불일치를 찾는다.
#
# 기본은 읽기 전용이다. 운영 배포 디렉터리(~/deploy/central-server)에서 실행하면 compose DB를 조회한다.
# Discord 채널명 대조는 DISCORD_GUILD_ID가 있을 때만 수행하며, 토큰은 출력하지 않는다.
#
# 사용:
#   cd ~/deploy/central-server
#   DISCORD_GUILD_ID=<guild_id> ./ops_policy_audit.sh
#   DISCORD_GUILD_ID=all ./ops_policy_audit.sh      # 봇이 들어간 모든 서버의 니아 기능 채널 대조
#
# 환경변수:
#   COMPOSE_FILE=compose.yml
#   DB_SERVICE=db
#   APP_SERVICE=central-server
#   PSQL_USER=central
#   PSQL_DB=central
#   DISCORD_GUILD_ID=<guild_id|all>          # 선택: 니아 기능 카테고리의 ai채팅/ai그림까지 Discord API로 대조
#   NIA_CHANNEL_AUDIT_SPEC="<category>=<chat>,<image>;..."
#     기본값은 ko/en/ja 니아 자동 채널명(i18n/messages.json) 전체.
#   NIA_FEATURE_CATEGORY_NAME / NIA_REQUIRED_CHANNEL_NAMES
#     단일 locale만 볼 때 쓰는 legacy override.
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-compose.yml}"
DB_SERVICE="${DB_SERVICE:-db}"
APP_SERVICE="${APP_SERVICE:-central-server}"
PSQL_USER="${PSQL_USER:-central}"
PSQL_DB="${PSQL_DB:-central}"
DISCORD_GUILD_ID="${DISCORD_GUILD_ID:-}"
NIA_FEATURE_CATEGORY_NAME="${NIA_FEATURE_CATEGORY_NAME:-}"
NIA_REQUIRED_CHANNEL_NAMES="${NIA_REQUIRED_CHANNEL_NAMES:-}"
NIA_CHANNEL_AUDIT_SPEC="${NIA_CHANNEL_AUDIT_SPEC:-}"
if [ -z "$NIA_CHANNEL_AUDIT_SPEC" ]; then
  if [ -n "$NIA_FEATURE_CATEGORY_NAME" ] || [ -n "$NIA_REQUIRED_CHANNEL_NAMES" ]; then
    NIA_CHANNEL_AUDIT_SPEC="${NIA_FEATURE_CATEGORY_NAME:-니아 기능 채널}=${NIA_REQUIRED_CHANNEL_NAMES:-🤖｜ai채팅,🎨｜ai그림}"
  else
    NIA_CHANNEL_AUDIT_SPEC="니아 기능 채널=🤖｜ai채팅,🎨｜ai그림;Nia AI Channels=🤖｜ai-chat,🎨｜ai-image;ニア機能チャンネル=🤖｜aiチャット,🎨｜ai画像"
  fi
fi

fail() {
  echo "❌ $1" >&2
  exit 1
}

info() {
  echo "ℹ️  $1"
}

ok() {
  echo "✅ $1"
}

print_rows() {
  while IFS= read -r row; do
    echo "  - $row" >&2
  done
}

need_command() {
  command -v "$1" >/dev/null 2>&1 || fail "$1 명령을 찾을 수 없습니다"
}

run_psql() {
  docker compose -f "$COMPOSE_FILE" exec -T "$DB_SERVICE" \
    psql -U "$PSQL_USER" -d "$PSQL_DB" "$@"
}

need_command docker

if [ ! -f "$COMPOSE_FILE" ]; then
  fail "compose 파일이 없습니다: $COMPOSE_FILE"
fi

info "DB 정책 감사 시작(compose=$COMPOSE_FILE, db=$DB_SERVICE)"

blocked_auto_respond="$(
  run_psql -At -F $'\t' <<'SQL'
WITH restricted_guild AS (
    SELECT guild_id
    FROM allowed_channel
    GROUP BY guild_id
)
SELECT ca.guild_id, ca.channel_id, ca.display_name
FROM channel_ai ca
JOIN restricted_guild rg ON rg.guild_id = ca.guild_id
LEFT JOIN allowed_channel ac
       ON ac.guild_id = ca.guild_id
      AND ac.channel_id = ca.channel_id
WHERE ca.auto_respond = TRUE
  AND ac.channel_id IS NULL
ORDER BY ca.guild_id, ca.channel_id;
SQL
)"

if [ -n "$blocked_auto_respond" ]; then
  print_rows <<<"$blocked_auto_respond"
  fail "auto_respond=true 채널 중 LLM allow-list에 없는 채널이 있습니다"
fi
ok "auto_respond 채널은 LLM allow-list와 충돌하지 않음"

duplicates="$(
  run_psql -At -F $'\t' <<'SQL'
SELECT guild_id, channel_id, COUNT(*)
FROM allowed_channel
GROUP BY guild_id, channel_id
HAVING COUNT(*) > 1
ORDER BY guild_id, channel_id;
SQL
)"

if [ -n "$duplicates" ]; then
  print_rows <<<"$duplicates"
  fail "allowed_channel 중복 행이 있습니다"
fi
ok "allowed_channel 중복 없음"

if [ -z "$DISCORD_GUILD_ID" ]; then
  info "DISCORD_GUILD_ID 미설정: Discord 채널명 대조는 건너뜀"
  ok "정책 감사 통과"
  exit 0
fi
if [ "$DISCORD_GUILD_ID" != "all" ] && [[ ! "$DISCORD_GUILD_ID" =~ ^[0-9]+$ ]]; then
  fail "DISCORD_GUILD_ID는 숫자 guild id 또는 all 이어야 합니다"
fi
need_command python3

discord_token="${DISCORD_BOT_TOKEN:-}"
if [ -z "$discord_token" ]; then
  discord_token="$(docker compose -f "$COMPOSE_FILE" exec -T "$APP_SERVICE" printenv DISCORD_BOT_TOKEN 2>/dev/null | tr -d '\r\n' || true)"
fi
if [ -z "$discord_token" ]; then
  fail "DISCORD_GUILD_ID가 설정됐지만 DISCORD_BOT_TOKEN을 찾을 수 없습니다"
fi

allowed_channel_rows="$(
  if [ "$DISCORD_GUILD_ID" = "all" ]; then
    run_psql -At -F $'\t' <<'SQL'
SELECT guild_id, channel_id
FROM allowed_channel
ORDER BY guild_id, channel_id;
SQL
  else
    run_psql -At -F $'\t' <<SQL
SELECT guild_id, channel_id
FROM allowed_channel
WHERE guild_id = ${DISCORD_GUILD_ID}
ORDER BY guild_id, channel_id;
SQL
  fi
)"

DISCORD_BOT_TOKEN="$discord_token" \
DISCORD_GUILD_ID="$DISCORD_GUILD_ID" \
NIA_CHANNEL_AUDIT_SPEC="$NIA_CHANNEL_AUDIT_SPEC" \
ALLOWED_CHANNEL_ROWS="$allowed_channel_rows" \
python3 - <<'PY'
from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request


def fail(message: str) -> None:
    print(f"❌ {message}", file=sys.stderr)
    raise SystemExit(1)


token = os.environ["DISCORD_BOT_TOKEN"].strip()
guild_selector = os.environ["DISCORD_GUILD_ID"].strip()
audit_specs: dict[str, list[str]] = {}
for raw_spec in os.environ["NIA_CHANNEL_AUDIT_SPEC"].split(";"):
    if not raw_spec.strip():
        continue
    if "=" not in raw_spec:
        fail(f"NIA_CHANNEL_AUDIT_SPEC 형식 오류: {raw_spec}")
    category_name, raw_names = raw_spec.split("=", 1)
    required_names = [name.strip() for name in raw_names.split(",") if name.strip()]
    if not category_name.strip() or not required_names:
        fail(f"NIA_CHANNEL_AUDIT_SPEC 값이 비었습니다: {raw_spec}")
    audit_specs[category_name.strip()] = required_names
if not audit_specs:
    fail("NIA_CHANNEL_AUDIT_SPEC에 유효한 감사 대상이 없습니다")

allowed_by_guild: dict[str, set[str]] = {}
for row in os.environ.get("ALLOWED_CHANNEL_ROWS", "").splitlines():
    parts = row.split("\t")
    if len(parts) != 2:
        fail(f"allowed_channel row 형식 오류: {row}")
    allowed_by_guild.setdefault(parts[0], set()).add(parts[1])


def fetch_json(path: str):
    request = urllib.request.Request(
        f"https://discord.com/api/v10{path}",
        headers={
            "Authorization": f"Bot {token}",
            "User-Agent": "discord-assistant-ops-policy-audit/1.0",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            return json.load(response)
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", "replace")[:200]
        fail(f"Discord API 조회 실패 HTTP {exc.code} path={path}: {body}")
    except OSError as exc:
        fail(f"Discord API 조회 실패 path={path}: {exc}")


if guild_selector == "all":
    guild_ids = [guild["id"] for guild in fetch_json("/users/@me/guilds")]
else:
    guild_ids = [guild_selector]

missing: list[str] = []
checked_categories = 0
for guild_id in guild_ids:
    channels = fetch_json(f"/guilds/{guild_id}/channels")
    allowed = allowed_by_guild.get(guild_id, set())
    for category_name, required_names in audit_specs.items():
        categories = [channel for channel in channels if channel.get("type") == 4 and channel.get("name") == category_name]
        if not categories:
            continue
        checked_categories += len(categories)
        for category in categories:
            children = [channel for channel in channels if channel.get("parent_id") == category["id"]]
            by_name = {channel.get("name"): channel for channel in children}
            for name in required_names:
                channel = by_name.get(name)
                if channel is None:
                    missing.append(f"guild={guild_id} {category_name}/{name}: Discord 채널 없음")
                    continue
                if allowed and channel["id"] not in allowed:
                    missing.append(f"guild={guild_id} {category_name}/{name}: channel_id={channel['id']} LLM allow-list 누락")

if missing:
    for row in missing:
        print(f"  - {row}", file=sys.stderr)
    fail("니아 자동 채널과 LLM allow-list가 불일치합니다")

if checked_categories == 0:
    print("ℹ️  니아 기능 카테고리 없음: 니아 자동 채널 대조 건너뜀")
else:
    print(f"✅ 니아 자동 채널(ai채팅/ai그림) LLM allow-list 대조 통과(categories={checked_categories})")
PY

ok "정책 감사 통과"
