-- Weekly recurring bookings: every occurrence is a real bookings row (so the exclusion
-- constraint keeps guarding conflicts), linked to its series. The series row itself is
-- minimal; occurrence data lives on the bookings.
CREATE TABLE recurring_series (
    id         uuid PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES users (id),
    created_at timestamptz NOT NULL
);

ALTER TABLE bookings ADD COLUMN series_id uuid REFERENCES recurring_series (id);
CREATE INDEX idx_bookings_series ON bookings (series_id) WHERE series_id IS NOT NULL;
