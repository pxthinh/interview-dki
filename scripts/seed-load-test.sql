-- ============================================================================
-- Seed data: 200 users + Hàng loạt booking trong 20 ngày
-- Mỗi phòng có từ 3 đến 5 booking / ngày (phân bổ ngẫu nhiên từ 08:00 đến 18:00)
--
-- Script chạy lại được nhiều lần và tự dọn dẹp: mọi booking seed có tiêu đề bắt đầu
-- bằng "[Seed] "; mỗi lần chạy chỉ xóa các booking seed cũ (cùng outbox / participants /
-- personal copies của chúng), KHÔNG đụng vào booking thủ công.
-- "Hôm nay" tính theo múi giờ nghiệp vụ Asia/Ho_Chi_Minh.
-- Không cần SEED_SYNC để đồng bộ: reconcile job của app tự xếp booking seed của phòng có lịch
-- Google vào outbox trong vòng ~1 phút; SEED_SYNC chỉ để xếp hàng ngay lập tức.
-- Lưu ý khi dùng SEED_SYNC: booking seed đã đẩy lên Google sẽ bị xóa ở lần chạy sau mà KHÔNG xóa
-- sự kiện trên Google Calendar (script chỉ chạm DB); dọn lịch Google bằng tay nếu cần.
--
-- Chạy trên PowerShell:
--   .\scripts\seed-load-test.ps1
-- Chạy trên Linux / Bash:
--   docker compose exec -T db psql -v ON_ERROR_STOP=1 -U roombooking -d roombooking < scripts/seed-load-test.sql
--
-- Bước 4 (đưa booking vào calendar_outbox để đồng bộ Google Calendar) mặc định TẮT.
-- Bật bằng biến psql SEED_SYNC, ví dụ:
--   docker compose exec -T db psql -v ON_ERROR_STOP=1 -v SEED_SYNC=1 -U roombooking -d roombooking < scripts/seed-load-test.sql
--   .\scripts\seed-load-test.ps1 -Sync
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- 1. Tạo 200 tài khoản load test nếu chưa có (mật khẩu: User@123)
WITH h AS (SELECT crypt('User@123', gen_salt('bf', 10)) AS hash)
INSERT INTO users (id, email, password_hash, full_name, role, active, created_at, updated_at)
SELECT gen_random_uuid(),
       format('loadtest%s@dki.local', lpad(i::text, 3, '0')),
       h.hash,
       format('Load Test %s', lpad(i::text, 3, '0')),
       'USER', true, now(), now()
FROM generate_series(1, 200) AS i
CROSS JOIN h
ON CONFLICT (email) DO NOTHING;

-- 2. Dọn dẹp các booking seed cũ (tiêu đề "[Seed] ...") để kết quả seed luôn đúng chuẩn
--    3-5 booking/phòng/ngày. Xóa các bảng con trước, booking sau. Booking thủ công giữ nguyên.
DELETE FROM calendar_outbox
WHERE booking_id IN (SELECT id FROM bookings WHERE title LIKE '[Seed] %');

DELETE FROM booking_participants
WHERE booking_id IN (SELECT id FROM bookings WHERE title LIKE '[Seed] %');

DELETE FROM booking_user_events
WHERE booking_id IN (SELECT id FROM bookings WHERE title LIKE '[Seed] %');

DELETE FROM bookings WHERE title LIKE '[Seed] %';

-- 3. Tạo hàng loạt booking trong 20 ngày (mỗi phòng 3 đến 5 booking/ngày)
WITH candidate_slots AS (
    -- 7 khung giờ mẫu cách nhau 75 phút (60p họp + 15p buffer nghỉ giữa các ca), không bao giờ trùng lịch
    SELECT t AS start_t,
           t + interval '1 hour' AS end_t
    FROM unnest(ARRAY[
        time '08:00',
        time '09:15',
        time '10:30',
        time '13:00',
        time '14:15',
        time '15:30',
        time '16:45'
    ]) AS t
),
titles AS (
    SELECT ARRAY[
        'Họp Daily Scrum & Sync tiến độ',
        'Sprint Planning & Định hướng task',
        'Phỏng vấn ứng viên Technical',
        'Thảo luận kiến trúc hệ thống backend',
        'Review thiết kế UI/UX & Feedback',
        'Họp đồng bộ dự án liên phòng ban',
        '1-on-1 định kỳ với Team Lead',
        'Demo giải pháp tính năng mới',
        'Họp ban điều hành & Định hướng quý',
        'Retrospective & Rút kinh nghiệm'
    ] AS arr
),
users_pool AS (
    SELECT array_agg(id) AS ids
    FROM users
    WHERE active = true
),
room_days AS (
    SELECT r.id AS room_id,
           r.name AS room_name,
           r.buffer_enabled,
           d AS day_offset,
           -- Mỗi phòng mỗi ngày chọn ngẫu nhiên từ 3 đến 5 booking
           (3 + floor(random() * 3))::int AS target_count
    FROM rooms r
    -- Khoảng 20 ngày: 5 ngày trước (-5), HÔM NAY (0), và 14 ngày tới (14). Tổng cộng: 20 ngày
    -- (Có thể đổi thành: generate_series(0, 19) nếu chỉ muốn từ hôm nay trở đi)
    CROSS JOIN generate_series(-5, 14) AS d
    WHERE r.active = true
),
selected_slots AS (
    SELECT rd.room_id,
           rd.room_name,
           rd.buffer_enabled,
           rd.day_offset,
           cs.start_t,
           cs.end_t,
           row_number() OVER (PARTITION BY rd.room_id, rd.day_offset ORDER BY random()) AS rk,
           rd.target_count
    FROM room_days rd
    CROSS JOIN candidate_slots cs
)
INSERT INTO bookings (id, room_id, user_id, title, start_time, end_time, status,
                      buffer_minutes, checked_in_at, created_at, updated_at)
