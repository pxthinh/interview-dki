export default function FlowPage() {
  return (
    <div className="page flow">
      <h1>Luồng hoạt động của hệ thống</h1>
      <p className="sub">
        Kiến trúc và các luồng chính: đăng nhập, chọn giờ, đặt phòng, người tham gia & email, chống trùng lịch, và
        đồng bộ hai chiều với Google Calendar.
      </p>

      <h2>1. Kiến trúc tổng thể</h2>
      <pre>{`┌──────────────┐   /api/v1 (JWT)    ┌─────────────────────────────┐
│  React SPA   │ ─────────────────▶ │  Spring Boot 3 (Java 17)    │
│ (nginx :3000 │ ◀───────────────── │  auth · user · room ·       │
│  proxy /api) │   JSON / RFC7807   │  booking · calendar         │
└──────────────┘                    └──┬─────────┬─────────┬──────┘
                                       │JPA      │outbox10s│ mail (async)
                                       │Flyway   │import30s│
                                       ▼         ▼         ▼
                                 ┌──────────┐ ┌─────────┐ ┌──────────────┐
                                 │PostgreSQL│ │ Google  │ │ SMTP         │
                                 │    16    │ │Calendar │ │ Mailpit/Gmail│
                                 └──────────┘ └─────────┘ └──────────────┘`}</pre>
      <ul>
        <li>
          Backend chia theo domain: <code>auth</code>, <code>user</code>, <code>room</code>, <code>booking</code>,{' '}
          <code>calendar</code>, <code>common</code>; mỗi domain controller → service → repository, DTO là record.
        </li>
        <li>
          Lỗi chuẩn RFC 7807 kèm <code>errorCode</code>; lỗi 409 kèm chi tiết booking đang chiếm chỗ trong trường{' '}
          <code>conflict</code>.
        </li>
        <li>
          Giờ nghiệp vụ <code>Asia/Ho_Chi_Minh</code>; DB lưu UTC (<code>timestamptz</code>), API nhận/trả ISO-8601
          kèm offset.
        </li>
        <li>
          <strong>DB là nguồn sự thật duy nhất</strong> về phòng trống/bận; Google Calendar là mặt hiển thị và điểm
          nhập liệu phụ; email là kênh thông báo best-effort.
        </li>
      </ul>

      <h2>2. Đăng nhập & phân quyền</h2>
      <div className="step">
        <ol>
          <li>
            <code>POST /auth/login</code> (email + mật khẩu BCrypt) → JWT HS256 hạn 8 giờ.
          </li>
          <li>
            Filter backend <strong>nạp lại user từ DB mỗi request</strong>: tài khoản bị khóa mất quyền ngay dù token
            còn hạn.
          </li>
          <li>
            <span className="badge off">USER</span> đặt/sửa/hủy booking của mình, xem lịch, tìm phòng, xem danh bạ.{' '}
            <span className="badge admin">ADMIN</span> thêm: quản lý phòng, người dùng, mọi booking, giám sát sync.
          </li>
          <li>Không self-signup: admin cấp tài khoản. Mỗi phòng có một admin phòng (mặc định là người tạo phòng).</li>
        </ol>
      </div>

      <h2>3. Chọn giờ & đặt phòng</h2>
      <div className="step">
        <ol>
          <li>
            UI không cho gõ giờ tự do. <code>GET /rooms/time-slots?date&minCapacity&amenities</code> trả danh sách{' '}
            <strong>giờ bắt đầu</strong> hợp lệ (lưới 15 phút, 07:00–19:45) kèm số phòng trống; truyền thêm{' '}
            <code>start</code> trả danh sách <strong>giờ kết thúc</strong> (tối đa 8 giờ, không quá 20:00). Mốc giờ mà
            mọi phòng khớp bộ lọc đều kín <strong>không được trả về</strong>.
          </li>
          <li>
            Chọn xong giờ, <code>GET /rooms/availability?start&end&minCapacity&amenities</code> trả các phòng trống,
            sắp theo sức chứa tăng dần (phòng vừa đủ đứng trước).
          </li>
          <li>
            Trong form đặt có thể <strong>mời người tham gia</strong>: combobox tìm theo tên/email trên danh bạ{' '}
            <code>GET /users/directory</code>, Enter/click để thêm, chip × để bỏ.
          </li>
          <li>
            <code>POST /bookings</code> kiểm lại toàn bộ quy tắc theo giờ Việt Nam:
            <ul>
              <li>căn lưới 15 phút; dài 15 phút – 8 giờ;</li>
              <li>trong 07:00–20:00, cùng một ngày; bắt đầu ở tương lai, tối đa 90 ngày tới;</li>
              <li>số người tham dự ≤ sức chứa; phòng đang hoạt động;</li>
              <li>
                <strong>buffer time</strong> — giờ nghỉ 15 phút giữa hai booking cùng phòng, bật/tắt theo từng phòng
                (admin chỉnh trong form phòng). Timeline dashboard vẽ dải sọc cho phần buffer.
              </li>
              <li>
                người tham gia phải là user active, không tính người đặt; id sai → 422{' '}
                <code>PARTICIPANT_NOT_FOUND</code>.
              </li>
            </ul>
          </li>
          <li>
            Sửa booking (<code>PUT /bookings/:id</code>): chỉ chủ booking hoặc admin, khi còn ACTIVE và chưa bắt đầu;
            form sửa dùng đúng picker như lúc tạo, giới hạn theo phòng của booking và <code>excludeBookingId</code> để
            giờ hiện tại của chính nó vẫn chọn được. Không đổi phòng.
          </li>
          <li>
            Hủy (<code>POST /bookings/:id/cancel</code>) là soft-cancel: bản ghi giữ CANCELLED, slot giải phóng.
          </li>
        </ol>
      </div>

      <h2>4. Email thông báo</h2>
      <pre>{`Tạo booking ──▶ người đặt:   "Đặt phòng thành công"
            └─▶ người mời:   "Mời họp"
Sửa booking ──▶ người thêm:  "Mời họp"
            ├─▶ người bị bỏ: "Bạn được rút khỏi cuộc họp"
            └─▶ người giữ:   "Cập nhật họp" (giờ mới)
Hủy booking ──▶ mọi người tham gia: "HỦY họp"`}</pre>
      <ul>
        <li>
          Gửi <strong>async, best-effort</strong>: SMTP chết chỉ sinh log warn, không bao giờ chặn hay làm chậm đặt
          phòng; health check SMTP tắt để mail server chết không kéo <code>/actuator/health</code> DOWN.
        </li>
        <li>
          SMTP cấu hình qua env <code>MAIL_*</code>: mặc định compose dùng <strong>Mailpit</strong> (hộp thư demo tại{' '}
          <code>http://localhost:8025</code>); trỏ sang Gmail thật bằng <code>MAIL_HOST=smtp.gmail.com</code> +
          app password trong <code>.env</code> (không commit).
        </li>
      </ul>

      <h2>5. Chống trùng lịch — 3 lớp bảo vệ</h2>
      <pre>{`Request A ─┐                  ┌─ Lớp 1: SELECT ... FOR UPDATE trên dòng rooms
Request B ─┤ cùng 1 phòng ──▶ │   (xếp hàng tuần tự theo phòng)
Request C ─┘                  ├─ Lớp 2: check busy-range từng booking trong service
                              └─ Lớp 3: EXCLUDE USING gist trên
                                  booking_busy_range(start, end, buffer_minutes)
                                  WHERE status='ACTIVE'`}</pre>
      <ul>
        <li>
          Busy range của mỗi booking là <code>[start, end + buffer riêng)</code>; mỗi booking chụp lại buffer của
          phòng tại thời điểm đặt (<code>buffer_minutes</code> 0/15) nên tắt buffer về sau không ảnh hưởng booking cũ.
        </li>
        <li>
          Lớp 3 là bảo đảm cuối ở tầng DB: kể cả khi code có bug, hai booking ACTIVE vi phạm buffer của cùng phòng
          không bao giờ tồn tại. Test 10 thread cùng đặt một slot → đúng 1 thành công, 9 bị 409.
        </li>
        <li>
          Booking có <code>@Version</code>: sửa và hủy đồng thời cùng một booking → một bên 409{' '}
          <code>CONCURRENT_MODIFICATION</code>.
        </li>
      </ul>

      <h2>6. Đồng bộ Google Calendar — hai chiều</h2>
      <h3>6a. Chiều đi (DB → Google): transactional outbox, 10s/lần</h3>
      <pre>{`POST /bookings ──▶ ┌────────── 1 transaction ──────────┐
                   │ INSERT bookings                    │
                   │ INSERT calendar_outbox (PENDING)   │  ← cùng commit
                   └────────────────────────────────────┘
        Processor: ① claim batch (FOR UPDATE SKIP LOCKED + lease)   [tx ngắn]
                   ② gọi Google API                           [NGOÀI transaction]
                   ③ ghi kết quả: DONE / backoff 30s×2ⁿ / FAILED sau 6 lần [tx ngắn]`}</pre>
      <ul>
        <li>Đặt phòng không bao giờ chậm/fail vì Google; app restart không mất sự kiện.</li>
        <li>Thứ tự CREATE → UPDATE → CANCEL của một booking được giữ; FAILED có trang admin xem và Retry.</li>
        <li>
          Tạo phòng mới khi sync bật mà không truyền calendar id → hệ thống <strong>tự tạo Google Calendar</strong>{' '}
          mang tên phòng.
        </li>
      </ul>

      <h3>6b. Chiều về (Google → DB): incremental import, 30s/lần</h3>
      <pre>{`Mỗi phòng: events.list(syncToken) ──▶ áp thay đổi [tx ngắn] ──▶ lưu nextSyncToken
  · sự kiện mới, slot trống    → booking ACTIVE
  · sự kiện bị xóa trên Google → booking CANCELLED
  · sự kiện dời giờ            → dời booking nếu khung mới trống
  · sự kiện LẤN GIỜ            → KHÔNG giữ phòng (DB thắng):
        - Google: tiêu đề bị đổi "[TRÙNG LỊCH - KHÔNG HỢP LỆ] ..." + lý do
        - DB:    lưu booking CANCELLED cùng nhãn để tra cứu trong app`}</pre>
      <ul>
        <li>
          Người đặt của booking import: email creator khớp user → user đó; không khớp →{' '}
          <strong>admin của phòng</strong>; không có → user hệ thống <code>calendar-import@dki.local</code>.
        </li>
        <li>
          Cursor là <code>syncToken</code> của Google (lưu ở <code>rooms.google_sync_token</code>; lần đầu lấy toàn
          bộ sự kiện từ hiện tại; token hết hạn 410 → tự full resync).
        </li>
        <li>
          Import ghi thẳng repository, không qua outbox → không có vòng lặp echo; sự kiện do chính app đẩy lên quay về
          sẽ no-op vì <code>google_event_id</code> đã khớp. Nhãn từ chối là guard chống gắn lặp.
        </li>
        <li>Sự kiện cả ngày bỏ qua. Dùng polling vì webhook push cần HTTPS public.</li>
        <li>
          Tắt sync (<code>GOOGLE_CALENDAR_ENABLED=false</code>, mặc định): hệ thống chạy đầy đủ, không cần credential.
        </li>
      </ul>

      <h2>7. Dữ liệu</h2>
      <pre>{`users ──◀ bookings ▶── rooms (admin_user_id → users)
   ▲          │  ▲        (capacity, amenities, active, buffer_enabled,
   │          │  └─────    google_calendar_id, google_sync_token)
   │          ▼
   └── booking_participants   calendar_outbox
       (người được mời)       (action, status, attempts, next_attempt_at)`}</pre>
      <ul>
        <li>
          Flyway: V1 schema + exclusion constraint · V2 optimistic lock · V3 seed 10 phòng · V4 buffer trong
          constraint · V5 sync token · V6 admin phòng · V7 buffer bật/tắt theo phòng · V8 người tham gia.
        </li>
        <li>Phòng không xóa cứng (chỉ tắt), booking không xóa (chỉ CANCELLED) — giữ lịch sử đối soát.</li>
      </ul>

      <h2>8. Công nghệ & kiểm thử</h2>
      <ul>
        <li>
          Backend: Java 17, Spring Boot 3.5, Spring Security (JWT), JPA/Hibernate, Flyway, PostgreSQL 16, spring-mail.
        </li>
        <li>Frontend: React 19 + TypeScript + Vite; chạy production qua nginx trong compose (port 3000).</li>
        <li>
          100+ test: unit (quy tắc thời gian, backoff) và integration trên PostgreSQL thật (Testcontainers) — trùng
          lịch đồng thời, buffer theo phòng, time-slots, phân quyền, outbox processor, import hai chiều, người tham
          gia + email (mail sender giả bắt thư).
        </li>
        <li>
          Chạy toàn bộ bằng <code>docker compose up --build</code>: db + app + web + mailpit. Swagger UI tại{' '}
          <code>/swagger-ui.html</code>.
        </li>
      </ul>
    </div>
  )
}
