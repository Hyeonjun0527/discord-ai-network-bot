-- at-rest 필드 암호화(안전정책 B5): content_preview 가 암호화되면 base64 로 길어져 VARCHAR(2000)을 넘긴다.
-- TEXT 로 확장(점진 암호화 — 기존 평문도 호환). constitution 류는 V30 에서 이미 TEXT.
ALTER TABLE knowledge_chunk ALTER COLUMN content_preview SET DATA TYPE TEXT;
