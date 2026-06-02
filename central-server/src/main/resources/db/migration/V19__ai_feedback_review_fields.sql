ALTER TABLE ai_feedback ADD COLUMN reviewed_by BIGINT;
ALTER TABLE ai_feedback ADD COLUMN reviewed_at TIMESTAMP;
ALTER TABLE ai_feedback ADD COLUMN resolution_reason VARCHAR(500);

CREATE INDEX idx_ai_feedback_review_queue ON ai_feedback(guild_id, status, created_at DESC);
