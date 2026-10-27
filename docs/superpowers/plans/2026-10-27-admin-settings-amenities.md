# Admin Settings, Amenity Catalog, Import Prefix, Participant View Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** (1) Admin-editable booking limits (max days ahead — hardcoded 90 today — and max recurring weeks); (2) admin-managed amenity catalog; (3) booking-form validation errors shown inside the popup (today they render behind it); (4) bookings imported from Google Calendar titled `[CALENDAR] …`; (5) invited participants see their bookings read-only in "Booking của tôi" with a role filter.

**Architecture:** A key-value `app_settings` table read through a small `SettingsService` replaces the two hardcoded limits (`BookingRuleValidator.MAX_DAYS_AHEAD`, the recurring `@Max(26)`); old bookings beyond a lowered limit stay untouched (new rule applies to new writes only — user-confirmed). Amenities become a catalog table seeded from the distinct values already on rooms; rooms keep storing `text[]`, the catalog only drives pickers. The import prefix lives in `CalendarImportJob` with echo/double-prefix guards. Participant visibility extends the existing `/bookings/me` specification query with a `role` param.

**Tech Stack:** unchanged (Spring Boot 3.5, Flyway, Testcontainers, React 19 TS).

**Spec:** conversation decisions: limits admin-decided; amenity + weeks management in one admin place; errors inside popup; `[CALENDAR]` prefix for calendar-synced bookings; participants see invited bookings view-only in My Bookings, filterable.

## Global Constraints

- Next free migrations: `V11` (settings), `V12` (amenities). `ddl-auto: validate` stays green.
- Error conventions as before (422 `BusinessRuleException`, 403 `AccessDeniedException`, RFC 7807).
- Settings keys: `booking.max-days-ahead` (default "90"), `booking.max-recurring-weeks` (default "26"). Values stored as text, parsed int, bounds 1..365 and 2..52 on write.
- The reject label `[TRÙNG LỊCH - KHÔNG HỢP LỆ] ` keeps its current form; `[CALENDAR] ` is a separate prefix (constant `CALENDAR_PREFIX = "[CALENDAR] "`).
- Existing tests must keep passing; `CalendarImportTest` title assertions will need the prefix.
- Commits: conventional, with the session's attribution trailer.

## Review Focus

1. Echo loop: app-created booking pushed to Google comes back with summary == title (no prefix) → must stay a no-op; an imported (prefixed) booking edited in-app pushes `[CALENDAR] x` to Google and echoes back → must not become `[CALENDAR] [CALENDAR] x`. → tests in Task 3.
2. Lowering `booking.max-days-ahead` below an existing booking's date must not break reading/cancelling that booking (rule applies in validateTimes/update paths only). → test in Task 1.
3. `role=participant` must not leak other people's bookings: only ones where the actor is in `participants`. → test in Task 4.
4. Deleting an amenity still on a room must not corrupt the room (room keeps its strings). → test in Task 2.
5. Settings write validation: non-numeric or out-of-bounds value → 422, never a saved broken limit that bricks booking creation. → test in Task 1.

---

### Task 1: app_settings + SettingsService + limits go dynamic

**Files:**
- Create: `src/main/resources/db/migration/V11__app_settings.sql`, `src/main/java/pxt/dki/settings/AppSetting.java`, `SettingsRepository.java`, `SettingsService.java`, `SettingsController.java`, `dto/SettingsResponse.java`, `dto/UpdateSettingsRequest.java`
- Modify: `BookingRuleValidator.java` (inject SettingsService; `MAX_DAYS_AHEAD` becomes the seed/default), `BookingService.createRecurring` (weeks limit + horizon from settings), `dto/CreateRecurringBookingRequest.java` (drop `@Max(26)`, keep `@Min(2)`), `BookingRuleValidatorTest` (stub settings)
- Test: Create `src/test/java/pxt/dki/settings/SettingsTest.java`

