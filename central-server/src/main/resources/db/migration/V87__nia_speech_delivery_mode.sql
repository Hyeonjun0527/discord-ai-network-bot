ALTER TABLE nexa_fewshot_example
    ADD COLUMN expected_delivery_mode VARCHAR(16);

ALTER TABLE nexa_scheduled_action
    ADD COLUMN delivery_mode VARCHAR(16) NOT NULL DEFAULT 'REPLY';
