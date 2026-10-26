-- Seed the full room inventory (~10 rooms per the problem statement) so the system is
-- testable right after migration. ON CONFLICT keeps this idempotent on databases where the
-- old ApplicationRunner already seeded the first five rooms.
INSERT INTO rooms (id, name, location, capacity, amenities, active, google_calendar_id, created_at, updated_at)
VALUES
    (gen_random_uuid(), 'Mercury', 'Tang 2', 4,  '{tv}',                                   true, NULL, now(), now()),
    (gen_random_uuid(), 'Venus',   'Tang 2', 6,  '{tv,whiteboard}',                        true, NULL, now(), now()),
    (gen_random_uuid(), 'Earth',   'Tang 3', 8,  '{projector,whiteboard}',                 true, NULL, now(), now()),
    (gen_random_uuid(), 'Mars',    'Tang 3', 12, '{projector,whiteboard,video-conference}', true, NULL, now(), now()),
    (gen_random_uuid(), 'Jupiter', 'Tang 5', 20, '{projector,video-conference}',           true, NULL, now(), now()),
    (gen_random_uuid(), 'Mekong',  'Tang 2', 7,  '{tv,whiteboard}',                        true, NULL, now(), now()),
    (gen_random_uuid(), 'Danang',  'Tang 3', 9,  '{tv,projector}',                         true, NULL, now(), now()),
    (gen_random_uuid(), 'Hanoi',   'Tang 4', 10, '{projector,whiteboard}',                 true, NULL, now(), now()),
    (gen_random_uuid(), 'Saigon',  'Tang 4', 14, '{projector,whiteboard,video-conference}', true, NULL, now(), now()),
    (gen_random_uuid(), 'Halong',  'Tang 5', 16, '{projector,video-conference}',           true, NULL, now(), now())
ON CONFLICT (name) DO NOTHING;

-- Earth is wired to the real company calendar used for manual testing.
UPDATE rooms
SET google_calendar_id = '1c7140b13e846fee05750490317dc48a32a4179f610d564128fd7dc4154dcfe0@group.calendar.google.com'
WHERE name = 'Earth' AND google_calendar_id IS NULL;
