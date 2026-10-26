export default function FlowPage() {
  return (
    <div className="page flow">
      <h1>Luồng hoạt động của hệ thống</h1>
      <p className="sub">
        Kiến trúc và các luồng chính: đăng nhập, chọn giờ, đặt phòng, chống trùng lịch, và đồng bộ hai chiều với
        Google Calendar.
      </p>

      <h2>1. Kiến trúc tổng thể</h2>
      <pre>{`┌──────────────┐   /api/v1 (JWT)    ┌─────────────────────────────┐
│  React SPA   │ ─────────────────▶ │  Spring Boot 3 (Java 17)    │
│ (frontend/)  │ ◀───────────────── │  auth · user · room ·       │
└──────────────┘   JSON / RFC7807   │  booking · calendar         │
                                    └──────┬───────────────┬──────┘
                                           │ JPA/Flyway    │ outbox 10s · import 30s
                                           ▼               ▼
                                    ┌────────────┐  ┌──────────────────┐
                                    │ PostgreSQL │  │ Google Calendar  │
                                    │  16        │  │ (1 lịch / phòng) │
                                    └────────────┘  └──────────────────┘`}</pre>
      <ul>
        <li>
          Backend chia theo domain: <code>auth</code>, <code>user</code>, <code>room</code>, <code>booking</code>,{' '}
          <code>calendar</code>, <code>common</code>; mỗi domain controller → service → repository.
        </li>
        <li>
          Lỗi trả về chuẩn RFC 7807 kèm <code>errorCode</code>; lỗi 409 kèm chi tiết booking đang chiếm chỗ trong
          trường <code>conflict</code>.
        </li>
        <li>
          Giờ nghiệp vụ là <code>Asia/Ho_Chi_Minh</code>; DB lưu UTC (<code>timestamptz</code>), API nhận/trả ISO-8601
          kèm offset.
        </li>
        <li>
          <strong>DB là nguồn sự thật duy nhất</strong> về việc phòng trống hay bận; Google Calendar là mặt hiển thị
          và điểm nhập liệu phụ.
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
            <span className="badge off">USER</span> đặt/sửa/hủy booking của mình, xem lịch, tìm phòng.{' '}
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
            <code>POST /bookings</code> kiểm lại toàn bộ quy tắc theo giờ Việt Nam:
            <ul>
              <li>căn lưới 15 phút; dài 15 phút – 8 giờ;</li>
              <li>trong 07:00–20:00, cùng một ngày; bắt đầu ở tương lai, tối đa 90 ngày tới;</li>
              <li>số người tham dự ≤ sức chứa; phòng đang hoạt động;</li>
              <li>
                <strong>giờ nghỉ 15 phút (buffer time)</strong> giữa hai booking cùng phòng — 10:00 ngay sau
                9:00–10:00 bị 409, 10:15 hợp lệ. Buffer bật/tắt theo từng phòng (admin chỉnh trong form phòng);
                mỗi booking chụp lại buffer lúc đặt nên constraint DB vẫn bảo đảm.
              </li>
            </ul>
          </li>
          <li>
            Sửa booking (<code>PUT /bookings/:id</code>): chỉ chủ booking hoặc admin, khi còn ACTIVE và chưa bắt đầu;
            form sửa dùng đúng picker như lúc tạo, giới hạn theo phòng của booking và <code>excludeBookingId</code> để
            giờ hiện tại của chính nó vẫn chọn được. Không đổi phòng.
          </li>
          <li>
            Hủy (<code>POST /bookings/:id/cancel</code>) là soft-cancel: bản ghi giữ lại trạng thái CANCELLED, slot
            được giải phóng.
          </li>
          <li>
            <strong>Người tham gia & email</strong>: khi đặt có thể mời đồng nghiệp (danh bạ{' '}
            <code>GET /users/directory</code>). Hệ thống gửi email async, best-effort: thư mời khi được thêm, thư
            báo hủy khi cuộc họp bị hủy, thư cập nhật khi đổi giờ, thư rút khi bị bỏ khỏi danh sách. SMTP demo là
            Mailpit (http://localhost:8025); mail chết không chặn đặt phòng.
          </li>
        </ol>
      </div>

      <h2>4. Chống trùng lịch — 3 lớp bảo vệ</h2>
      <pre>{`Request A ─┐                  ┌─ Lớp 1: SELECT ... FOR UPDATE trên dòng rooms
Request B ─┤ cùng 1 phòng ──▶ │   (xếp hàng tuần tự theo phòng)
Request C ─┘                  ├─ Lớp 2: query overlap [start-15', end+15') → 409 + conflict
                              └─ Lớp 3: EXCLUDE USING gist trên range nới end +15'
                                  (booking_busy_range, WHERE status='ACTIVE')`}</pre>
      <ul>
        <li>
          <strong>Lớp 1</strong>: khóa dòng phòng khiến các request vào cùng phòng chạy tuần tự; phòng khác song song.
        </li>
        <li>
          <strong>Lớp 2</strong>: kiểm tra chồng lấn trong service (đã cộng giờ nghỉ 15 phút hai phía) để trả 409 kèm
          booking đang chiếm chỗ.
        </li>
        <li>
          <strong>Lớp 3</strong>: exclusion constraint của PostgreSQL là bảo đảm cuối — kể cả khi code có bug, hai
          booking ACTIVE gần nhau hơn 15 phút của cùng phòng không bao giờ tồn tại trong DB.
        </li>
        <li>
          Booking có <code>@Version</code> (optimistic lock): sửa và hủy đồng thời cùng một booking → một bên nhận 409{' '}
          <code>CONCURRENT_MODIFICATION</code>. Test đồng thời: 10 thread cùng đặt một slot → đúng 1 thành công.
        </li>
      </ul>

      <h2>5. Đồng bộ Google Calendar — hai chiều</h2>
      <h3>5a. Chiều đi (DB → Google): transactional outbox, 10s/lần</h3>
      <pre>{`POST /bookings ──▶ ┌────────── 1 transaction ──────────┐
                   │ INSERT bookings                    │
                   │ INSERT calendar_outbox (PENDING)   │  ← cùng commit
                   └────────────────────────────────────┘
        Processor: ① claim batch (FOR UPDATE SKIP LOCKED + lease)   [tx ngắn]
                   ② gọi Google API                           [NGOÀI transaction]
                   ③ ghi kết quả: DONE / backoff 30s×2ⁿ / FAILED sau 6 lần [tx ngắn]`}</pre>
      <ul>
        <li>Đặt phòng không bao giờ chậm hay fail vì Google; app restart không mất sự kiện.</li>
        <li>Thứ tự CREATE → UPDATE → CANCEL của một booking được giữ; FAILED có trang admin xem và Retry.</li>
        <li>
          Tạo phòng mới khi sync bật mà không truyền calendar id → hệ thống <strong>tự tạo Google Calendar</strong>{' '}
          mang tên phòng.
        </li>
      </ul>

      <h3>5b. Chiều về (Google → DB): incremental import, 30s/lần</h3>
      <pre>{`Mỗi phòng: events.list(syncToken) ──▶ áp thay đổi [tx ngắn] ──▶ lưu nextSyncToken
  · sự kiện mới, slot trống    → booking ACTIVE
  · sự kiện bị xóa trên Google → booking CANCELLED
  · sự kiện dời giờ            → dời booking nếu khung mới trống
  · sự kiện LẤN GIỜ            → KHÔNG giữ phòng (DB thắng):
        - Google: tiêu đề bị đổi "[TRÙNG LỊCH - KHÔNG HỢP LỆ] ..." + lý do
        - DB:    lưu booking CANCELLED cùng nhãn để tra cứu trong app`}</pre>
      <ul>
        <li>
          Người đặt của booking import xác định theo thứ tự: email người tạo sự kiện khớp user trong hệ thống →{' '}
          <strong>admin của phòng</strong> → user hệ thống <code>calendar-import@dki.local</code>.
        </li>
        <li>
          Lần đầu import lấy toàn bộ sự kiện từ thời điểm hiện tại; các lần sau chỉ lấy thay đổi qua{' '}
          <code>syncToken</code> (lưu ở <code>rooms.google_sync_token</code>, hết hạn 410 thì tự full resync).
        </li>
        <li>
          Import ghi thẳng repository, không qua outbox → không có vòng lặp echo; sự kiện do chính app đẩy lên quay về
          trong feed sẽ no-op vì <code>google_event_id</code> đã khớp. Nhãn từ chối cũng là guard chống gắn lặp.
        </li>
        <li>Sự kiện cả ngày (không có giờ cụ thể) bỏ qua. Dùng polling vì webhook push cần HTTPS public.</li>
        <li>
          Tắt sync (<code>GOOGLE_CALENDAR_ENABLED=false</code>, mặc định): hệ thống chạy đầy đủ, không cần credential.
        </li>
      </ul>

      <h2>6. Dữ liệu</h2>
      <pre>{`users ─────────◀ bookings ▶───────── rooms (admin_user_id → users)
                    │                  (capacity, amenities, active,
                    ▼                   google_calendar_id, google_sync_token)
             calendar_outbox
             (action, status, attempts, next_attempt_at)`}</pre>
      <ul>
        <li>
          Flyway: V1 schema + exclusion constraint · V2 optimistic lock · V3 seed 10 phòng · V4 giờ nghỉ 15 phút
          trong constraint · V5 sync token · V6 admin phòng.
        </li>
        <li>Phòng không xóa cứng (chỉ tắt), booking không xóa (chỉ CANCELLED) — giữ lịch sử đối soát.</li>
      </ul>

      <h2>7. Công nghệ & kiểm thử</h2>
      <ul>
        <li>Backend: Java 17, Spring Boot 3.5, Spring Security (JWT), JPA/Hibernate, Flyway, PostgreSQL 16.</li>
        <li>Frontend: React 19 + TypeScript + Vite, dev-proxy sang backend nên không cần CORS.</li>
        <li>
          90+ test: unit (quy tắc thời gian, backoff) và integration trên PostgreSQL thật (Testcontainers) — trùng
          lịch đồng thời, giờ nghỉ 15 phút, time-slots, phân quyền, outbox processor và import hai chiều với Google
          client giả.
        </li>
        <li>
          Chạy toàn bộ bằng <code>docker compose up --build</code>; Swagger UI tại <code>/swagger-ui.html</code>.
        </li>
      </ul>
    </div>
  )
}
