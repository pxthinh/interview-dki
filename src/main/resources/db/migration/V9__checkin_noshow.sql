-- Check-in / no-show: a booking not checked in within the grace period after start is
-- released as NO_SHOW. The exclusion constraint only guards status = 'ACTIVE', so a
-- NO_SHOW booking frees its slot with no constraint change.
ALTER TABLE bookings ADD COLUMN checked_in_at timestamptz;

ALTER TABLE bookings DROP CONSTRAINT bookings_status_check;
ALTER TABLE bookings ADD CONSTRAINT bookings_status_check
    CHECK (status IN ('ACTIVE', 'CANCELLED', 'NO_SHOW'));
