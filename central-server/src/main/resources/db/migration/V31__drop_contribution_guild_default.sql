-- V6__contribution_guild_scope.sql 에서 guild_id 를 `DEFAULT 0` 으로 추가하고 백필했다.
-- 백필이 완료된 이후에도 DEFAULT 0 이 남아 있으면, 애플리케이션 버그로 guild_id 를 전달하지
-- 않은 INSERT 가 조용히 0 으로 저장되어 데이터 무결성 결함을 가린다.
-- 백필은 이미 끝났으므로 DEFAULT 만 제거한다(NOT NULL 제약은 유지).
-- H2(PostgreSQL 모드)와 PostgreSQL 양쪽에서 동일하게 동작하는 DDL.

ALTER TABLE contribution_log ALTER COLUMN guild_id DROP DEFAULT;
