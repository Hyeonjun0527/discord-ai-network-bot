-- 감사(2026-06-03)로 발견한 인덱스 결함 보정: 리포지토리 파생 쿼리가 풀스캔하던 컬럼에 인덱스를
-- 추가하고, UNIQUE 와 컬럼셋이 동일한 중복 비-unique 인덱스를 제거한다. 모두 가산/정리 작업.

-- ── 누락 인덱스 추가(HIGH: 풀스캔/정렬 제거) ─────────────────────────────
-- ai_request: provider+state(라우팅/분석), guild+id(최근 목록·카운트)
CREATE INDEX idx_ai_request_provider_state ON ai_request(provider_id, state);
CREATE INDEX idx_ai_request_guild ON ai_request(guild_id, id DESC);

-- usage_log: (guild,user,created) 로 일일 한도 범위쿼리 커버 + (guild,created) 길드 집계.
--            기존 (guild,user) 프리픽스 인덱스는 새 인덱스에 포함되어 중복 → 제거.
CREATE INDEX idx_usage_guild_user_created ON usage_log(guild_id, user_id, created_at);
CREATE INDEX idx_usage_guild_created ON usage_log(guild_id, created_at);
DROP INDEX IF EXISTS idx_usage_guild_user;

-- provider: 프로바이더 식별 핵심 조회(provider_user_id, guild_id) — 인덱스 없었음.
CREATE INDEX idx_provider_user_guild ON provider(provider_user_id, guild_id);

-- ai_feedback: 멱등/중복방지 조회(guild, request_id, user_id).
CREATE INDEX idx_ai_feedback_request_user ON ai_feedback(guild_id, request_id, user_id);

-- multi_response_run: 최근 실행 목록(guild, started_at DESC).
CREATE INDEX idx_multi_response_run_guild ON multi_response_run(guild_id, started_at DESC);

-- preset_report: 신고 멱등 조회(published_preset_id, reporter_user_id, status) — FK 무인덱스였음.
CREATE INDEX idx_preset_report_published ON preset_report(published_preset_id, reporter_user_id, status);

-- ai_network_event: 타입별 조회(guild, event_type, created_at DESC).
CREATE INDEX idx_ai_network_event_type ON ai_network_event(guild_id, event_type, created_at DESC);

-- ── 누락 인덱스 추가(MED) ───────────────────────────────────────────────
CREATE INDEX idx_provider_schedule_guild ON provider_schedule(guild_id);
CREATE INDEX idx_ai_change_proposal_guild_status ON ai_change_proposal(guild_id, status, created_at DESC);
CREATE INDEX idx_ai_change_proposal_channel_ai ON ai_change_proposal(channel_ai_id);
CREATE INDEX idx_knowledge_source_guild ON knowledge_source(guild_id);
CREATE INDEX idx_knowledge_chunk_guild_space_status ON knowledge_chunk(guild_id, knowledge_space_id, status);
CREATE INDEX idx_embedding_index_job_guild_queued ON embedding_index_job(guild_id, queued_at DESC);

-- ── 중복 인덱스 제거(UNIQUE 와 동일 컬럼셋) ──────────────────────────────
DROP INDEX IF EXISTS idx_provider_durable_revocation; -- uk_provider_durable_revocation 와 동일
DROP INDEX IF EXISTS idx_ai_admin_role_guild;         -- uk_ai_admin_role 와 동일
DROP INDEX IF EXISTS idx_channel_ai_profile_channel;  -- uk_channel_ai_profile_channel 와 동일
DROP INDEX IF EXISTS idx_channel_ai_channel;          -- uk_channel_ai_channel 와 동일
DROP INDEX IF EXISTS idx_ai_network_profile_guild;    -- uk_ai_network_profile_guild 와 동일
