-- NEXA "AI 멤버 채널" 온보딩 동의의 목적별 독립 컬럼(NEXA-P15-T014) — additive only.
-- 기존 guild_onboarding_consent 는 message_backfill_opted_in 단일 동의만 갖는다. NEXA 멤버 채널은
-- 데이터 범위·외부 GLM·shadow/live·학습을 **각각 따로** 동의해야 하므로(포괄 동의 금지, consent-model.md)
-- 4개 컬럼을 추가한다. 모두 기본 FALSE = 봇 추가·버튼 클릭만으로는 어떤 목적도 켜지지 않는다(fail-closed).
-- 기존 행/컬럼은 건드리지 않는다(기존 V49 DB·새 DB 모두 적용 가능). H2(PostgreSQL 모드)·Postgres 공통 SQL.

ALTER TABLE guild_onboarding_consent ADD COLUMN nexa_observe_scope BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE guild_onboarding_consent ADD COLUMN nexa_external_glm_allowed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE guild_onboarding_consent ADD COLUMN nexa_live_send_allowed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE guild_onboarding_consent ADD COLUMN nexa_learning_opt_in BOOLEAN NOT NULL DEFAULT FALSE;
