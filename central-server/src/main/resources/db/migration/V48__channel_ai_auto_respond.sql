-- ai채팅처럼 지정된 채널이 멘션 없이 모든 메시지에 자동 응답하도록 플래그를 추가한다.
-- 기본 false(기존 채널은 멘션 필요 그대로). "니아 채널 자동 만들기"의 ai채팅만 true 로 켠다.
ALTER TABLE channel_ai ADD COLUMN auto_respond BOOLEAN NOT NULL DEFAULT FALSE;
