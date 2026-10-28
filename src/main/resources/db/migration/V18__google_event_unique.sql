-- One Google event maps to at most one booking. Before the import/outbox race was fixed, a
-- rejected echo of our own event could leave a CANCELLED trace row with the same
-- google_event_id as the real booking, and the import's lookup then failed on every poll.
-- Clear the id on the duplicates first (a non-cancelled row wins, then the oldest) so the
-- index can be built, then make duplicates impossible.
WITH ranked AS (
    SELECT id, row_number() OVER (PARTITION BY google_event_id
                                  ORDER BY (status = 'CANCELLED'), created_at, id) AS rn
    FROM bookings
    WHERE google_event_id IS NOT NULL)
UPDATE bookings b SET google_event_id = NULL
FROM ranked r
WHERE r.id = b.id AND r.rn > 1;

create unique index if not exists bookings_google_event_id_uidx
    on bookings (google_event_id) where google_event_id is not null;
