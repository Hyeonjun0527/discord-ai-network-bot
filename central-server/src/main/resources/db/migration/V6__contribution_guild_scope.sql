ALTER TABLE contribution_log ADD COLUMN guild_id BIGINT NOT NULL DEFAULT 0;

UPDATE contribution_log
SET guild_id = COALESCE(
    (
        SELECT ai_request.guild_id
        FROM ai_request
        WHERE ai_request.request_id = contribution_log.request_id
        LIMIT 1
    ),
    guild_id
);

CREATE INDEX idx_contribution_guild_provider ON contribution_log(guild_id, provider_id);
