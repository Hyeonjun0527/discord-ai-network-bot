-- Discord thread key는 "discord:<guild-pseudonym>:<channel-id>" 형식이라 기존 64자를 넘을 수 있다.
-- 도메인/JPA 계약과 동일하게 256자로 넓혀 운영 target을 손실 없이 저장한다.
ALTER TABLE nexa_scheduled_action ALTER COLUMN thread_id SET DATA TYPE VARCHAR(256);
