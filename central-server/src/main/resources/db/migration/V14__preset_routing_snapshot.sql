ALTER TABLE preset_revision
    ADD COLUMN response_mode VARCHAR(40) NOT NULL DEFAULT 'balanced';

ALTER TABLE preset_revision
    ADD COLUMN preferred_model VARCHAR(160);

ALTER TABLE preset_revision
    ADD COLUMN min_quality_tier VARCHAR(40) NOT NULL DEFAULT 'standard';

ALTER TABLE preset_revision
    ADD COLUMN max_candidates INT NOT NULL DEFAULT 1;

ALTER TABLE preset_revision
    ADD COLUMN provider_tag_filter VARCHAR(1000);

ALTER TABLE preset_revision
    ADD COLUMN cost_guard VARCHAR(80) NOT NULL DEFAULT 'provider_safe';