**Interfaces:**
- V11: `app_settings(key varchar(100) PK, value varchar(100) NOT NULL)`; seed `('booking.max-days-ahead','90'), ('booking.max-recurring-weeks','26')`.
- `SettingsService.maxDaysAhead(): int`, `maxRecurringWeeks(): int`, `update(UpdateSettingsRequest): SettingsResponse` (bounds: days 1..365, weeks 2..52 → 422 `SETTING_OUT_OF_RANGE`).
- `GET /api/v1/settings` (any user) → `SettingsResponse(int maxDaysAhead, int maxRecurringWeeks)`; `PUT /api/v1/admin/settings` (ADMIN) body `UpdateSettingsRequest(Integer maxDaysAhead, Integer maxRecurringWeeks)` — null field = unchanged.
- `BookingRuleValidator` constructor gains `SettingsService`; TOO_FAR_AHEAD messages use the live value.

- [ ] **Step 1: failing tests** in `SettingsTest` (MockMvc): GET returns 90/26 defaults; admin PUT `{"maxDaysAhead":7}` → GET shows 7 and a booking at day+10 → 422 `TOO_FAR_AHEAD` while day+5 → 201; PUT `{"maxRecurringWeeks":3}` then recurring weeks=4 → 422; non-admin PUT → 403; PUT `{"maxDaysAhead":0}` → 422 `SETTING_OUT_OF_RANGE`; lowering the limit does not block cancelling an existing farther-out booking (create at day+40 first, lower to 7, cancel → 200).
- [ ] **Step 2: run → FAIL** (`mvn -q test -Dtest=SettingsTest`).
- [ ] **Step 3: implement** (migration, entity `AppSetting(key,value)`, repo, service reading by key with parse + default fallback, controller, validator/service wiring; recurring horizon check reuses `settings.maxDaysAhead()`, weeks check `RECURRING_TOO_MANY_WEEKS` 422; fix `BookingRuleValidatorTest` with a stub returning 90).
- [ ] **Step 4: run → PASS**, full suite green.
- [ ] **Step 5: commit** `feat(settings): admin-managed booking limits`

### Task 2: amenity catalog

**Files:**
- Create: `src/main/resources/db/migration/V12__amenities.sql`, `src/main/java/pxt/dki/room/Amenity.java`, `AmenityRepository.java`, `AmenityController.java`
- Test: Create `src/test/java/pxt/dki/room/AmenityTest.java`

**Interfaces:**
- V12: `amenities(name varchar(100) PK)`; seed `INSERT INTO amenities SELECT DISTINCT unnest(amenities) FROM rooms ON CONFLICT DO NOTHING;`
- `GET /api/v1/amenities` (user) → `List<String>` sorted; `POST /api/v1/admin/amenities` body `{"name":"..."}` (blank/dup → 422/409 `DataIntegrityViolation`→ 409 handled? use exists-check → 422 `AMENITY_EXISTS`); `DELETE /api/v1/admin/amenities/{name}` → 204, unknown → 404. Rooms are not touched by deletes.

- [ ] **Step 1: failing tests**: GET lists seeded values (insert a room with amenities via jdbc first); admin POST adds, duplicate → 422 `AMENITY_EXISTS`; DELETE removes (GET no longer shows), unknown name → 404; non-admin POST/DELETE → 403; DELETE of an amenity still on a room → room row unchanged (jdbc assert).
- [ ] **Step 2: run → FAIL.**
- [ ] **Step 3: implement** (`@Id String name` entity; controller with `@PreAuthorize("hasRole('ADMIN')")` on mutations).
- [ ] **Step 4: run → PASS**, full suite green.
- [ ] **Step 5: commit** `feat(room): admin-managed amenity catalog`

### Task 3: [CALENDAR] title prefix on imported bookings

**Files:**
- Modify: `src/main/java/pxt/dki/calendar/CalendarImportJob.java`, `src/test/java/pxt/dki/calendar/CalendarImportTest.java`

**Interfaces:**
- `static final String CALENDAR_PREFIX = "[CALENDAR] "` on `CalendarImportJob`.
- New import: title = `CALENDAR_PREFIX + title(event)`. Rejected trace keeps `REJECT_PREFIX + title(event)` (no CALENDAR prefix).
- Update path: strip `CALENDAR_PREFIX` from the incoming summary if present; if the DB title is prefixed, compare/set `CALENDAR_PREFIX + stripped`, else compare/set the raw summary. App-booking echo (summary == title) stays a no-op; imported-booking echo of `[CALENDAR] x` stays a no-op, never double-prefixes.

