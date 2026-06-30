-- NIA durable judge trace fields for participation decisions.
-- Additive only. Stores version/hash/ref/source metadata, never raw message text, prompt text, or model output.

ALTER TABLE nexa_policy_decision_log
    ADD COLUMN judge_model_version VARCHAR(160);

ALTER TABLE nexa_policy_decision_log
    ADD COLUMN judge_prompt_version VARCHAR(160);

ALTER TABLE nexa_policy_decision_log
    ADD COLUMN fewshot_set_id VARCHAR(160);

ALTER TABLE nexa_policy_decision_log
    ADD COLUMN fewshot_version INTEGER;

ALTER TABLE nexa_policy_decision_log
    ADD COLUMN raw_window_hash VARCHAR(160);

ALTER TABLE nexa_policy_decision_log
    ADD COLUMN raw_window_message_refs_json VARCHAR(2048) NOT NULL DEFAULT '[]';

ALTER TABLE nexa_policy_decision_log
    ADD COLUMN shadow_baseline_action VARCHAR(24);

ALTER TABLE nexa_policy_decision_log
    ADD COLUMN final_decision_source VARCHAR(96);

CREATE INDEX idx_nexa_policy_decision_fewshot ON nexa_policy_decision_log(fewshot_set_id, fewshot_version);
CREATE INDEX idx_nexa_policy_decision_raw_window_hash ON nexa_policy_decision_log(raw_window_hash);
