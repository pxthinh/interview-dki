-- Optimistic locking for bookings: protects against lost updates when a user edits and
-- cancels the same booking concurrently (or an admin and the owner race each other).
ALTER TABLE bookings ADD COLUMN version bigint NOT NULL DEFAULT 0;
