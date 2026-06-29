-- NEXA participation decision explainability metadata.
-- 원문 메시지/프롬프트/모델 응답 본문은 저장하지 않는다. evidence_refs 는 raw context/window 의 안정 참조만 담는다.

ALTER TABLE nexa_policy_decision_log
    ADD COLUMN reason_code VARCHAR(96);

ALTER TABLE nexa_policy_decision_log
    ADD COLUMN judge_confidence DOUBLE PRECISION;

ALTER TABLE nexa_policy_decision_log
    ADD COLUMN decision_delay_millis BIGINT;

ALTER TABLE nexa_policy_decision_log
    ADD COLUMN last_wake_up_reason VARCHAR(96);

ALTER TABLE nexa_policy_decision_log
    ADD COLUMN missing_input_codes VARCHAR(512) NOT NULL DEFAULT '';

ALTER TABLE nexa_policy_decision_log
    ADD COLUMN evidence_refs VARCHAR(1024) NOT NULL DEFAULT '';
