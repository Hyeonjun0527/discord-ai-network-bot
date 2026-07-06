-- 니아 사회 마음 상태(관계 4축·감정) 낙관적 락 컬럼 — additive only(기존 V1~V74 미변경).
-- observe() 의 load→compute→save read-modify-write 가 동시 메시지에서 갱신을 잃지 않도록 @Version 을 붙인다.
-- (첫 행 중복 INSERT 는 V70 의 유니크 제약 uq_nia_relationship_scope_person·uq_nia_emotion_scope 가 이미 막고,
--  애플리케이션이 충돌을 재시도로 흡수한다.) H2(PostgreSQL 모드)·Postgres 공통 SQL.

ALTER TABLE nia_relationship_state ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE nia_emotion_state ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
