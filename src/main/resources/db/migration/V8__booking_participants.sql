-- Participants invited to a meeting (besides the booker). Notified by email on
-- invite, cancellation and changes.
CREATE TABLE booking_participants (
    booking_id uuid NOT NULL REFERENCES bookings (id),
    user_id    uuid NOT NULL REFERENCES users (id),
    PRIMARY KEY (booking_id, user_id)
);
