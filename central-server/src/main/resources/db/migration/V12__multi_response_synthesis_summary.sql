ALTER TABLE synthesis_result
    ADD COLUMN strategy VARCHAR(80) NOT NULL DEFAULT 'best_by_heuristic';

ALTER TABLE synthesis_result
    ADD COLUMN quality_summary VARCHAR(1000);

ALTER TABLE synthesis_result
    ADD COLUMN safety_summary VARCHAR(1000);
