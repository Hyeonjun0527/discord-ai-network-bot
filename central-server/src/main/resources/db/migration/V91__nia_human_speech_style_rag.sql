CREATE TABLE nia_human_speech_style_example (
    example_id         VARCHAR(64)  PRIMARY KEY,
    response_mode      VARCHAR(32)  NOT NULL,
    quality            VARCHAR(48)  NOT NULL,
    source_fingerprint VARCHAR(96)  NOT NULL,
    consent_revision   VARCHAR(128) NOT NULL,
    combined_chars     INTEGER      NOT NULL,
    enabled            BOOLEAN      NOT NULL DEFAULT TRUE,
    payload_json       TEXT         NOT NULL,
    embedding_json     TEXT         NOT NULL,
    embedding_model    VARCHAR(96)  NOT NULL,
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_nia_human_speech_style_enabled
    ON nia_human_speech_style_example(enabled, response_mode, example_id);
