-- User-scope consent revocation support for pending scheduled actions.
-- Nullable for existing/legacy actions; new participation SPEAK/REACT reservations fill this with the consent subject pseudonym.
ALTER TABLE nexa_scheduled_action
    ADD COLUMN IF NOT EXISTS subject_pseudonym VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_nexa_scheduled_action_subject
    ON nexa_scheduled_action(guild_pseudonym, channel_id, subject_pseudonym);
