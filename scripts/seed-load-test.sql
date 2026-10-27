-- Load-test data: 200 users + 200 bookings per active room spread over the last 20 days.
-- Run against the compose database (NOT a Flyway migration):
--   docker compose exec -T db psql -U roombooking -d roombooking < scripts/seed-load-test.sql
-- Idempotent-ish: users upsert by email; bookings skip any slot that would clash with
-- existing ACTIVE bookings (the exclusion constraint cannot be ON CONFLICT'ed).

-- 200 users, password User@123 (hash copied from the seeded alice account)
INSERT INTO users (id, email, password_hash, full_name, role, active, created_at, updated_at)
SELECT gen_random_uuid(),
       format('loadtest%s@dki.local', lpad(i::text, 3, '0')),
       (SELECT password_hash FROM users WHERE email = 'alice@dki.local'),
       format('Load Test %s', lpad(i::text, 3, '0')),
       'USER', true, now(), now()
FROM generate_series(1, 200) AS i
ON CONFLICT (email) DO NOTHING;

-- Per active room: 20 days x 10 one-hour slots/day, 07:00-19:15 Vietnam time, 75-minute
-- stride so every booking clears the 15-minute turnover buffer of its neighbor.
WITH u AS (
    SELECT array_agg(id) AS ids
    FROM users
    WHERE email LIKE 'loadtest%@dki.local'
)
INSERT INTO bookings (id, room_id, user_id, title, start_time, end_time, status,
                      buffer_minutes, created_at, updated_at)
SELECT gen_random_uuid(),
       r.id,
       u.ids[1 + floor(random() * array_length(u.ids, 1))::int],
       format('Load test D-%s S%s', d, s),
       ((current_date - d) + time '07:00' + (s * interval '75 minutes')) AT TIME ZONE 'Asia/Ho_Chi_Minh',
       ((current_date - d) + time '08:00' + (s * interval '75 minutes')) AT TIME ZONE 'Asia/Ho_Chi_Minh',
       'ACTIVE', 15, now(), now()
FROM rooms r
CROSS JOIN u
CROSS JOIN generate_series(1, 20) AS d
CROSS JOIN generate_series(0, 9) AS s
WHERE r.active = true
  AND NOT EXISTS (
      SELECT 1 FROM bookings b
      WHERE b.room_id = r.id AND b.status = 'ACTIVE'
        AND b.start_time < ((current_date - d) + time '08:15' + (s * interval '75 minutes')) AT TIME ZONE 'Asia/Ho_Chi_Minh'
        AND b.end_time + make_interval(mins => b.buffer_minutes)
            > ((current_date - d) + time '07:00' + (s * interval '75 minutes')) AT TIME ZONE 'Asia/Ho_Chi_Minh'
  );

SELECT count(*) AS loadtest_users FROM users WHERE email LIKE 'loadtest%@dki.local';
SELECT r.name, count(b.id) AS bookings_last_20_days
FROM rooms r LEFT JOIN bookings b
    ON b.room_id = r.id AND b.title LIKE 'Load test %'
GROUP BY r.name ORDER BY r.name;
