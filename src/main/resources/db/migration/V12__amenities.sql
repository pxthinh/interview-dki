-- Amenity catalog managed by admins; drives the pickers only. Rooms keep their text[] column,
-- so removing a catalog entry never touches existing rooms. Seeded from values already in use.
CREATE TABLE amenities (
    name varchar(100) PRIMARY KEY
);

INSERT INTO amenities (name)
SELECT DISTINCT unnest(amenities) FROM rooms
ON CONFLICT DO NOTHING;
