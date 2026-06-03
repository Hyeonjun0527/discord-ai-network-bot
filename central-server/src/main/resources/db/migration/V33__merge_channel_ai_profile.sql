-- 감사 2026-06-03 C항목: ChannelAi 이중 엔티티 단일화.
-- legacy channel_ai_profile(그림자 복사본)을 권위 소스 channel_ai 로 흡수하고 테이블을 제거한다.
-- V7 이 최초 백필을 했지만 그 이후 set()/syncLegacyProfile() 로 추가된 행이 남아 있을 수 있어,
-- (guild_id, channel_id) 가 channel_ai 에 아직 없는 legacy 행만 멱등적으로 백필한다.
-- H2(PostgreSQL 모드)·Postgres 양쪽에서 동작하는 INSERT...SELECT...WHERE NOT EXISTS 만 사용한다.

-- 1) channel_ai 에 없는 legacy 프로필을 백필한다. NOT NULL 컬럼(created_at/updated_at/source)을 모두 채운다.
INSERT INTO channel_ai(guild_id, channel_id, display_name, avatar_url, source, created_at, updated_at)
SELECT guild_id, channel_id, display_name, avatar_url, 'legacy_migrated', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM channel_ai_profile
WHERE NOT EXISTS (
    SELECT 1
    FROM channel_ai ca
    WHERE ca.guild_id = channel_ai_profile.guild_id
      AND ca.channel_id = channel_ai_profile.channel_id
);

-- 2) 방금 백필된 채널에 기본 행동 버전(v1)을 생성한다(없을 때만).
INSERT INTO ai_behavior_version(channel_ai_id, version, purpose, tone, answer_length, constitution, safety_level, created_by, change_summary, created_at)
SELECT ca.id,
       1,
       'general_assistant',
       'friendly',
       'balanced',
       '민감정보(비밀번호·API 키·개인정보)는 입력하지 않도록 안내하고, 확실하지 않은 내용은 단정하지 않습니다.',
       'standard',
       NULL,
       'legacy channel_ai_profile merge (V33)',
       CURRENT_TIMESTAMP
FROM channel_ai ca
WHERE ca.source = 'legacy_migrated'
  AND NOT EXISTS (
      SELECT 1
      FROM ai_behavior_version bv
      WHERE bv.channel_ai_id = ca.id
        AND bv.version = 1
  );

-- 3) active_behavior_version_id 가 비어 있으면 방금 만든 v1 으로 연결한다.
UPDATE channel_ai
SET active_behavior_version_id = (
    SELECT MAX(bv.id)
    FROM ai_behavior_version bv
    WHERE bv.channel_ai_id = channel_ai.id
)
WHERE active_behavior_version_id IS NULL;

-- 4) legacy 그림자 테이블 제거(소속 인덱스도 함께 제거됨).
DROP TABLE channel_ai_profile;
