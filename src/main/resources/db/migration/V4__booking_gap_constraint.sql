-- Business rule: 15-minute turnover break between two bookings of the same room.
-- The exclusion constraint pads end_time by 15 minutes, so two ACTIVE bookings closer than
-- the break (including back-to-back) are rejected at the database level under any concurrency.
--
-- timestamptz + interval is only STABLE in PostgreSQL (month/day intervals depend on the
-- session timezone), so it cannot sit in an index expression directly. Adding a fixed number
-- of minutes to an absolute instant IS immutable, which this wrapper function declares.
CREATE FUNCTION booking_busy_range(start_time timestamptz, end_time timestamptz)
    RETURNS tstzrange
    LANGUAGE sql
    IMMUTABLE
    RETURN tstzrange(start_time, end_time + interval '15 minutes', '[)');

ALTER TABLE bookings DROP CONSTRAINT bookings_no_overlap;
ALTER TABLE bookings ADD CONSTRAINT bookings_no_overlap EXCLUDE USING gist
    (room_id WITH =, booking_busy_range(start_time, end_time) WITH &&)
    WHERE (status = 'ACTIVE');
