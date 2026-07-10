-- A scheduled action keeps the rollout authority it had when it was decided.
-- Existing pending rows are intentionally backfilled as OFF: a later channel promotion must not turn an unproven
-- shadow-era action into a real Discord send.
ALTER TABLE nexa_scheduled_action
    ADD COLUMN IF NOT EXISTS origin_rollout_mode VARCHAR(24) NOT NULL DEFAULT 'OFF';

ALTER TABLE nexa_scheduled_action
    ADD CONSTRAINT ck_nexa_scheduled_action_origin_rollout_mode
        CHECK (origin_rollout_mode IN ('OFF', 'OBSERVE_ONLY', 'SHADOW_PREDICT', 'CANARY', 'LIVE'));