- [ ] **Step 1: failing tests** in `CalendarImportTest` (uses `FakeGoogleCalendarClient`): new event 'Standup' → booking title `[CALENDAR] Standup`; echo of an app-pushed event (summary == unprefixed title) → no change (likely existing test, keep green); imported booking re-echoed with summary `[CALENDAR] Standup` → title unchanged, applied count 0; Google edit renames to 'Retro' → `[CALENDAR] Retro`. Adjust existing title assertions.
- [ ] **Step 2: run → FAIL** on the new prefix assertions.
- [ ] **Step 3: implement.**
- [ ] **Step 4: run → PASS**, full suite green.
- [ ] **Step 5: commit** `feat(calendar): [CALENDAR] prefix on imported booking titles`

### Task 4: /bookings/me role filter (participant view)

**Files:**
- Modify: `BookingService.myBookings` (+`role` param), `BookingController.myBookings`
- Test: Create `src/test/java/pxt/dki/booking/MyBookingsRoleTest.java`

**Interfaces:**
- `GET /api/v1/bookings/me?role=organizer|participant` — omitted = both. Spec predicate: organizer → `user.id = actor`; participant → actor in `participants` (join); both → OR. Enum `BookingRole { ORGANIZER, PARTICIPANT }` (new, in booking package) bound case-insensitively by Spring.
- Service signature: `myBookings(AuthUser actor, Instant from, Instant to, BookingStatus status, BookingRole role)`.

- [ ] **Step 1: failing tests**: alice books with bob invited; bob `role=participant` → sees it; bob `role=organizer` → empty; bob default → sees it; carol (not invited) default → empty; alice `role=participant` → empty. Distinct room via jdbc or API to avoid clashes.
- [ ] **Step 2: run → FAIL** (unknown param ignored → wrong results / 400).
- [ ] **Step 3: implement** (criteria join: `root.join("participants").get("id") = actor.id()` with `query.distinct(true)` for the OR case).
- [ ] **Step 4: run → PASS**, full suite green.
- [ ] **Step 5: commit** `feat(booking): participants see invited bookings in /bookings/me`

### Task 5: frontend + docs

**Files:**
- Create: `frontend/src/pages/AdminSettingsPage.tsx`
- Modify: `frontend/src/App.tsx` (route + nav "Cấu hình"), `frontend/src/api/types.ts`, `client.ts`, `AvailabilityPage.tsx`, `MyBookingsPage.tsx`, `RoomsPage.tsx`, `README.md`

Read `App.tsx`, `RoomsPage.tsx`, `AuthContext.tsx` fully before editing.

- [ ] **Step 1: AdminSettingsPage** — two numeric fields (giới hạn ngày đặt trước, số tuần tối đa) with save via `PUT /admin/settings`; amenity manager (list + add input + delete ×) via the Task 2 endpoints. Route `/admin/settings`, nav link admin-only.
- [ ] **Step 2: AvailabilityPage** — amenities chips from `GET /amenities`; date input `max` and weeks input `max` from `GET /settings` (default 90/26 on fetch failure); render `{error}` inside the booking modal (keep the outer banner for non-modal errors).
- [ ] **Step 3: MyBookingsPage** — role filter select (Tất cả / Tôi đặt / Được mời) wired to `role=`; Sửa/Hủy/Hủy chuỗi buttons only when `b.userId === user.id` (from `useAuth()`); Check-in button stays for participants; render error inside the edit modal; date `max` from settings.
- [ ] **Step 4: RoomsPage** — amenities comma-text input becomes catalog checkboxes (from `GET /amenities`); stale values on an existing room still display.
- [ ] **Step 5: README** — settings/amenities/role filter/[CALENDAR] rows in rules+API tables, env section note, decisions entry.
- [ ] **Step 6: verify** `cd frontend && npm run build` exit 0; `mvn -q test` full suite green.
- [ ] **Step 7: commit** `feat(frontend): admin settings page, amenity catalog, participant view`
