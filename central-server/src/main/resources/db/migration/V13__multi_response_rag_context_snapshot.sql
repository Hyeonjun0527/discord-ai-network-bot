ALTER TABLE multi_response_run
    ADD COLUMN rag_context_status VARCHAR(80);

ALTER TABLE multi_response_run
    ADD COLUMN rag_context_source_ids VARCHAR(1000);

ALTER TABLE multi_response_run
    ADD COLUMN rag_context_chars INT NOT NULL DEFAULT 0;
