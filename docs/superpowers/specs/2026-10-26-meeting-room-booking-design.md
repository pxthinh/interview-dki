# Thiết kế: Hệ thống đặt phòng họp (Meeting Room Booking API)

Ngày: 2026-10-26
Bối cảnh: Coding test Backend Developer của DKI. Deadline 4 ngày. Stack bắt buộc: Java 17+, Spring Boot 3, PostgreSQL, Docker Compose.

## 1. Mục đích và lý do

Công ty có ~10 phòng họp và ~200 nhân viên. Hiện đặt phòng bằng nhắn tin nên hay trùng lịch.
Cần một REST API làm nguồn sự thật duy nhất về lịch phòng: ai đặt phòng nào, lúc nào, và
tuyệt đối không cho hai booking chồng nhau trên cùng một phòng.

Thêm yêu cầu của người làm bài: mỗi booking được đẩy lên Google Calendar của công ty.
Tài khoản Google của công ty có nhiều lịch con, mỗi lịch con tương ứng một phòng.

Bài làm được đánh giá như một hệ thống sẽ dùng thật, nên ưu tiên: đúng nghiệp vụ, chống
race condition, chạy một lệnh là lên, README giải thích rõ quyết định.

## 2. Mục tiêu, ngoài phạm vi, phản mục tiêu

Mục tiêu
- Quản lý phòng (admin), đặt/sửa/hủy phòng (user), xem lịch, tìm phòng trống.
- Không bao giờ có hai booking ACTIVE chồng thời gian trên cùng phòng, kể cả khi hai
  request đến đồng thời. Dùng khóa `SELECT ... FOR UPDATE` trên dòng phòng trong transaction
  đặt phòng, cộng thêm exclusion constraint ở DB làm lớp bảo đảm cuối.
- Phân quyền USER / ADMIN bằng JWT.
- Đồng bộ booking lên Google Calendar theo phòng, không làm hỏng luồng đặt phòng khi Google lỗi.
- `docker compose up` chạy được toàn bộ, không cần credential Google.
- Test tự động cho nghiệp vụ cốt lõi, đặc biệt là chống trùng lịch.

Ngoài phạm vi (nêu rõ trong README)
- Booking lặp (recurring).
- Luồng duyệt booking (approval).
- Tự đăng ký tài khoản (self-signup). Admin tạo tài khoản.
- Đồng bộ ngược từ Google Calendar về hệ thống.
- Giao diện người dùng.

Phản mục tiêu (làm được nhưng vẫn coi là thất bại)
- Trùng lịch lọt qua dưới tải đồng thời.
- Đặt phòng thất bại hoặc chậm vì Google API lỗi/chậm.
- Reviewer không chạy được vì thiếu cấu hình.

## 3. Ràng buộc

- Java 17+ (máy dev có Java 21; target bytecode 17), Spring Boot 3.x, PostgreSQL 16, Maven.
- Múi giờ nghiệp vụ: `Asia/Ho_Chi_Minh`. API nhận và trả thời gian ISO-8601 kèm offset;
  DB lưu `timestamptz`.
- Giữ nguyên lịch sử commit; commit nhỏ, message rõ.

## 4. Các hướng tiếp cận đã cân nhắc

A. Monolith phân lớp theo domain, đồng bộ Calendar qua bảng outbox + job nền. (ĐÃ CHỌN)
   - Booking và bản ghi outbox ghi trong cùng transaction. Scheduler đọc outbox, gọi Google,
     retry có backoff. Booking không phụ thuộc Google. Không mất sự kiện khi app restart.
   - Đánh đổi: thêm một bảng và một job; sự kiện lên Google trễ vài giây.
B. Monolith, gọi Google API đồng bộ trong request.
   - Ít code nhất. Nhưng request chậm, phụ thuộc Google, lỗi mạng gây mất sự kiện hoặc phải
     rollback booking. Không hợp "dùng thật".
C. Hai service (booking, calendar-sync) qua message queue.
   - Thừa cho quy mô 10 phòng/200 người, làm docker compose phức tạp, không thêm giá trị.

Lý do chọn A: đúng với tiêu chí "dùng thật" mà vẫn gọn trong 4 ngày.

## 5. Kiến trúc

Một ứng dụng Spring Boot, package gốc `com.dki.roombooking`, chia theo domain:

