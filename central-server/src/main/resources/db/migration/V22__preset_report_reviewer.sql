ALTER TABLE preset_report
    ADD COLUMN reviewed_by BIGINT;

CREATE INDEX idx_preset_report_reviewed_by ON preset_report(reviewed_by);
