CREATE TABLE nia_prompt_configuration (
    id BIGINT PRIMARY KEY,
    active_version INTEGER NOT NULL DEFAULT 0,
    active_documents_json TEXT,
    draft_documents_json TEXT,
    updated_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    applied_at TIMESTAMP
);
