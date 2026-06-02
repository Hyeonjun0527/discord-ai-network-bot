ALTER TABLE preset_import
    ADD COLUMN source_revision_id BIGINT;

ALTER TABLE preset_import
    ADD CONSTRAINT fk_preset_import_source_revision
    FOREIGN KEY (source_revision_id) REFERENCES preset_revision(id) ON DELETE SET NULL;

CREATE INDEX idx_preset_import_source_revision ON preset_import(source_revision_id);
