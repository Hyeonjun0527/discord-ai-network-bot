-- 감사 2026-06-03 A항목: 코드가 단일행을 가정하나 DB UNIQUE 미보장이던 3개 테이블에
-- UNIQUE 제약을 추가한다(NonUniqueResultException·정합성 깨짐 방지).
-- 기존 데이터에 중복이 있을 수 있어 **먼저 중복을 정리(최신 id 1건만 유지)** 한 뒤 제약을 건다.
-- GROUP BY 는 NULL 을 한 그룹으로 묶으므로 channel_id NULL(길드 기본행)도 올바르게 1건만 남는다.
-- H2(PostgreSQL 모드)·Postgres 양쪽에서 동작하는 일반 UNIQUE 만 사용한다
-- (COALESCE/partial 표현식 인덱스는 H2 미지원). channel-specific 행 중복은 양 DB 모두 완전 차단되며,
-- channel_id NULL 기본행의 단일성은 dedup 으로 정리 + 앱(check-then-update)·짧은 트랜잭션 경계로 보장한다.

-- 1) provider(provider_user_id, guild_id) — 두 컬럼 모두 NOT NULL.
DELETE FROM provider
WHERE id NOT IN (
    SELECT MAX(id) FROM provider GROUP BY provider_user_id, guild_id
);
CREATE UNIQUE INDEX uq_provider_user_guild ON provider (provider_user_id, guild_id);

-- 2) multi_response_policy(guild_id, channel_id) — channel_id NULL 은 길드 기본행.
DELETE FROM multi_response_policy
WHERE id NOT IN (
    SELECT MAX(id) FROM multi_response_policy GROUP BY guild_id, channel_id
);
CREATE UNIQUE INDEX uq_multi_response_policy_guild_channel
    ON multi_response_policy (guild_id, channel_id);

-- 3) retrieval_policy(guild_id, channel_id) — channel_id NULL 은 길드 기본행.
DELETE FROM retrieval_policy
WHERE id NOT IN (
    SELECT MAX(id) FROM retrieval_policy GROUP BY guild_id, channel_id
);
CREATE UNIQUE INDEX uq_retrieval_policy_guild_channel
    ON retrieval_policy (guild_id, channel_id);
