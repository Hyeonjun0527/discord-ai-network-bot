-- 공개 대상(allowed_role / ProviderModelScope) 제거.
-- '서버 멤버만'은 길드별 라우팅 격리(registry.byGuild)로 이미 구조적 보장이고,
-- 세분화(trusted/admin)는 라우팅에서 강제된 적이 없는 죽은 컬럼이었다. 가짜 설정을 없앤다.
ALTER TABLE provider_contribution_policy DROP COLUMN allowed_role;
