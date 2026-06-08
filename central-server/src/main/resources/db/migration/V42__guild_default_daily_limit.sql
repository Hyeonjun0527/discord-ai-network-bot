-- 길드별 유저 일일 사용 한도(요청자 쿼터) 기본값. NULL=하드코딩 기본(20), 0=무제한.
-- 역할 정책(role_policy.daily_limit)과 별개 — 역할 정책이 없는 일반 멤버에게 적용되는 길드 기본값이다.
ALTER TABLE guild ADD COLUMN IF NOT EXISTS default_daily_limit INTEGER;