SELECT gen_random_uuid(),
       ss.room_id,
       up.ids[1 + floor(random() * array_length(up.ids, 1))::int],
       '[Seed] ' || t.arr[1 + floor(random() * array_length(t.arr, 1))::int],
       (((now() at time zone 'Asia/Ho_Chi_Minh')::date + ss.day_offset) + ss.start_t) AT TIME ZONE 'Asia/Ho_Chi_Minh',
       (((now() at time zone 'Asia/Ho_Chi_Minh')::date + ss.day_offset) + ss.end_t) AT TIME ZONE 'Asia/Ho_Chi_Minh',
       'ACTIVE',
       CASE WHEN ss.buffer_enabled THEN 15 ELSE 0 END,
       -- Cuộc họp trong quá khứ được đánh dấu đã check-in sau khi bắt đầu 5 phút
       CASE WHEN (((now() at time zone 'Asia/Ho_Chi_Minh')::date + ss.day_offset) + ss.start_t) AT TIME ZONE 'Asia/Ho_Chi_Minh' < now()
            THEN (((now() at time zone 'Asia/Ho_Chi_Minh')::date + ss.day_offset) + ss.start_t) AT TIME ZONE 'Asia/Ho_Chi_Minh' + interval '5 minutes'
            ELSE NULL END,
       now(), now()
FROM selected_slots ss
CROSS JOIN titles t
CROSS JOIN users_pool up
WHERE ss.rk <= ss.target_count
  AND NOT EXISTS (
      -- Phòng ngừa trùng lặp với bất kỳ booking thủ công nào đang có
      SELECT 1 FROM bookings b
      WHERE b.room_id = ss.room_id AND b.status = 'ACTIVE'
        AND b.start_time < (((now() at time zone 'Asia/Ho_Chi_Minh')::date + ss.day_offset) + ss.end_t + interval '15 minutes') AT TIME ZONE 'Asia/Ho_Chi_Minh'
        AND b.end_time + make_interval(mins => b.buffer_minutes) > (((now() at time zone 'Asia/Ho_Chi_Minh')::date + ss.day_offset) + ss.start_t) AT TIME ZONE 'Asia/Ho_Chi_Minh'
  );

-- 4. (Tùy chọn, chỉ khi chạy với -v SEED_SYNC=1) Tự động đưa các booking seed ACTIVE sắp tới
--    của các phòng có Google Calendar vào Outbox để đồng bộ. calendar_outbox không có unique key
--    trên booking_id nên dùng NOT EXISTS để không tạo trùng bản ghi room-calendar (target_user_id IS NULL).
\if :{?SEED_SYNC}
INSERT INTO calendar_outbox (booking_id, action, status, next_attempt_at, attempts, created_at)
SELECT b.id, 'CREATE', 'PENDING', now(), 0, now()
FROM bookings b
JOIN rooms r ON r.id = b.room_id
WHERE r.google_calendar_id IS NOT NULL
  AND b.status = 'ACTIVE'
  AND b.title LIKE '[Seed] %'
  AND b.end_time > now()
  AND NOT EXISTS (
      SELECT 1 FROM calendar_outbox o
      WHERE o.booking_id = b.id AND o.target_user_id IS NULL
  );
\endif

-- Thống kê kết quả sau khi seed:
SELECT count(*) AS total_loadtest_users FROM users WHERE email LIKE 'loadtest%@dki.local';

SELECT min(cnt) AS min_bookings_per_room_day,
       max(cnt) AS max_bookings_per_room_day,
       round(avg(cnt), 2) AS avg_bookings_per_room_day,
       sum(cnt) AS total_seeded_bookings
FROM (
    SELECT room_id, date(start_time AT TIME ZONE 'Asia/Ho_Chi_Minh') AS d, count(*) AS cnt
    FROM bookings
    WHERE status = 'ACTIVE' AND title LIKE '[Seed] %'
    GROUP BY room_id, d
) sub;

SELECT r.name AS room_name,
       count(b.id) AS total_bookings_20_days,
       count(b.id) FILTER (WHERE date(b.start_time AT TIME ZONE 'Asia/Ho_Chi_Minh') = (now() at time zone 'Asia/Ho_Chi_Minh')::date) AS bookings_today
FROM rooms r
LEFT JOIN bookings b ON b.room_id = r.id AND b.status = 'ACTIVE'
WHERE r.active = true
GROUP BY r.name
ORDER BY r.name;
