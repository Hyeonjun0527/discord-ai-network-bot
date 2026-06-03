-- 감사: 사용자 작성(시스템 프롬프트 constitution)·진단/에러(reason) 텍스트가 VARCHAR(500~2000)로
-- 묶여 잘릴 위험. 인덱스 없는 자유 텍스트이므로 TEXT 로 넓힌다. SET DATA TYPE 은 H2/Postgres 공통.

-- 시스템 프롬프트(사용자 작성, 길어질 수 있음)
ALTER TABLE ai_behavior_version ALTER COLUMN constitution SET DATA TYPE TEXT;
ALTER TABLE preset_revision ALTER COLUMN constitution SET DATA TYPE TEXT;

-- 진단/에러/사유 텍스트(LLM·프로바이더 메시지가 길 수 있음)
ALTER TABLE ai_request ALTER COLUMN fail_reason SET DATA TYPE TEXT;
ALTER TABLE embedding_index_job ALTER COLUMN failure_reason SET DATA TYPE TEXT;
ALTER TABLE multi_response_run ALTER COLUMN failure_reason SET DATA TYPE TEXT;
ALTER TABLE ai_feedback ALTER COLUMN resolution_reason SET DATA TYPE TEXT;
ALTER TABLE multi_response_policy ALTER COLUMN disabled_reason SET DATA TYPE TEXT;

-- 직렬화 스냅샷/리스트(후보 많아지면 길어짐)
ALTER TABLE ai_change_proposal ALTER COLUMN routing_snapshot SET DATA TYPE TEXT;
ALTER TABLE provider_capability_profile ALTER COLUMN model_names SET DATA TYPE TEXT;
