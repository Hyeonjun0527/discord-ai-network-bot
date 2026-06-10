-- 전문가 층(차수: /그림 2층 구조 P2): ComfyUI 웹에서 직접 생성한 이미지를 길드 지정 채널로 자동 포워드.
-- null = 미설정(포워드 안 함). 관리자가 /그림채널 로 설정/해제한다. 채널은 central 이 소유(에이전트가 임의 채널 지정 불가).
ALTER TABLE guild ADD COLUMN IF NOT EXISTS expert_forward_channel_id BIGINT;
