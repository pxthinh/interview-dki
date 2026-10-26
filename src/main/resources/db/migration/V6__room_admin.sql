-- Each room has an owning admin. Reverse-synced bookings whose Google creator email matches
-- no user are attributed to the room's admin (fallback: the system import user).
ALTER TABLE rooms ADD COLUMN admin_user_id uuid REFERENCES users (id);

-- Existing rooms: owned by the seeded admin when present.
UPDATE rooms SET admin_user_id = (SELECT id FROM users WHERE email = 'admin@dki.local' LIMIT 1);
