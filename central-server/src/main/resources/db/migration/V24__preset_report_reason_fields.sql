ALTER TABLE preset_report
    ADD COLUMN reason_code VARCHAR(60) NOT NULL DEFAULT 'other';

ALTER TABLE preset_report
    ADD COLUMN details VARCHAR(500);

CREATE INDEX idx_preset_report_reason_code ON preset_report(reason_code);
