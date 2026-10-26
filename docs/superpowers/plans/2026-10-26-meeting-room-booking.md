# Meeting Room Booking API — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** REST API đặt phòng họp (Spring Boot 3, PostgreSQL) chống trùng lịch tuyệt đối, phân quyền USER/ADMIN, đồng bộ booking lên Google Calendar theo phòng qua outbox, chạy bằng `docker compose up`.

**Architecture:** Monolith một module Maven, package theo domain (`auth`, `user`, `room`, `booking`, `calendar`, `common`). Mỗi domain `controller -> service -> repository`. Chống trùng lịch 3 lớp: khóa `FOR UPDATE` dòng phòng, kiểm tra overlap trong service, exclusion constraint PostgreSQL. Calendar sync qua bảng `calendar_outbox` + `@Scheduled` processor, Google không bao giờ được gọi trong transaction đặt phòng.

**Tech Stack:** Java 17 (bytecode), Spring Boot 3.3.5, Spring Security + jjwt 0.12.6, Spring Data JPA (Hibernate 6), Flyway 10 (`flyway-database-postgresql`), PostgreSQL 16, springdoc-openapi 2.6.0, google-api-services-calendar `v3-rev20240927-2.0.0` + google-auth-library-oauth2-http 1.28.0, Lombok (entity only), Testcontainers (spring-boot-testcontainers), JUnit 5, Maven.

**Spec:** `docs/superpowers/specs/2026-10-26-meeting-room-booking-design.md`

## Global Constraints

- `maven.compiler.release` = 17. Máy dev có JDK 21, build phải chạy trên JDK 17 trong Docker.
- Package gốc `com.dki.roombooking`.
- Múi giờ nghiệp vụ `Asia/Ho_Chi_Minh` (hằng `BusinessTime.ZONE`). Entity lưu `Instant`; DTO dùng `OffsetDateTime`, response luôn trả offset `+07:00`.
- API prefix `/api/v1`. Lỗi theo RFC 7807 `ProblemDetail`, có property `errorCode`; 409 có thêm property `conflict`.
- Quy tắc booking: thời lượng 15 phút..8 giờ, lưới 15 phút, 07:00–20:00 VN cùng ngày, tối đa 90 ngày tới, `[start,end)` nửa mở.
- JWT HS256, env `JWT_SECRET`, `JWT_EXPIRATION` (mặc định `8h`). Không refresh token.
- `GOOGLE_CALENDAR_ENABLED` mặc định `false`; `docker compose up` không cần credential.
- DTO là `record`. Entity dùng Lombok `@Getter @Setter`. Không MapStruct, map tay trong `static from(...)`.
- Mỗi task kết thúc bằng `mvn -q test` xanh và một commit. Commit message tiếng Anh, Conventional Commits.
- Test tích hợp kế thừa `AbstractIntegrationTest` (Testcontainers `postgres:16-alpine`, `@ServiceConnection`). Docker Desktop phải chạy khi test.

## Review Focus

1. Request với `startTime` offset khác `+07:00` (vd `Z`): phải quy về giờ VN trước khi kiểm tra lưới/giờ làm việc. Test ở Task 7 (`validator_acceptsUtcOffsetInput`).
2. Hai booking kề nhau 09:00–10:00 và 10:00–11:00 cùng phòng: không được 409. Test Task 7 (`create_adjacentBookings_bothSucceed`).
3. Sửa booking giữ nguyên giờ của chính nó: không được tự xung đột với chính mình. Test Task 8 (`update_sameSlot_succeeds`).
4. Token của user đã bị `active=false`: phải nhận 401 ở request kế tiếp. Test Task 4 (`deactivatedUser_tokenRejected`).
5. Outbox nhận `CANCEL` khi `CREATE` chưa thành công (chưa có `google_event_id`): CANCEL phải DONE mà không gọi Google, và CREATE còn lại phải bị bỏ (booking đã CANCELLED). Test Task 9 (`processor_cancelBeforeCreateFinished_skipsBoth`).

---

### Task 1: Project skeleton, config, Docker

**Files:**
- Create: `pom.xml`, `src/main/java/com/dki/roombooking/RoomBookingApplication.java`, `src/main/resources/application.yml`, `src/test/resources/application-test.yml`, `src/test/java/com/dki/roombooking/AbstractIntegrationTest.java`, `src/test/java/com/dki/roombooking/ApplicationStartsTest.java`, `Dockerfile`, `docker-compose.yml`, `.env.example`, `.dockerignore`, `.gitignore` (bổ sung), `.gitattributes`
- Create: `src/main/java/com/dki/roombooking/common/config/ClockConfig.java`

**Interfaces:**
- Produces: `AbstractIntegrationTest` (`@SpringBootTest(webEnvironment = RANDOM_PORT)`, `@AutoConfigureMockMvc`, `@ActiveProfiles("test")`, static `PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")` với `@ServiceConnection`, `@Autowired protected MockMvc mockMvc; @Autowired protected ObjectMapper objectMapper;`).
- Produces: bean `Clock` (`Clock.systemUTC()`), mọi service nhận giờ qua bean này.

- [ ] **Step 1: Viết `pom.xml`**

Parent `spring-boot-starter-parent:3.3.5`. Dependencies: `spring-boot-starter-web`, `-data-jpa`, `-security`, `-validation`, `-actuator`, `flyway-core`, `flyway-database-postgresql`, `postgresql` (runtime), `io.jsonwebtoken:jjwt-api/impl/jackson:0.12.6`, `org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0`, `com.google.apis:google-api-services-calendar:v3-rev20240927-2.0.0`, `com.google.auth:google-auth-library-oauth2-http:1.28.0`, `lombok` (optional), test: `spring-boot-starter-test`, `spring-security-test`, `spring-boot-testcontainers`, `org.testcontainers:postgresql`, `org.testcontainers:junit-jupiter`. Property `<maven.compiler.release>17</maven.compiler.release>`. Plugin `spring-boot-maven-plugin`.

