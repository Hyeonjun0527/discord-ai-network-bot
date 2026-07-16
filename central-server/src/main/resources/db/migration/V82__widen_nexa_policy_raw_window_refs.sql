-- Raw judge windows can contain enough stable message refs to exceed the original 2,048 character trace limit.
ALTER TABLE nexa_policy_decision_log ALTER COLUMN raw_window_message_refs_json SET DATA TYPE TEXT;
