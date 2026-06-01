ALTER TABLE preset_import
    ADD COLUMN created_channel_ai_id BIGINT;

ALTER TABLE preset_import
    ADD COLUMN created_behavior_version_id BIGINT;

ALTER TABLE preset_import
    ADD CONSTRAINT fk_preset_import_channel_ai
    FOREIGN KEY (created_channel_ai_id) REFERENCES channel_ai(id) ON DELETE SET NULL;

ALTER TABLE preset_import
    ADD CONSTRAINT fk_preset_import_behavior
    FOREIGN KEY (created_behavior_version_id) REFERENCES ai_behavior_version(id) ON DELETE SET NULL;

CREATE INDEX idx_preset_import_created_channel_ai ON preset_import(created_channel_ai_id);
