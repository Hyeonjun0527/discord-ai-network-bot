-- 채널 AI 자유 지침(custom instruction) 슬롯.
-- 관리자가 채널 AI에 자연어 페르소나/색깔 지침("너는 우리 길드 공대장 '냥대장'이야 …")을 추가하면
-- 이 컬럼에 behavior 버전의 일부로 저장·버전관리되고, /ask 시스템 프롬프트 정체성 블록 뒤에 삽입된다.
-- 슬롯(purpose/tone/answerLength/constitution)=가드레일, 자유 지침=색깔로 공존한다.
-- 길이 상한은 코드에서 2000자로 take 하므로 가변 길이 TEXT 로 둔다(기존 마이그레이션 컨벤션과 동일,
-- H2 PostgreSQL 모드·실 Postgres 공통 문법만 사용).

ALTER TABLE ai_behavior_version ADD COLUMN custom_instruction TEXT;
