-- 길드 기본 모델/언어 설정 (LAUNCH 차수 11 #146).
ALTER TABLE guild ADD COLUMN default_model VARCHAR(128);
ALTER TABLE guild ADD COLUMN language VARCHAR(8) NOT NULL DEFAULT 'ko';
