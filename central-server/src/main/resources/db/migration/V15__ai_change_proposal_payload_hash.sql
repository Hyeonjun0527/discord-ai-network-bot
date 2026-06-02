ALTER TABLE ai_change_proposal ADD COLUMN IF NOT EXISTS payload_hash VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_ai_change_proposal_payload_hash ON ai_change_proposal(payload_hash);