- [ ] **Step 2: Viết `application.yml`**

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/roombooking}
    username: ${DB_USERNAME:roombooking}
    password: ${DB_PASSWORD:roombooking}
  jpa:
    open-in-view: false
    hibernate.ddl-auto: validate
  flyway.enabled: true
  data.web.pageable.serialization-mode: via_dto
app:
  jwt:
    secret: ${JWT_SECRET:change-me-change-me-change-me-change-me-32b}
    expiration: ${JWT_EXPIRATION:8h}
  seed.enabled: ${SEED_ENABLED:true}
  calendar:
    enabled: ${GOOGLE_CALENDAR_ENABLED:false}
    service-account-key-path: ${GOOGLE_SERVICE_ACCOUNT_KEY_PATH:}
    invite-booker: ${GOOGLE_CALENDAR_INVITE_BOOKER:false}
    impersonate-user: ${GOOGLE_IMPERSONATE_USER:}
    poll-interval-ms: 10000
    batch-size: 20
    max-attempts: 6
server.port: ${SERVER_PORT:8080}
```

`application-test.yml`: `app.calendar.poll-interval-ms: 3600000` (processor không tự chạy trong test), `logging.level.org.hibernate.SQL: warn`.

- [ ] **Step 3: Viết `RoomBookingApplication` (`@SpringBootApplication @EnableScheduling @ConfigurationPropertiesScan`) và `ClockConfig`**

- [ ] **Step 4: Viết `AbstractIntegrationTest` và `ApplicationStartsTest`**

```java
class ApplicationStartsTest extends AbstractIntegrationTest {
    @Test void healthIsUp() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
```

Tạm thời security chưa có nên health public. Hibernate `ddl-auto: validate` với 0 entity và 0 migration vẫn start được.

- [ ] **Step 5: Chạy `mvn -q test`** — Expected: BUILD SUCCESS, 1 test pass.

- [ ] **Step 6: Viết `Dockerfile`, `docker-compose.yml`, `.env.example`, `.dockerignore`, `.gitattributes`**

Dockerfile: stage 1 `maven:3.9-eclipse-temurin-17` copy `pom.xml`, `mvn -q dependency:go-offline`, copy `src`, `mvn -q package -DskipTests`; stage 2 `eclipse-temurin:17-jre-alpine`, user non-root, `COPY --from=build /app/target/*.jar app.jar`, `ENTRYPOINT ["java","-jar","/app.jar"]`.

Compose: service `db` image `postgres:16-alpine`, env `POSTGRES_DB/USER/PASSWORD=roombooking`, volume `pgdata`, healthcheck `pg_isready -U roombooking -d roombooking` interval 5s; service `app` build `.`, `depends_on: db: condition: service_healthy`, ports `8080:8080`, env `DB_URL=jdbc:postgresql://db:5432/roombooking`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET=${JWT_SECRET:-change-me-change-me-change-me-change-me-32b}`, `GOOGLE_CALENDAR_ENABLED=${GOOGLE_CALENDAR_ENABLED:-false}`, `GOOGLE_SERVICE_ACCOUNT_KEY_PATH=/secrets/google-sa.json`, volume `./secrets:/secrets:ro`.

`.gitattributes`: `* text=auto eol=lf`. `.gitignore` thêm `target/`, `secrets/`, `.env`.

- [ ] **Step 7: Chạy `docker compose build`** — Expected: image build thành công (chưa cần `up`, app chưa có schema).

- [ ] **Step 8: Commit** — `chore: bootstrap spring boot project with docker compose`

---

### Task 2: Database schema (Flyway)

**Files:**
- Create: `src/main/resources/db/migration/V1__init_schema.sql`
- Test: `src/test/java/com/dki/roombooking/db/SchemaConstraintsTest.java`

**Interfaces:**
- Produces: bảng `users`, `rooms`, `bookings`, `calendar_outbox` đúng mục 6 của spec; tên exclusion constraint `bookings_no_overlap`.

- [ ] **Step 1: Viết test**

```java
class SchemaConstraintsTest extends AbstractIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Test void overlappingActiveBookings_rejectedByExclusionConstraint() {
        UUID user = insertUser(); UUID room = insertRoom();
        insertBooking(room, user, "2026-11-03T02:00:00Z", "2026-11-03T03:00:00Z", "ACTIVE");
        assertThatThrownBy(() -> insertBooking(room, user, "2026-11-03T02:30:00Z", "2026-11-03T03:30:00Z", "ACTIVE"))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("bookings_no_overlap");
    }
    @Test void adjacentAndCancelled_allowed() {
        // 02:00-03:00 ACTIVE, 03:00-04:00 ACTIVE ok; 02:00-03:00 CANCELLED thứ hai cũng ok
    }
}
```

Helper insert bằng JDBC thuần (chưa có entity).

- [ ] **Step 2: Chạy test** — Expected: FAIL (`relation "users" does not exist`).

- [ ] **Step 3: Viết `V1__init_schema.sql`**

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE TABLE users (id uuid PRIMARY KEY, email varchar(255) NOT NULL UNIQUE, password_hash varchar(100) NOT NULL,
  full_name varchar(255) NOT NULL, role varchar(20) NOT NULL CHECK (role IN ('USER','ADMIN')),
  active boolean NOT NULL DEFAULT true, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL);
CREATE TABLE rooms (id uuid PRIMARY KEY, name varchar(100) NOT NULL UNIQUE, location varchar(255),
  capacity int NOT NULL CHECK (capacity > 0), amenities text[] NOT NULL DEFAULT '{}',
  active boolean NOT NULL DEFAULT true, google_calendar_id varchar(255),
  created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL);
CREATE TABLE bookings (id uuid PRIMARY KEY, room_id uuid NOT NULL REFERENCES rooms(id),
  user_id uuid NOT NULL REFERENCES users(id), title varchar(200) NOT NULL, description text,
  attendee_count int, start_time timestamptz NOT NULL, end_time timestamptz NOT NULL,
  status varchar(20) NOT NULL CHECK (status IN ('ACTIVE','CANCELLED')), cancelled_at timestamptz,
  google_event_id varchar(255), created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
  CONSTRAINT bookings_end_after_start CHECK (end_time > start_time),
  CONSTRAINT bookings_no_overlap EXCLUDE USING gist
    (room_id WITH =, tstzrange(start_time, end_time, '[)') WITH &&) WHERE (status = 'ACTIVE'));
CREATE INDEX idx_bookings_room_start ON bookings(room_id, start_time);
CREATE INDEX idx_bookings_user_start ON bookings(user_id, start_time);
CREATE TABLE calendar_outbox (id bigserial PRIMARY KEY, booking_id uuid NOT NULL REFERENCES bookings(id),
  action varchar(20) NOT NULL CHECK (action IN ('CREATE','UPDATE','CANCEL')),
  status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','DONE','FAILED')),
  attempts int NOT NULL DEFAULT 0, next_attempt_at timestamptz NOT NULL, last_error text,
  created_at timestamptz NOT NULL, processed_at timestamptz);
CREATE INDEX idx_outbox_pending ON calendar_outbox(status, next_attempt_at);
```

- [ ] **Step 4: Chạy test** — Expected: PASS.

- [ ] **Step 5: Commit** — `feat(db): initial schema with booking exclusion constraint`

---

### Task 3: Common layer (errors, time)

**Files:**
- Create: `common/time/BusinessTime.java`, `common/error/ResourceNotFoundException.java`, `common/error/BusinessRuleException.java`, `common/error/BookingConflictException.java`, `common/error/ConflictInfo.java`, `common/error/GlobalExceptionHandler.java`
- Test: `src/test/java/com/dki/roombooking/common/time/BusinessTimeTest.java`

**Interfaces:**
- Produces:
  - `BusinessTime.ZONE = ZoneId.of("Asia/Ho_Chi_Minh")`; `static OffsetDateTime toApi(Instant)`; `static ZonedDateTime toBusiness(Instant)`; `static LocalDate today(Clock)`.
  - `ResourceNotFoundException(String message)` → 404, `errorCode = "NOT_FOUND"`.
  - `BusinessRuleException(String errorCode, String message)` → 422.
  - `ConflictInfo(UUID bookingId, String title, OffsetDateTime startTime, OffsetDateTime endTime)`.
  - `BookingConflictException(ConflictInfo conflict)` (conflict có thể null) → 409, `errorCode = "BOOKING_CONFLICT"`, property `conflict`.
  - Handler: `MethodArgumentNotValidException` → 400, `errorCode="VALIDATION_ERROR"`, property `errors: [{field, message}]`; `AccessDeniedException` → 403; `AuthenticationException` → 401 (xử lý ở Task 4 entry point); `DataIntegrityViolationException` có message chứa `bookings_no_overlap` → 409 `BookingConflictException(null)`; còn lại 500 không lộ stack.

- [ ] **Step 1: Viết `BusinessTimeTest`**: `toApi(Instant.parse("2026-11-03T02:00:00Z"))` có `getOffset() == ZoneOffset.ofHours(7)` và `getHour() == 9`.

- [ ] **Step 2: Chạy** — FAIL. **Step 3: Implement.** **Step 4: Chạy** — PASS.

- [ ] **Step 5: Commit** — `feat(common): business time helpers and problem-detail error handling`

---

### Task 4: User entity, JWT auth, security

**Files:**
- Create: `user/User.java`, `user/Role.java`, `user/UserRepository.java`
- Create: `auth/JwtProperties.java`, `auth/JwtService.java`, `auth/AuthUser.java`, `auth/JwtAuthenticationFilter.java`, `auth/SecurityConfig.java`, `auth/AuthService.java`, `auth/AuthController.java`, `auth/dto/LoginRequest.java`, `auth/dto/LoginResponse.java`
- Create: `user/UserResponse.java` (dùng chung), `common/config/SeedDataRunner.java`
- Test: `auth/JwtServiceTest.java`, `auth/AuthControllerTest.java`, `src/test/java/com/dki/roombooking/TestAuth.java`

**Interfaces:**
- Produces:
  - `User` (fields: `UUID id`, `String email`, `String passwordHash`, `String fullName`, `Role role`, `boolean active`, `Instant createdAt`, `Instant updatedAt`; `@PrePersist/@PreUpdate` set timestamps, id = `UUID.randomUUID()` nếu null). `enum Role { USER, ADMIN }`.
  - `UserRepository extends JpaRepository<User, UUID>`: `Optional<User> findByEmailIgnoreCase(String)`, `boolean existsByEmailIgnoreCase(String)`.
  - `record AuthUser(UUID id, String email, Role role)` — principal trong `SecurityContext`; authorities `ROLE_<role>`. `boolean isAdmin()`.
  - `JwtService(JwtProperties, Clock)`: `String generate(User user)`, `UUID parseSubject(String token)` (throw `JwtException` nếu sai/hết hạn).
  - `JwtProperties` `@ConfigurationProperties("app.jwt")` record `(String secret, Duration expiration)`.
  - `SecurityConfig`: stateless, permit `/api/v1/auth/login`, `/actuator/health`, `/swagger-ui/**`, `/v3/api-docs/**`; còn lại authenticated; `@EnableMethodSecurity`; 401 entry point trả ProblemDetail `errorCode="UNAUTHORIZED"`; bean `PasswordEncoder` BCrypt.
  - `JwtAuthenticationFilter`: đọc `Authorization: Bearer`, parse, **load user từ DB**, nếu không tồn tại hoặc `active=false` thì không set authentication (→ 401).
  - `AuthService.login(LoginRequest) -> LoginResponse`; sai mật khẩu/user inactive → `BadCredentialsException` → 401 `errorCode="BAD_CREDENTIALS"`.
  - `record LoginRequest(@Email @NotBlank String email, @NotBlank String password)`; `record LoginResponse(String accessToken, long expiresInSeconds, UserResponse user)`; `record UserResponse(UUID id, String email, String fullName, Role role, boolean active, OffsetDateTime createdAt)` với `static UserResponse from(User)`.
  - `GET /api/v1/auth/me` → `UserResponse`.
  - `SeedDataRunner implements ApplicationRunner` (`@ConditionalOnProperty("app.seed.enabled")`): nếu `userRepository.count()==0` tạo `admin@dki.local / Admin@123` (ADMIN), `alice@dki.local`, `bob@dki.local`, `carol@dki.local` / `User@123` (USER). Phòng seed thêm ở Task 6.
  - Test helper `TestAuth`: `static String token(MockMvc, ObjectMapper, String email, String password)` gọi login thật; hằng `ADMIN="admin@dki.local"`, `ALICE`, `BOB`, `PASSWORD_ADMIN`, `PASSWORD_USER`.

- [ ] **Step 1: Viết `JwtServiceTest`** (unit, fixed clock): generate rồi parse trả đúng id; token tạo với clock lùi 9h → `ExpiredJwtException`.

- [ ] **Step 2: Viết `AuthControllerTest`** (integration)
  - `login_ok_returnsTokenAndUser`: 200, `accessToken` non-empty, `user.email == admin@dki.local`.
  - `login_wrongPassword_401`: body `errorCode == "BAD_CREDENTIALS"`.
  - `me_withoutToken_401`, `me_withToken_200`.
  - `deactivatedUser_tokenRejected`: tạo user qua repository, lấy token, set `active=false` save, gọi `/auth/me` → 401.

- [ ] **Step 3: Chạy** — FAIL. **Step 4: Implement toàn bộ file trên.** **Step 5: Chạy `mvn -q test`** — PASS (kể cả `ApplicationStartsTest` vì health vẫn public).

- [ ] **Step 6: Commit** — `feat(auth): jwt login, security config, seeded accounts`

---

### Task 5: User management (admin)

**Files:**
- Create: `user/UserService.java`, `user/UserController.java`, `user/dto/CreateUserRequest.java`, `user/dto/UpdateUserRequest.java`, `user/dto/ChangePasswordRequest.java`
- Test: `user/UserControllerTest.java`

**Interfaces:**
- Produces:
  - `record CreateUserRequest(@Email @NotBlank String email, @NotBlank @Size(max=255) String fullName, @NotBlank @Size(min=8,max=72) String password, @NotNull Role role)`
  - `record UpdateUserRequest(@Size(max=255) String fullName, Role role, Boolean active)` — chỉ áp field non-null.
  - `record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank @Size(min=8,max=72) String newPassword)`
  - `UserService`: `UserResponse create(CreateUserRequest)` (email trùng → `BusinessRuleException("EMAIL_TAKEN", ...)`), `Page<UserResponse> list(Pageable)`, `UserResponse get(UUID)`, `UserResponse update(UUID, UpdateUserRequest)`, `void changePassword(AuthUser actor, ChangePasswordRequest)` (sai currentPassword → `BusinessRuleException("WRONG_PASSWORD", ...)`).
  - Endpoints theo spec mục 8; `POST/GET/PATCH /users**` có `@PreAuthorize("hasRole('ADMIN')")`, `POST /users/me/password` mọi user đã đăng nhập. Create trả 201.

- [ ] **Step 1: Viết `UserControllerTest`**: `admin_createUser_201`, `user_createUser_403`, `createDuplicateEmail_422_EMAIL_TAKEN`, `admin_deactivateUser_thenLogin401`, `changePassword_thenLoginWithNew_200`.
- [ ] **Step 2: FAIL. Step 3: Implement. Step 4: PASS.**
- [ ] **Step 5: Commit** — `feat(user): admin user management and password change`

---

### Task 6: Room management

**Files:**
- Create: `room/Room.java`, `room/RoomRepository.java`, `room/RoomService.java`, `room/RoomController.java`, `room/dto/RoomRequest.java`, `room/dto/RoomStatusRequest.java`, `room/dto/RoomResponse.java`
- Modify: `common/config/SeedDataRunner.java` (thêm 5 phòng)
- Test: `room/RoomControllerTest.java`

**Interfaces:**
- Produces:
  - `Room` (`UUID id`, `String name`, `String location`, `int capacity`, `List<String> amenities` với `@JdbcTypeCode(SqlTypes.ARRAY) @Column(columnDefinition="text[]")`, `boolean active`, `String googleCalendarId`, timestamps).
  - `RoomRepository`: `List<Room> findByActiveTrueOrderByCapacityAscNameAsc()`, `List<Room> findAllByOrderByNameAsc()`, `boolean existsByNameIgnoreCase(String)`, và
    ```java
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Room r where r.id = :id")
    Optional<Room> findByIdForUpdate(@Param("id") UUID id);
    ```
  - `record RoomRequest(@NotBlank @Size(max=100) String name, @Size(max=255) String location, @Min(1) int capacity, List<String> amenities, @Size(max=255) String googleCalendarId)`; `record RoomStatusRequest(@NotNull Boolean active)`; `record RoomResponse(UUID id, String name, String location, int capacity, List<String> amenities, boolean active, String googleCalendarId, OffsetDateTime createdAt)` + `static from(Room)`.
  - `RoomService`: `RoomResponse create(RoomRequest)` (tên trùng → `BusinessRuleException("ROOM_NAME_TAKEN")`), `RoomResponse update(UUID, RoomRequest)`, `RoomResponse setActive(UUID, boolean)`, `RoomResponse get(UUID)`, `List<RoomResponse> list(Boolean active)` (null → tất cả), `Room requireActiveRoom(UUID)` (404 nếu không có, `BusinessRuleException("ROOM_INACTIVE")` nếu inactive). Availability search thêm ở Task 8.
  - Endpoints spec mục 8 (`POST`, `PUT`, `PATCH /rooms/{id}/status` ADMIN). Seed 5 phòng: `Mercury`(4, Tầng 2, [tv]), `Venus`(6, Tầng 2, [tv,whiteboard]), `Earth`(8, Tầng 3, [projector,whiteboard]), `Mars`(12, Tầng 3, [projector,whiteboard,video-conference]), `Jupiter`(20, Tầng 5, [projector,video-conference]).

- [ ] **Step 1: Viết `RoomControllerTest`**: `user_listRooms_200_seededFive`, `user_createRoom_403`, `admin_createRoom_201`, `admin_duplicateName_422`, `admin_deactivate_thenListActiveExcludesIt`.
- [ ] **Step 2: FAIL. Step 3: Implement. Step 4: PASS.**
- [ ] **Step 5: Commit** — `feat(room): room CRUD, soft deactivate, seeded rooms`

---

### Task 7: Booking core — rules, create with lock, conflict 409

**Files:**
- Create: `booking/Booking.java`, `booking/BookingStatus.java`, `booking/BookingRepository.java`, `booking/BookingRuleValidator.java`, `booking/BookingService.java`, `booking/BookingController.java`, `booking/dto/CreateBookingRequest.java`, `booking/dto/BookingResponse.java`
- Test: `booking/BookingRuleValidatorTest.java` (unit), `booking/BookingCreateTest.java`, `booking/BookingConcurrencyTest.java`, `src/test/java/com/dki/roombooking/TestData.java`

**Interfaces:**
- Produces:
  - `Booking` (`UUID id`, `@ManyToOne(fetch=LAZY) Room room`, `@ManyToOne(fetch=LAZY) User user`, `String title`, `String description`, `Integer attendeeCount`, `Instant startTime`, `Instant endTime`, `@Enumerated(STRING) BookingStatus status`, `Instant cancelledAt`, `String googleEventId`, timestamps). `enum BookingStatus { ACTIVE, CANCELLED }`.
  - `BookingRepository`:
    ```java
    @Query("""
      select b from Booking b where b.room.id = :roomId and b.status = 'ACTIVE'
        and b.startTime < :end and b.endTime > :start
        and (:excludeId is null or b.id <> :excludeId)
      order by b.startTime""")
    List<Booking> findActiveOverlapping(UUID roomId, Instant start, Instant end, UUID excludeId);
    ```
  - `BookingRuleValidator(Clock clock)`: `void validateTimes(Instant start, Instant end)` ném `BusinessRuleException` với errorCode theo thứ tự kiểm tra: `END_BEFORE_START`, `NOT_ON_15_MIN_GRID`, `DURATION_TOO_SHORT` (<15m), `DURATION_TOO_LONG` (>8h), `START_IN_PAST`, `OUTSIDE_WORKING_HOURS` (start < 07:00 VN, end > 20:00 VN, hoặc khác ngày VN), `TOO_FAR_AHEAD` (ngày VN của start > today(VN)+90). `void validateCapacity(Integer attendeeCount, int capacity)` → `EXCEEDS_CAPACITY`.
  - `record CreateBookingRequest(@NotNull UUID roomId, @NotBlank @Size(max=200) String title, @Size(max=2000) String description, @Positive Integer attendeeCount, @NotNull OffsetDateTime startTime, @NotNull OffsetDateTime endTime)`
  - `record BookingResponse(UUID id, UUID roomId, String roomName, UUID userId, String userEmail, String title, String description, Integer attendeeCount, OffsetDateTime startTime, OffsetDateTime endTime, BookingStatus status, OffsetDateTime cancelledAt, String googleEventId, OffsetDateTime createdAt)` + `static from(Booking)` (room/user phải được fetch; service dùng `@Transactional` và map trong transaction).
  - `BookingService` (`@Transactional`): `BookingResponse create(AuthUser actor, CreateBookingRequest req)`. Thứ tự: `roomRepository.findByIdForUpdate` (404 nếu không có; inactive → `ROOM_INACTIVE`) → `validateTimes` → `validateCapacity` → `findActiveOverlapping(roomId, start, end, null)`; nếu có → `BookingConflictException(ConflictInfo.from(first))` → save → `calendarSync.enqueue(booking, CREATE)` (Task 9; ở task này gọi qua interface `CalendarSyncPort` với impl no-op: `interface CalendarSyncPort { void enqueue(Booking, OutboxAction); }` đặt trong `booking/`; `enum OutboxAction { CREATE, UPDATE, CANCEL }` đặt trong `calendar/`). `BookingResponse get(AuthUser actor, UUID id)` (không phải chủ và không admin → `AccessDeniedException`).
  - Controller: `POST /api/v1/bookings` → 201 + `Location`; `GET /api/v1/bookings/{id}`.
  - `TestData`: `static OffsetDateTime at(LocalDate date, int hour, int minute)` tại VN; `static LocalDate nextWeekday()` (ngày mai trở đi, bỏ T7/CN để khỏi nhầm, dù rule không cấm cuối tuần); `static UUID roomId(MockMvc, token, "Earth")`.

- [ ] **Step 1: Viết `BookingRuleValidatorTest`** (fixed clock `2026-10-26T01:00:00Z` = 08:00 VN 26/10)
  - `valid_9to10_ok`, `endBeforeStart`, `notOnGrid_9h05`, `tooShort_10min`, `tooLong_8h15`, `startInPast_yesterday`, `before7am`, `after8pm_endAt2015`, `crossesMidnight`, `tooFarAhead_day91_rejected_day90_ok`, `validator_acceptsUtcOffsetInput` (start `2026-10-27T02:00Z`, end `03:00Z` → ok vì 09:00–10:00 VN), `capacity_exceeds`.

- [ ] **Step 2: Viết `BookingCreateTest`**
  - `create_201_returnsBookingWithPlus7Offset` (startTime string kết thúc `+07:00`).
  - `create_overlapping_409_withConflictInfo` (body `errorCode == BOOKING_CONFLICT`, `conflict.bookingId` == id đầu).
  - `create_adjacentBookings_bothSucceed` (09:00–10:00 và 10:00–11:00).
  - `create_inactiveRoom_422_ROOM_INACTIVE`, `create_unknownRoom_404`, `create_invalidBody_400_withErrors`.
  - `get_otherUsersBooking_403`, `get_asAdmin_200`.

- [ ] **Step 3: Viết `BookingConcurrencyTest`** (service-level): 10 thread `Executors.newFixedThreadPool(10)` + `CountDownLatch` cùng gọi `bookingService.create(alice, sameSlot)`; đếm: đúng 1 thành công, 9 ném `BookingConflictException`; `bookingRepository.count()==1`.

- [ ] **Step 4: Chạy** — FAIL. **Step 5: Implement.** **Step 6: Chạy `mvn -q test`** — PASS.

- [ ] **Step 7: Commit** — `feat(booking): create booking with row lock, rule validation and 409 conflicts`

---

### Task 8: Booking update/cancel, schedules, my bookings, admin search, availability

**Files:**
- Modify: `booking/BookingService.java`, `booking/BookingController.java`, `booking/BookingRepository.java`, `room/RoomService.java`, `room/RoomController.java`
- Create: `booking/dto/UpdateBookingRequest.java`, `booking/dto/RoomScheduleResponse.java`, `booking/dto/BookingSearchFilter.java`, `booking/ScheduleController.java`, `room/dto/AvailabilityQuery.java`
- Test: `booking/BookingUpdateCancelTest.java`, `booking/BookingQueryTest.java`, `room/RoomAvailabilityTest.java`

**Interfaces:**
- Produces:
  - `record UpdateBookingRequest(@NotBlank @Size(max=200) String title, @Size(max=2000) String description, @Positive Integer attendeeCount, @NotNull OffsetDateTime startTime, @NotNull OffsetDateTime endTime)`
  - `BookingService.update(AuthUser actor, UUID id, UpdateBookingRequest)`: chủ hoặc admin; status phải ACTIVE (`BOOKING_NOT_ACTIVE`), chưa bắt đầu (`BOOKING_ALREADY_STARTED`); khóa phòng `findByIdForUpdate`; validate lại; overlap với `excludeId = id`; enqueue `UPDATE`.
  - `BookingService.cancel(AuthUser, UUID)`: ACTIVE (`BOOKING_NOT_ACTIVE`), `endTime > now` (`BOOKING_ALREADY_ENDED`); set CANCELLED + `cancelledAt`; enqueue `CANCEL`.
  - `BookingService.myBookings(AuthUser, OffsetDateTime from, OffsetDateTime to, BookingStatus status)`: mặc định `from = now`, `to = null` (không giới hạn), `status = ACTIVE`; sắp theo `startTime`.
  - `record BookingSearchFilter(UUID roomId, UUID userId, OffsetDateTime from, OffsetDateTime to, BookingStatus status)`; `Page<BookingResponse> search(BookingSearchFilter, Pageable)` (admin) — JPQL một query với điều kiện `:x is null or ...`.
  - `List<BookingResponse> roomSchedule(UUID roomId, LocalDate from, LocalDate to)`: mặc định cả hai = today VN; `to < from` → 400 (`IllegalArgumentException` → handler 400 `errorCode="INVALID_RANGE"`); `to - from > 30 ngày` → `INVALID_RANGE`; chỉ ACTIVE trong `[from 00:00 VN, to+1 00:00 VN)`.
  - `record RoomScheduleResponse(RoomResponse room, List<BookingResponse> bookings)`; `List<RoomScheduleResponse> daySchedule(LocalDate date)`: mọi phòng active, kể cả phòng không có booking.
  - Repository thêm: `findByRoomIdAndStatusAndStartTimeLessThanAndEndTimeGreaterThanOrderByStartTime(UUID, BookingStatus, Instant to, Instant from)`, `findByStatusAndStartTimeLessThanAndEndTimeGreaterThanOrderByStartTime(...)`, query search.
  - `record AvailabilityQuery(@NotNull OffsetDateTime start, @NotNull OffsetDateTime end, @Min(1) Integer minCapacity, List<String> amenities)`; `RoomService.findAvailable(AvailabilityQuery) -> List<RoomResponse>`: validate `end > start` (400 `INVALID_RANGE`), lấy phòng active capacity >= min, lọc amenities (phòng phải chứa tất cả), loại phòng có `findActiveOverlapping` không rỗng, sắp capacity tăng dần.
  - Endpoints: `PUT /bookings/{id}`, `POST /bookings/{id}/cancel`, `GET /bookings/me`, `GET /bookings` (ADMIN, `Pageable` mặc định size 20), `GET /rooms/{id}/schedule`, `GET /schedule?date`, `GET /rooms/availability`.

- [ ] **Step 1: Viết `BookingUpdateCancelTest`**: `update_sameSlot_succeeds`, `update_intoOtherBooking_409`, `update_byOtherUser_403`, `update_byAdmin_200`, `cancel_200_statusCancelled`, `cancel_twice_422_BOOKING_NOT_ACTIVE`, `cancelled_slotCanBeRebooked_201`.
- [ ] **Step 2: Viết `BookingQueryTest`**: `me_defaultsToUpcomingActive`, `roomSchedule_rangeOver31Days_400`, `daySchedule_includesRoomsWithoutBookings`, `adminSearch_filterByRoom_paged`, `user_adminSearch_403`.
- [ ] **Step 3: Viết `RoomAvailabilityTest`**: `availability_excludesBookedRoom`, `availability_minCapacity_andAmenities_filter`, `availability_sortedByCapacityAsc`, `availability_endBeforeStart_400`.
- [ ] **Step 4: FAIL. Step 5: Implement. Step 6: `mvn -q test` PASS.**
- [ ] **Step 7: Commit** — `feat(booking): update, cancel, schedules, my bookings, admin search, room availability`

---

### Task 9: Google Calendar outbox sync

**Files:**
- Create: `calendar/CalendarProperties.java`, `calendar/CalendarOutbox.java`, `calendar/OutboxStatus.java`, `calendar/CalendarOutboxRepository.java`, `calendar/CalendarSyncService.java` (implements `CalendarSyncPort`), `calendar/CalendarEvent.java`, `calendar/GoogleCalendarClient.java`, `calendar/CalendarEventNotFoundException.java`, `calendar/NoOpGoogleCalendarClient.java`, `calendar/GoogleCalendarClientImpl.java`, `calendar/CalendarOutboxProcessor.java`, `calendar/CalendarOutboxAdminController.java`, `calendar/dto/OutboxResponse.java`
- Delete: no-op `CalendarSyncPort` impl tạm từ Task 7 (thay bằng `CalendarSyncService`).
- Test: `calendar/CalendarOutboxProcessorTest.java`, `calendar/FakeGoogleCalendarClient.java` (test), `calendar/BackoffTest.java`

**Interfaces:**
- Produces:
  - `CalendarProperties` `@ConfigurationProperties("app.calendar")` record `(boolean enabled, String serviceAccountKeyPath, boolean inviteBooker, String impersonateUser, long pollIntervalMs, int batchSize, int maxAttempts)`.
  - `CalendarOutbox` (`Long id`, `UUID bookingId`, `OutboxAction action`, `OutboxStatus status`, `int attempts`, `Instant nextAttemptAt`, `String lastError`, `Instant createdAt`, `Instant processedAt`). `enum OutboxStatus { PENDING, DONE, FAILED }`.
  - `CalendarOutboxRepository`: native `@Query(value = "select * from calendar_outbox where status = 'PENDING' and next_attempt_at <= :now order by booking_id, id limit :limit for update skip locked", nativeQuery = true) List<CalendarOutbox> lockPendingBatch(Instant now, int limit)`; `List<CalendarOutbox> findByStatusOrderByIdDesc(OutboxStatus)`; `List<CalendarOutbox> findByBookingIdOrderById(UUID)`.
  - `CalendarSyncService.enqueue(Booking booking, OutboxAction action)`: no-op nếu `!enabled` hoặc `booking.getRoom().getGoogleCalendarId() == null`; ngược lại insert PENDING, `nextAttemptAt = now`. Chạy trong transaction của caller (`@Transactional(propagation = MANDATORY)`).
  - `record CalendarEvent(String summary, String description, String location, Instant start, Instant end, String attendeeEmail)`; `static CalendarEvent from(Booking, boolean inviteBooker)`: description = `"Người đặt: <fullName> <<email>>\n\n<description>"`; attendeeEmail = inviteBooker ? email : null.
  - `interface GoogleCalendarClient { String createEvent(String calendarId, CalendarEvent e); void updateEvent(String calendarId, String eventId, CalendarEvent e); void deleteEvent(String calendarId, String eventId); }` — update/delete ném `CalendarEventNotFoundException` khi Google trả 404/410.
  - `NoOpGoogleCalendarClient` (`@ConditionalOnProperty(name="app.calendar.enabled", havingValue="false", matchIfMissing=true)`), `GoogleCalendarClientImpl` (`havingValue="true"`): `GoogleCredentials.fromStream(key).createScoped(CalendarScopes.CALENDAR)`; nếu `impersonateUser` non-blank → `.createDelegated(impersonateUser)`; `Calendar.Builder(GoogleNetHttpTransport.newTrustedTransport(), GsonFactory.getDefaultInstance(), new HttpCredentialsAdapter(creds))`; event `EventDateTime` với `setDateTime(new DateTime(instant.toEpochMilli()))` và `setTimeZone("Asia/Ho_Chi_Minh")`; bắt `GoogleJsonResponseException` 404/410 → `CalendarEventNotFoundException`.
  - `CalendarOutboxProcessor(repo, bookingRepository, client, props, clock)`:
    - `@Scheduled(fixedDelayString = "${app.calendar.poll-interval-ms}") void poll()` → gọi `processBatch()` nếu `enabled`.
    - `@Transactional int processBatch()`: `lockPendingBatch(now, batchSize)`; với từng item `processOne(item)`; trả số item xử lý.
    - `processOne` (không ném): load booking (fetch room, user). Logic:
      - booking `CANCELLED` và action là `CREATE`/`UPDATE` → DONE (bỏ qua, `lastError = "booking cancelled"`).
      - `CREATE`: `createEvent` → lưu `googleEventId` vào booking → DONE.
      - `UPDATE`: nếu `googleEventId == null` → để PENDING, `nextAttemptAt = now + 30s`, không tăng attempts; else `updateEvent` → DONE; `CalendarEventNotFoundException` → FAILED.
      - `CANCEL`: nếu `googleEventId == null` → DONE (không gì để xóa); else `deleteEvent` → DONE; `CalendarEventNotFoundException` → DONE.
      - Exception khác: `attempts++`, `lastError = ex.toString()` (cắt 2000 ký tự); `attempts >= maxAttempts` → FAILED, ngược lại `nextAttemptAt = now + backoff(attempts)`.
    - `static Duration backoff(int attempts)` = `30s * 2^(attempts-1)` (attempts=1 → 30s, 2 → 60s, 3 → 120s).
  - Admin: `GET /api/v1/admin/calendar-outbox?status=` (mặc định FAILED) → `List<OutboxResponse>`; `POST /api/v1/admin/calendar-outbox/{id}/retry` → set PENDING, `attempts=0`, `nextAttemptAt=now`, trả `OutboxResponse`. `record OutboxResponse(Long id, UUID bookingId, OutboxAction action, OutboxStatus status, int attempts, OffsetDateTime nextAttemptAt, String lastError, OffsetDateTime createdAt, OffsetDateTime processedAt)`.
  - Test `FakeGoogleCalendarClient implements GoogleCalendarClient` (`@TestConfiguration` `@Primary`): ghi lại calls `List<String> calls` (`"create:<calendarId>"`, ...), `AtomicBoolean failNext`, `Set<String> missingEventIds`; `createEvent` trả `"evt-" + counter`.
  - Test class dùng `@TestPropertySource(properties = "app.calendar.enabled=true")` + `@Import(FakeGoogleCalendarClient.class)`; gán `googleCalendarId` cho phòng Earth qua repository trước mỗi test.

- [ ] **Step 1: Viết `BackoffTest`**: `backoff(1)==30s`, `backoff(2)==60s`, `backoff(6)==960s`.
- [ ] **Step 2: Viết `CalendarOutboxProcessorTest`**:
  - `createBooking_enqueuesCreate_processorStoresEventId`: sau `create` có 1 outbox PENDING; `processBatch()` → DONE, booking `googleEventId == "evt-1"`, fake calls = `["create:cal-earth"]`.
  - `roomWithoutCalendarId_noOutbox`.
  - `updateBeforeCreateDone_staysPending_thenProcessedInOrder`: create + update rồi `processBatch()` hai lần → calls `[create, update]`, cả hai DONE.
  - `processor_cancelBeforeCreateFinished_skipsBoth`: create + cancel chưa xử lý; `processBatch()` → CREATE DONE với lastError "booking cancelled", CANCEL DONE, fake calls rỗng.
  - `googleFailure_retriesWithBackoff_thenFailedAfterMax`: `failNext` luôn true (dùng flag `alwaysFail`); gọi `processBatch()` với clock tiến dần 7 lần → attempts 6, status FAILED; `retry` endpoint admin → PENDING attempts 0.
  - `cancelWhenGoogleReturns404_isDone`.
  - `disabled_noOutboxRows` (test riêng không set property, kiểm `enqueue` không ghi).

  Clock trong test: bean `Clock` thay bằng `MutableClock` (test util `common/MutableClock extends Clock` với `setInstant`) qua `@TestConfiguration @Primary` trong `AbstractIntegrationTest`; mặc định trỏ giờ hệ thống tại lúc test bắt đầu.

- [ ] **Step 3: FAIL. Step 4: Implement. Step 5: `mvn -q test` PASS.**
- [ ] **Step 6: Commit** — `feat(calendar): google calendar sync via transactional outbox with retry`

---

### Task 10: README, OpenAPI polish, end-to-end docker verification

**Files:**
- Create: `README.md`, `docs/api-examples.http` (tùy chọn), `src/main/java/com/dki/roombooking/common/config/OpenApiConfig.java`
- Modify: `docker-compose.yml` nếu cần.

**Interfaces:**
- `OpenApiConfig`: `@OpenAPIDefinition(info = @Info(title="Meeting Room Booking API", version="1.0"))`, `@SecurityScheme(name="bearerAuth", type=HTTP, scheme="bearer", bearerFormat="JWT")`; controller có `@SecurityRequirement(name="bearerAuth")`.

- [ ] **Step 1: Viết `README.md`** (tiếng Việt) theo mục 14 spec: chạy (`docker compose up --build`, local `mvn spring-boot:run` cần Postgres, test `mvn test` cần Docker), tài khoản seed, kiến trúc + ERD mermaid, bảng endpoint, curl luồng: login → `/rooms/availability` → `POST /bookings` → `POST` lần 2 nhận 409 → cancel; quyết định & lý do (row lock + exclusion constraint, outbox, soft-cancel, no recurring/approval, giờ làm việc 07–20, lưới 15 phút, VN timezone, JWT không refresh, attendees tắt mặc định vì DWD); bật Google Calendar (tạo service account, bật Calendar API, share lịch phòng cho email SA quyền "Make changes to events", đặt file vào `./secrets/google-sa.json`, set `GOOGLE_CALENDAR_ENABLED=true`, `PUT /rooms/{id}` với `googleCalendarId`); ngoài phạm vi & mở rộng.

- [ ] **Step 2: Chạy `docker compose up --build -d`, đợi healthy, chạy luồng curl trong README bằng tay**

Expected: login 200, availability trả 5 phòng, booking 201, booking trùng 409 với `conflict`, cancel 200, swagger tại `http://localhost:8080/swagger-ui.html` mở được. Ghi output thật vào README nếu khác dự kiến.

- [ ] **Step 3: `docker compose down -v`.**
- [ ] **Step 4: Commit** — `docs: README with run guide, design decisions and api walkthrough`

---

## Self-review

- Spec coverage: mục 6 → Task 2; mục 7 → Task 7, 8; mục 8 → Task 4, 5, 6, 7, 8, 9; mục 9 → Task 9; mục 10 → Task 7 (lock + constraint + concurrency test) và Task 2 (constraint); mục 11 → Task 4; mục 12 → Task 1, 10; mục 13 → mỗi task; mục 14 → Task 10. Seed chuyển từ Flyway `V2__seed` sang `SeedDataRunner` (quyết định của planner: tránh hardcode BCrypt hash trong SQL, vẫn idempotent).
- Type consistency: `CalendarSyncPort` (booking) ↔ `CalendarSyncService` (calendar) ↔ `OutboxAction` (calendar) dùng xuyên Task 7, 8, 9. `RoomRepository.findByIdForUpdate` dùng ở Task 7, 8. `BookingRepository.findActiveOverlapping(UUID, Instant, Instant, UUID)` dùng ở Task 7, 8.
- Review Focus: 5 mục đều có test được gắn vào task sở hữu.
