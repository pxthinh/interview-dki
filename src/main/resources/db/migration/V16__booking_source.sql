-- Where a booking was born: APP (web/API) or GOOGLE (imported from a room calendar). Imported
-- bookings get no personal copy for their organizer: the creator already has the event in
-- Google, or the organizer is only the room-admin fallback attribution.
ALTER TABLE bookings ADD COLUMN source varchar(10) NOT NULL DEFAULT 'APP'
    CHECK (source IN ('APP', 'GOOGLE'));

-- Backfill what can be recognised: imports are titled "[CALENDAR] ..." (or carry the
-- rejection label).
UPDATE bookings SET source = 'GOOGLE'
WHERE title LIKE '[CALENDAR] %' OR title LIKE '[TRÙNG LỊCH - KHÔNG HỢP LỆ] %';