```
auth/      login, JWT, security config
user/      entity User, admin tạo user
room/      entity Room, CRUD admin, tìm phòng trống
booking/   entity Booking, tạo/sửa/hủy, lịch, kiểm tra quy tắc
calendar/  outbox, scheduler, GoogleCalendarClient (interface) + 2 impl: Google thật, NoOp
common/    exception handler, error response, time utils
```

Mỗi domain: `controller` -> `service` -> `repository` (Spring Data JPA). DTO tách khỏi entity.
Migration bằng Flyway. OpenAPI (springdoc) tại `/swagger-ui.html`.

Thư viện: spring-boot-starter-web, -data-jpa, -security, -validation, -actuator, flyway,
postgresql driver, jjwt, springdoc-openapi, google-api-client + google-api-services-calendar,
lombok (tùy builder), testcontainers (test).

## 6. Mô hình dữ liệu

users
- id uuid pk, email unique not null, password_hash, full_name, role (USER|ADMIN),
  active bool, created_at, updated_at.

rooms
- id uuid pk, name unique not null, location (vd "Tầng 3"), capacity int > 0,
  amenities text[] (vd projector, whiteboard), active bool default true,
  google_calendar_id text null, created_at, updated_at.

bookings
- id uuid pk, room_id fk rooms, user_id fk users (người đặt), title not null,
  description null, attendee_count int null, start_time timestamptz, end_time timestamptz,
  status (ACTIVE|CANCELLED), cancelled_at null, google_event_id text null,
  created_at, updated_at.
- check end_time > start_time.
- Exclusion constraint (cần extension btree_gist):
  `EXCLUDE USING gist (room_id WITH =, tstzrange(start_time, end_time, '[)') WITH &&)
   WHERE (status = 'ACTIVE')`
  Đây là lớp bảo vệ cuối cùng chống trùng lịch dưới mọi tải đồng thời. Service vẫn kiểm tra
  trước để trả lỗi đẹp, nhưng ràng buộc DB mới là thứ bảo đảm.
- index (room_id, start_time), index (user_id, start_time).

calendar_outbox
- id bigserial pk, booking_id fk, action (CREATE|UPDATE|CANCEL), status
  (PENDING|DONE|FAILED), attempts int default 0, next_attempt_at timestamptz,
  last_error text null, created_at, processed_at null.
- Thứ tự xử lý theo booking_id rồi id để UPDATE không chạy trước CREATE.

Seed (Flyway V2__seed): 1 admin `admin@dki.local`, 3 user, 5 phòng mẫu.
Mật khẩu seed ghi trong README. Mật khẩu hash BCrypt.

## 7. Quy tắc nghiệp vụ

Đặt phòng
- Phòng phải `active`.
- `start_time` phải ở tương lai (so với giờ server).
- `end_time > start_time`. Thời lượng 15 phút đến 8 giờ.
- Căn theo lưới 15 phút (phút thuộc {0,15,30,45}, giây = 0) theo múi giờ VN.
- Nằm trong giờ làm việc 07:00–20:00 giờ VN, cùng một ngày (không qua đêm).
- Không đặt xa hơn 90 ngày kể từ hôm nay.
- Nếu gửi `attendee_count` thì phải <= `capacity` của phòng.
- Không chồng với booking ACTIVE khác của cùng phòng. Chồng được định nghĩa nửa mở `[start, end)`:
  booking 9:00–10:00 và 10:00–11:00 không chồng.
- Trùng lịch trả HTTP 409 kèm thông tin booking đang chiếm (id, start, end, title) để
  người dùng biết chọn giờ khác.

Sửa booking
- Chỉ chủ booking hoặc ADMIN. Chỉ khi status ACTIVE và chưa bắt đầu.
- Được đổi: title, description, attendee_count, start_time, end_time. Không đổi phòng
  (muốn đổi phòng thì hủy rồi đặt mới, để lịch sử rõ ràng).
- Áp lại toàn bộ quy tắc như khi tạo; khi kiểm tra chồng lịch bỏ qua chính nó.

Hủy booking
- Chỉ chủ booking hoặc ADMIN. Chỉ khi ACTIVE. Cho hủy cả khi đang diễn ra (giải phóng phòng),
  không cho hủy booking đã kết thúc.
- Đổi status sang CANCELLED, ghi cancelled_at. Không xóa bản ghi.

