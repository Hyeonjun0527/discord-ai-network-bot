-- conversation focus(thread_id)와 Discord reply message snowflake는 다른 식별자다.
-- 기존 예약은 reply 없이 유지하고, 새 SPEAK 예약만 실제 트리거 메시지에 답장한다.
ALTER TABLE nexa_scheduled_action ADD COLUMN reply_to_message_id VARCHAR(20);
