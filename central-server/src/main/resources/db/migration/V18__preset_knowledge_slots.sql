ALTER TABLE preset_revision
    ADD COLUMN knowledge_slot_names VARCHAR(1000);

ALTER TABLE preset_revision
    ADD COLUMN knowledge_guide VARCHAR(1000);
