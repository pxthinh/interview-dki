export default function FlowPage() {
  return (
    <div className="page flow">
      <h1>Luồng hoạt động của hệ thống</h1>
      <p className="sub">
        Giải thích toàn bộ kiến trúc và các luồng chính: đăng nhập, đặt phòng, chống trùng lịch, và đồng bộ Google
        Calendar.
      </p>

      <h2>1. Kiến trúc tổng thể</h2>
      <pre>{`┌──────────────┐   /api/v1 (JWT)    ┌─────────────────────────────┐
│  React SPA   │ ─────────────────▶ │  Spring Boot 3 (Java 17)    │
│ (frontend/)  │ ◀───────────────── │  auth · user · room ·       │
└──────────────┘   JSON / RFC7807   │  booking · calendar         │
                                    └──────┬───────────────┬──────┘
                                           │ JPA/Flyway    │ outbox poll 10s
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
          Mọi lỗi trả về chuẩn RFC 7807 kèm <code>errorCode</code> máy đọc được; lỗi 409 kèm thông tin booking đang
          chiếm chỗ trong trường <code>conflict</code>.
        </li>
        <li>
          Giờ nghiệp vụ là <code>Asia/Ho_Chi_Minh</code>; DB lưu UTC (<code>timestamptz</code>), API nhận/trả ISO-8601
          kèm offset.
        </li>
      </ul>

      <h2>2. Đăng nhập & phân quyền</h2>
      <div className="step">
        <ol>
          <li>
            <code>POST /api/v1/auth/login</code> với email + mật khẩu (BCrypt) → trả <code>accessToken</code> JWT
            HS256, hạn 8 giờ.
          </li>
          <li>
            Mọi request sau gửi <code>Authorization: Bearer &lt;token&gt;</code>. Filter backend{' '}
            <strong>nạp lại user từ DB mỗi request</strong>, nên tài khoản bị khóa mất quyền ngay lập tức dù token còn
            hạn.
          </li>
          <li>
            Hai vai trò: <span className="badge off">USER</span> đặt/sửa/hủy booking của mình, xem lịch, tìm phòng;{' '}
            <span className="badge admin">ADMIN</span> thêm quản lý phòng, người dùng, mọi booking và giám sát calendar
            sync.
          </li>
          <li>Không có self-signup: admin tạo tài khoản với mật khẩu tạm, người dùng tự đổi sau.</li>
        </ol>
      </div>

      <h2>3. Luồng đặt phòng</h2>
      <div className="step">
        <ol>
          <li>
            Người dùng tìm phòng trống: <code>GET /rooms/availability?start&end&minCapacity&amenities</code>. Backend
            lọc phòng active đủ sức chứa, đủ tiện nghi, không có booking ACTIVE chồng khoảng <code>[start, end)</code>;
            kết quả sắp theo sức chứa tăng dần để phòng vừa đủ đứng trước.
          </li>
          <li>
            <code>POST /bookings</code>. Các quy tắc được kiểm theo giờ Việt Nam:
            <ul>
              <li>căn lưới 15 phút (phút 00/15/30/45);</li>
              <li>thời lượng 15 phút – 8 giờ;</li>
              <li>trong giờ làm việc 07:00–20:00, cùng một ngày;</li>
              <li>bắt đầu ở tương lai, không quá 90 ngày tới;</li>
              <li>số người tham dự ≤ sức chứa phòng;</li>
              <li>phòng phải đang hoạt động.</li>
            </ul>
          </li>
          <li>
            Sửa (<code>PUT /bookings/:id</code>) áp lại toàn bộ quy tắc, chỉ chủ booking hoặc admin, khi chưa bắt đầu.
            Không đổi phòng — hủy rồi đặt mới để lịch sử rõ ràng.
          </li>
          <li>
            Hủy (<code>POST /bookings/:id/cancel</code>) là soft-cancel: đổi trạng thái sang CANCELLED, giữ bản ghi,
            slot được giải phóng cho người khác.
          </li>
        </ol>
      </div>

      <h2>4. Chống trùng lịch — 3 lớp bảo vệ</h2>
      <pre>{`Request A ─┐                  ┌─ Lớp 1: SELECT ... FOR UPDATE trên dòng rooms
Request B ─┤ cùng 1 phòng ──▶ │   (xếp hàng tuần tự theo phòng)
Request C ─┘                  ├─ Lớp 2: query overlap [start,end) → 409 + conflict
                              └─ Lớp 3: PostgreSQL EXCLUDE USING gist
                                  (room_id =, tstzrange && WHERE status='ACTIVE')`}</pre>
      <ul>
        <li>
          <strong>Lớp 1</strong>: transaction đặt/sửa mở đầu bằng khóa dòng phòng, nên hai request vào cùng phòng chạy
          tuần tự; phòng khác không bị ảnh hưởng.
        </li>
        <li>
          <strong>Lớp 2</strong>: kiểm tra chồng lịch trong service để trả 409 thân thiện kèm booking đang chiếm chỗ
          (id, tiêu đề, giờ) — frontend hiển thị ngay cho người dùng chọn giờ khác.
        </li>
        <li>
          <strong>Lớp 3</strong>: exclusion constraint ở tầng DB là bảo đảm cuối cùng — kể cả khi code có bug, hai
          booking ACTIVE chồng giờ của cùng phòng không bao giờ tồn tại. Khoảng nửa mở <code>[start, end)</code> nên
          9:00–10:00 và 10:00–11:00 không đụng nhau.
        </li>
        <li>
          Test đồng thời: 10 thread cùng đặt một slot → đúng 1 thành công (201), 9 nhận 409. Booking còn có{' '}
          <code>@Version</code> (optimistic lock): sửa và hủy đồng thời cùng một booking → một bên nhận 409{' '}
          <code>CONCURRENT_MODIFICATION</code>.
        </li>
      </ul>

      <h2>5. Đồng bộ Google Calendar — transactional outbox</h2>
      <pre>{`POST /bookings ──▶ ┌────────── 1 transaction ──────────┐
                   │ INSERT bookings                    │
                   │ INSERT calendar_outbox (PENDING)   │   ← cùng commit, không bao giờ lệch
                   └────────────────────────────────────┘
                                 │
        CalendarOutboxProcessor (poll 10s):
        ① claim: khóa batch PENDING (FOR UPDATE SKIP LOCKED) + đặt lease  [tx ngắn]
        ② gọi Google API                                            [NGOÀI transaction]
        ③ ghi kết quả: DONE / retry backoff 30s×2ⁿ / FAILED sau 6 lần [tx ngắn]
           CREATE thành công → UPDATE bookings SET google_event_id (update có đích)`}</pre>
      <ul>
        <li>
          Mỗi phòng map tới một Google Calendar (<code>rooms.google_calendar_id</code>). Khi admin tạo phòng mới mà
          không truyền calendar id và sync đang bật, hệ thống <strong>tự tạo một calendar mang tên phòng</strong>.
        </li>
        <li>
          Đặt phòng không bao giờ chậm hay fail vì Google: outbox ghi cùng transaction với booking, Google được gọi
          sau, ngoài transaction.
        </li>
        <li>
          Thứ tự CREATE → UPDATE → CANCEL của một booking được giữ (sort theo <code>booking_id, id</code>); booking đã
          hủy trước khi CREATE chạy thì bỏ qua, không gọi Google.
        </li>
        <li>
          Lỗi được retry với backoff lũy tiến; quá 6 lần → FAILED, admin xem và bấm Retry ở trang{' '}
          <strong>Calendar sync</strong>.
        </li>
        <li>
          Tắt sync (<code>GOOGLE_CALENDAR_ENABLED=false</code>, mặc định): hệ thống chạy đầy đủ, không cần credential.
        </li>
      </ul>

      <h2>6. Dữ liệu</h2>
      <pre>{`users ─────────◀ bookings ▶───────── rooms
                    │                  (name, capacity, amenities,
                    ▼                   active, google_calendar_id)
             calendar_outbox
             (action, status, attempts, next_attempt_at)`}</pre>
      <ul>
        <li>Schema quản lý bằng Flyway: V1 schema + exclusion constraint, V2 optimistic lock, V3 seed 10 phòng.</li>
        <li>Phòng không xóa cứng, chỉ tắt (active=false); booking không xóa, chỉ CANCELLED — giữ lịch sử đối soát.</li>
      </ul>

      <h2>7. Công nghệ & kiểm thử</h2>
      <ul>
        <li>Backend: Java 17, Spring Boot 3.5, Spring Security (JWT), JPA/Hibernate, Flyway, PostgreSQL 16.</li>
        <li>Frontend: React 19 + TypeScript + Vite, dev-proxy sang backend nên không cần CORS.</li>
        <li>
          Test: 70+ test gồm unit (quy tắc thời gian, backoff) và integration trên PostgreSQL thật (Testcontainers):
          trùng lịch đồng thời, phân quyền, outbox processor với Google client giả.
        </li>
        <li>
          Chạy toàn bộ bằng <code>docker compose up --build</code>; Swagger UI tại <code>/swagger-ui.html</code>.
        </li>
      </ul>
    </div>
  )
}
