-- Buffer (turnover break) becomes a per-room toggle. Each booking snapshots the buffer that
-- applied when it was made, so the exclusion constraint can keep enforcing the break at the
-- database level: a booking's busy range is [start, end + its own buffer).
ALTER TABLE rooms ADD COLUMN buffer_enabled boolean NOT NULL DEFAULT true;
ALTER TABLE bookings ADD COLUMN buffer_minutes int NOT NULL DEFAULT 15
    CHECK (buffer_minutes IN (0, 15));

ALTER TABLE bookings DROP CONSTRAINT bookings_no_overlap;
DROP FUNCTION booking_busy_range(timestamptz, timestamptz);

CREATE FUNCTION booking_busy_range(start_time timestamptz, end_time timestamptz, buffer_minutes int)
    RETURNS tstzrange
    LANGUAGE sql
    IMMUTABLE
    RETURN tstzrange(start_time, end_time + make_interval(mins => buffer_minutes), '[)');

ALTER TABLE bookings ADD CONSTRAINT bookings_no_overlap EXCLUDE USING gist
    (room_id WITH =, booking_busy_range(start_time, end_time, buffer_minutes) WITH &&)
    WHERE (status = 'ACTIVE');
