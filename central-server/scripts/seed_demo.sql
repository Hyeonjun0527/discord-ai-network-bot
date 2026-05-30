-- 데모용 시드 데이터(차수 17 #257). 수동 실행: 빈 DB 에 데모 길드/정책을 넣는다.
--   psql "$DB_URL" -f central-server/scripts/seed_demo.sql
-- 주의: 운영 DB 에 실행하지 말 것. 데모/로컬 전용. (Flyway 마이그레이션 아님 — 수동 시드)
-- 데모 길드 id=100, 역할 id=1(일반)/2(신뢰).

-- 길드 기본값(V2: default_model, language)
INSERT INTO guild (id, privacy_mode, auto_approve, default_model, language)
VALUES (100, 'C_ADMIN_ONLY', TRUE, 'llama3', 'ko')
ON CONFLICT (id) DO UPDATE
  SET auto_approve = EXCLUDED.auto_approve,
      default_model = EXCLUDED.default_model,
      language = EXCLUDED.language;

-- 역할별 정책: 일반(LIGHT/20), 신뢰(STANDARD/100)
INSERT INTO role_policy (guild_id, role_id, max_burden, daily_limit) VALUES
  (100, 1, 'LIGHT', 20),
  (100, 2, 'STANDARD', 100);

-- 허용 채널 예시(채널 id=200)
INSERT INTO allowed_channel (guild_id, channel_id) VALUES (100, 200);
