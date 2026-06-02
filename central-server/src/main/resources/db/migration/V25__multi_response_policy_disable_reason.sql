ALTER TABLE multi_response_policy
    ADD COLUMN disabled_reason VARCHAR(500);

CREATE INDEX idx_multi_response_policy_disabled
    ON multi_response_policy(guild_id, channel_id, disabled_reason);
