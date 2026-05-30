-- 길드별 환영/안내 메시지 (LAUNCH 차수 12 #174). NULL=미설정.
ALTER TABLE guild ADD COLUMN welcome_message VARCHAR(1000);