Phòng
- ADMIN tạo/sửa. Tên phòng duy nhất.
- Vô hiệu hóa (`active=false`) thay vì xóa. Phòng inactive: không nhận booking mới, booking
  cũ ACTIVE vẫn giữ (admin tự xử lý). Không có endpoint xóa cứng.

Người dùng
- ADMIN tạo user (email, full_name, password tạm, role). Không self-signup.
- User tự đổi mật khẩu. Ngoài phạm vi các thứ khác.

Xem lịch
- Lịch một phòng trong khoảng ngày (mặc định hôm nay, tối đa 31 ngày).
- Lịch toàn bộ phòng trong một ngày (cho dashboard).
- Booking của tôi (upcoming mặc định, có thể xem lịch sử).
- ADMIN liệt kê mọi booking với filter room, user, khoảng thời gian, status, phân trang.

Tìm phòng trống
- Input: start, end, optional minCapacity, optional amenities.
- Output: các phòng active thỏa điều kiện và không có booking ACTIVE chồng `[start,end)`.
- Sắp theo capacity tăng dần (phòng vừa đủ lên trước).

## 8. API

Prefix `/api/v1`. JSON. Lỗi theo RFC 7807 ProblemDetail của Spring (`type`, `title`, `status`,
`detail`, thêm `errors[]` cho validation, `conflict` cho 409).

Auth
- POST /auth/login {email,password} -> {accessToken, expiresIn, user}
- GET  /auth/me

Users (ADMIN trừ khi ghi khác)
- POST /users
- GET  /users?page&size
- GET  /users/{id}
- PATCH /users/{id} (active, role, fullName)
- POST /users/me/password (mọi user đã đăng nhập)

Rooms
- GET  /rooms?active=true  (mọi user)
- GET  /rooms/{id}
- POST /rooms   (ADMIN)
- PUT  /rooms/{id}  (ADMIN)
- PATCH /rooms/{id}/status {active}  (ADMIN)
- GET  /rooms/availability?start&end&minCapacity&amenities   (tìm phòng trống)
- GET  /rooms/{id}/schedule?from&to   (lịch một phòng)

Bookings
- POST /bookings
- GET  /bookings/{id}   (chủ hoặc ADMIN; user khác nhận 403)
- PUT  /bookings/{id}
- POST /bookings/{id}/cancel
- GET  /bookings/me?from&to&status
- GET  /bookings?roomId&userId&from&to&status&page&size   (ADMIN)
- GET  /schedule?date   (lịch mọi phòng trong một ngày; mọi user)

Admin calendar
- GET  /admin/calendar-outbox?status
- POST /admin/calendar-outbox/{id}/retry

Thời gian: request/response dùng `OffsetDateTime` ISO-8601 (`2026-10-27T09:00:00+07:00`).

JWT: HS256, secret từ env `JWT_SECRET`, hết hạn 8h (env `JWT_EXPIRATION`). Không refresh token
(ngoài phạm vi, ghi README).

## 9. Đồng bộ Google Calendar

Mô hình: một tài khoản Google Workspace của công ty có nhiều lịch con; mỗi phòng map tới một
`google_calendar_id`. Backend dùng service account (JSON key) đã được share quyền
"Make changes to events" trên từng lịch phòng.

Luồng
1. Tạo/sửa/hủy booking -> trong cùng transaction insert `calendar_outbox` (action tương ứng).
   Chỉ insert nếu phòng có `google_calendar_id` và `GOOGLE_CALENDAR_ENABLED=true`.
2. `CalendarOutboxProcessor` chạy mỗi 10s (`@Scheduled`, `SELECT ... FOR UPDATE SKIP LOCKED`),
   lấy tối đa 20 bản ghi PENDING có `next_attempt_at <= now`, theo thứ tự booking_id, id.
3. Với mỗi bản ghi gọi `GoogleCalendarClient`:
   - CREATE: tạo event (summary = title, description gồm người đặt + mô tả, location = tên
     phòng, start/end theo VN). Lưu `google_event_id` vào booking.
     Không gắn `attendees` mặc định: service account thường bị Google từ chối
     (403 `forbiddenForServiceAccounts`) nếu không có Domain-Wide Delegation. Email người đặt
     ghi trong description. Cờ `GOOGLE_CALENDAR_INVITE_BOOKER=true` (mặc định false) bật
     attendees cho môi trường đã có DWD và `GOOGLE_IMPERSONATE_USER`.
   - UPDATE: patch event theo `google_event_id`. Nếu chưa có id (CREATE chưa xong) thì
     để lại PENDING, xử lý ở vòng sau.
   - CANCEL: delete event theo `google_event_id`. 404/410 từ Google coi là DONE.
