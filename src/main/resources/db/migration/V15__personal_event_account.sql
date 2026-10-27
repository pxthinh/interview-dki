-- A personal copy's event id is only meaningful in the Google account that created it. Record
-- that account so reconnecting a DIFFERENT Google account can drop stale mappings (the old
-- events cannot be reached with the new token) while reconnecting the same one keeps them.
ALTER TABLE booking_user_events ADD COLUMN google_email varchar(255);

UPDATE booking_user_events m
SET google_email = a.google_email
FROM user_google_accounts a
WHERE a.user_id = m.user_id;
