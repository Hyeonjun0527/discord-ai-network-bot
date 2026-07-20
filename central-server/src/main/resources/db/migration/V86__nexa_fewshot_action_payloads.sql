ALTER TABLE nexa_fewshot_example
    ADD COLUMN current_state TEXT;
ALTER TABLE nexa_fewshot_example
    ADD COLUMN expected_reaction_code VARCHAR(64);
ALTER TABLE nexa_fewshot_example
    ADD COLUMN expected_reevaluate_after_ms BIGINT;