4. Thành công -> DONE, processed_at. Lỗi -> attempts+1, backoff 30s * 2^attempts, tối đa 6 lần
   rồi FAILED, lưu `last_error`. Admin xem và retry qua API admin calendar.
5. Google không bao giờ được gọi trong transaction đặt phòng.

Cấu hình
- `GOOGLE_CALENDAR_ENABLED` (default false)
- `GOOGLE_SERVICE_ACCOUNT_KEY_PATH` (đường dẫn file JSON, mount qua docker volume)
- `GOOGLE_CALENDAR_INVITE_BOOKER` (default false), `GOOGLE_IMPERSONATE_USER` (chỉ khi có DWD)
- Khi disabled: bean `NoOpGoogleCalendarClient`, outbox không được ghi, app chạy bình thường.
  README hướng dẫn bật: tạo service account, share lịch phòng, set `google_calendar_id` qua
  API phòng.

## 10. Chống trùng lịch: lớp bảo vệ

1. Transaction đặt/sửa booking mở đầu bằng `SELECT ... FOR UPDATE` trên dòng `rooms` của phòng
   đó (`@Lock(PESSIMISTIC_WRITE)`). Các request vào cùng phòng được xếp hàng tuần tự; phòng
   khác không bị ảnh hưởng. Với 10 phòng, chi phí khóa không đáng kể.
2. Sau khi giữ khóa, service kiểm tra chồng lịch và trả 409 kèm chi tiết booking đang chiếm.
3. Exclusion constraint PostgreSQL là lớp bảo đảm cuối, phòng trường hợp ghi vào DB ngoài
   luồng service (script, migration, bug). Service bắt `DataIntegrityViolationException` có
   tên constraint và chuyển thành 409.
4. Test đồng thời: bắn N thread cùng đặt một slot, đúng một cái 201, còn lại 409.

## 11. Bảo mật

- Spring Security stateless, JWT filter. BCrypt.
- Endpoint public: /auth/login, /actuator/health, swagger.
- Method security `@PreAuthorize` cho ADMIN. Kiểm tra chủ booking trong service.
- Không log mật khẩu, không trả password_hash.
- CORS: mở cho dev, cấu hình qua env.

## 12. Docker

- `docker-compose.yml`: service `db` (postgres:16-alpine, volume, healthcheck) và `app`
  (build từ Dockerfile multi-stage: maven build -> eclipse-temurin:17-jre). `app` depends_on
  `db` condition healthy. Port 8080.
- `.env.example` liệt kê mọi biến. Compose có default để `docker compose up` chạy không cần
  sửa gì.
- Flyway chạy migration khi app start.

## 13. Kiểm thử

- Unit: BookingRuleValidator (mọi quy tắc ở mục 7), tính toán overlap, backoff outbox.
- Integration (Testcontainers PostgreSQL): tạo booking, 409 khi chồng, exclusion constraint
  thật sự chặn khi đồng thời, tìm phòng trống, phân quyền 401/403, outbox processor với
  GoogleCalendarClient giả.
- Không test Google thật trong CI.

## 14. README phải có

- Cách chạy (docker compose, chạy local, chạy test).
- Tài khoản seed.
- Tổng quan kiến trúc + sơ đồ ERD (mermaid).
- Danh sách API và ví dụ curl cho luồng chính: login, tìm phòng trống, đặt, bị 409, hủy.
- Các quyết định và lý do: exclusion constraint, outbox, soft-cancel, no-recurring,
  giờ làm việc, múi giờ, JWT không refresh.
- Hướng dẫn bật Google Calendar.
- Những gì ngoài phạm vi và hướng mở rộng.

## 15. Để builder tự quyết

- Tên chính xác của class, cấu trúc DTO, mapper (MapStruct hay tay).
- Format phân trang (dùng Spring `Page` chuẩn).
- Chi tiết message lỗi, mã lỗi.
- Cách tổ chức test fixture.
- Có dùng Lombok hay record cho DTO (gợi ý: record).
