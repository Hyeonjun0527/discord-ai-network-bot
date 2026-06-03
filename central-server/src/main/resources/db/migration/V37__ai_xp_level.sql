-- 서버(길드) AI 레벨·경험치 게이미피케이션 Phase 1.
-- ai_network_profile(길드당 1행, V8 에서 uk_ai_network_profile_guild UNIQUE 보유)에
-- 활동 경험치(total_xp)·활동 레벨(ai_level)·마지막 적립 시각(last_xp_at)을 더한다.
-- 기존 networkLevel(milestone 기반 "구성 단계")과는 의미가 다른 별개 컬럼이다.
-- H2(PostgreSQL 모드)·실 Postgres 양쪽에서 동작하는 SQL 만 사용한다.
ALTER TABLE ai_network_profile ADD COLUMN total_xp BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ai_network_profile ADD COLUMN ai_level INT NOT NULL DEFAULT 1;
ALTER TABLE ai_network_profile ADD COLUMN last_xp_at TIMESTAMP;

-- guild_id UNIQUE 제약은 V8 의 uk_ai_network_profile_guild 로 이미 보장되어 추가하지 않는다
-- (ensureNetworkProfile find-or-create 동시성 안전 + 조건부 레벨 UPDATE 멱등성의 전제).
