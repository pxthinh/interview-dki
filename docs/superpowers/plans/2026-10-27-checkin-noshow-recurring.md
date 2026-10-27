# Check-in / No-show + Weekly Recurring Booking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** (1) Bookings not checked in within a grace period after start are auto-released (`NO_SHOW`); (2) weekly recurring bookings are created in one call, skipping conflicting occurrences and reporting them.

**Architecture:** Both features ride the existing booking pipeline. Check-in adds a `checked_in_at` column, a `NO_SHOW` status, one endpoint, and a `@Scheduled` sweeper that reuses the cancel side-effects (calendar outbox CANCEL + email). Recurring materializes one real `bookings` row per occurrence under the same room row-lock + `ensureNoOverlap` path as single create, linked by a minimal `recurring_series` table; the DB exclusion constraint stays the final guard untouched (it only covers `status = 'ACTIVE'`, so `NO_SHOW` frees the slot by definition).

**Tech Stack:** Java 17, Spring Boot 3.5, PostgreSQL 16, Flyway, Testcontainers, React 19 + TS.

**Spec:** decisions settled in conversation (no separate doc):
- Check-in: any participant or the booker (or admin) may check in; grace 15 min (env-configurable); check-in window opens 5 min before start and stays open until `endTime` while the booking is `ACTIVE` (the sweeper, not the endpoint, enforces the deadline); meetings shorter than the grace period are never swept; no-show gets its own status `NO_SHOW` (not `CANCELLED`), distinct email copy.
- Recurring: weekly only; skip conflicting occurrences and report them (partial success); cancel options are "this occurrence" (existing endpoint) and "whole series, remaining occurrences"; per-occurrence edit only (existing endpoint); series capped so the last occurrence obeys the existing 90-days-ahead rule; all-occurrences-conflict fails the whole request.

## Global Constraints

- Flyway only for schema change; `ddl-auto: validate` must keep passing. Next free migrations: `V9`, `V10`.
- `bookings.status` has `CHECK (status IN ('ACTIVE','CANCELLED'))` from V1 (auto-named `bookings_status_check`) — must be widened for `NO_SHOW`.
- Business errors = `BusinessRuleException(code, message)` → 422; conflicts = `BookingConflictException(ConflictInfo)` → 409; authz = `AccessDeniedException` → 403 (see `GlobalExceptionHandler`).
- All times compared via injected `java.time.Clock` (`ClockConfig`), wall-clock rules in `Asia/Ho_Chi_Minh` (`BusinessTime.ZONE`; no DST, so `+1 week` is safe via `ZonedDateTime.plusWeeks`).
- Integration tests extend `AbstractIntegrationTest` (Testcontainers postgres:16); follow existing test style (`TestAuth`, `TestData`).
- Email via `NotificationService` snapshot pattern (`BookingSnapshot.of` inside tx, `@Async` send); calendar via `calendarSync.enqueue(booking, OutboxAction.X)`.
- Commits: conventional commits, message style as in `git log`; end with the session's attribution trailer.

## Review Focus

1. User cancels while sweeper marks no-show (optimistic `@Version` race) → `markNoShow` re-checks status inside its own tx; test in Task 3 (markNoShow on a CANCELLED booking is a no-op).
2. A new booking into the slot of a `NO_SHOW` booking must succeed (constraint only guards ACTIVE) → test in Task 3.
3. Check-in boundary: exactly `start − 5 min` is allowed, `start − 5 min − 1s` is `CHECKIN_TOO_EARLY` → test in Task 2.
4. Recurring occurrence that only clashes via the buffer (back-to-back within 15-min gap) must be skipped+reported, not crash on the DB constraint → test in Task 5.
5. Recurring where the *last* occurrence lands exactly on day 90 is allowed; day 91 → `TOO_FAR_AHEAD` → test in Task 5.

---

### Task 1: Migration V9 + status/entity plumbing for check-in

**Files:**
- Create: `src/main/resources/db/migration/V9__checkin_noshow.sql`
- Modify: `src/main/java/pxt/dki/booking/BookingStatus.java`, `Booking.java`, `dto/BookingResponse.java`
- Test: `src/test/java/pxt/dki/db/SchemaConstraintsTest.java` (extend)

**Interfaces:**
- Produces: `BookingStatus.NO_SHOW`; `Booking.getCheckedInAt()/setCheckedInAt(Instant)`; `BookingResponse.checkedInAt` (OffsetDateTime, nullable, via `BusinessTime.toApi`).

- [ ] **Step 1: Write failing test** — in `SchemaConstraintsTest`, add a test that inserts a booking row with `status = 'NO_SHOW'` via JDBC/repository and succeeds, and asserts `checked_in_at` column exists (insert with a value).
- [ ] **Step 2: Run it, verify it fails** — `mvn test -Dtest=SchemaConstraintsTest` → FAIL (check constraint violation / unknown column).
- [ ] **Step 3: Write V9** —
  ```sql
  ALTER TABLE bookings ADD COLUMN checked_in_at timestamptz;
  ALTER TABLE bookings DROP CONSTRAINT bookings_status_check;
  ALTER TABLE bookings ADD CONSTRAINT bookings_status_check
      CHECK (status IN ('ACTIVE', 'CANCELLED', 'NO_SHOW'));
  ```
  (Verify the V1 auto-name first inside the test DB if the drop fails.)
- [ ] **Step 4: Java plumbing** — add `NO_SHOW` to enum; `@Column(name = "checked_in_at") private Instant checkedInAt;` on `Booking`; add `checkedInAt` to `BookingResponse` record + `from()`.
- [ ] **Step 5: Run test → PASS**, full `mvn test` green.
- [ ] **Step 6: Commit** — `feat(booking): NO_SHOW status and checked_in_at column`

### Task 2: Check-in endpoint

**Files:**
- Create: `src/main/java/pxt/dki/booking/CheckinProperties.java`
- Modify: `BookingService.java`, `BookingController.java`, `src/main/resources/application.yml`, `.env.example`
- Test: Create `src/test/java/pxt/dki/booking/BookingCheckInTest.java`

**Interfaces:**
- Produces: `BookingResponse BookingService.checkIn(AuthUser actor, UUID id)`; `POST /api/v1/bookings/{id}/check-in`; `CheckinProperties(boolean enabled, int graceMinutes, int earlyWindowMinutes, long sweepIntervalMs)` bound to `app.checkin.*` (defaults true/15/5/60000, env `CHECKIN_ENABLED`, `CHECKIN_GRACE_MINUTES` in yml like the other `app.*` blocks).

- [ ] **Step 1: Failing tests** in `BookingCheckInTest` (MockMvc, style of `BookingUpdateCancelTest`):
  - booker checks in within window → 200, `checkedInAt` non-null
  - participant checks in → 200
  - non-participant stranger → 403
  - before `start − earlyWindow` → 422 code `CHECKIN_TOO_EARLY` (boundary: exactly at `start − 5min` OK)
  - after `endTime` → 422 `BOOKING_ALREADY_ENDED`
  - cancelled booking → 422 `BOOKING_NOT_ACTIVE`
  - second check-in → 200, `checkedInAt` unchanged (idempotent)

  Bookings needing an already-started meeting are written through the repository directly (service forbids past starts).
- [ ] **Step 2: Run → FAIL** (`mvn test -Dtest=BookingCheckInTest`, 404/compile errors expected).
- [ ] **Step 3: Implement** `checkIn` in `BookingService`: `require(id)`; authz = admin OR booker OR `participants` contains actor (else `AccessDeniedException`); if `checkedInAt != null` return early; status must be ACTIVE (`BOOKING_NOT_ACTIVE`); `now < start − earlyWindow` → `CHECKIN_TOO_EARLY`; `now >= endTime` → `BOOKING_ALREADY_ENDED`; set `checkedInAt = now`, save, return. Controller method with `@Operation` summary in Vietnamese matching file style.
- [ ] **Step 4: Run → PASS**, full suite green.
- [ ] **Step 5: Commit** — `feat(booking): check-in endpoint for booker and participants`

### Task 3: No-show sweeper

**Files:**
- Create: `src/main/java/pxt/dki/booking/NoShowSweeper.java`
- Modify: `BookingRepository.java`, `BookingService.java`, `NotificationService.java`
- Test: Create `src/test/java/pxt/dki/booking/NoShowSweepTest.java`

**Interfaces:**
- Consumes: `CheckinProperties` (Task 2).
- Produces: `List<Booking> BookingRepository.findNoShowCandidates(Instant latestStart, Instant now)` (JPQL: status ACTIVE, `checkedInAt` null, `startTime <= :latestStart`, `endTime > :now`; the `end − start > grace` filter happens in Java); `void BookingService.markNoShow(UUID id)` — `@Transactional(propagation = REQUIRES_NEW)`; `void NotificationService.bookingNoShow(BookingSnapshot, Collection<User>)` (subject `[DKI Rooms] Thu hồi phòng (không check-in): …`).

- [ ] **Step 1: Failing tests** in `NoShowSweepTest` (insert bookings via repository with past `startTime`):
  - ACTIVE, not checked in, started > grace ago, still ongoing → after `sweeper.sweep()`: status `NO_SHOW`, one `calendar_outbox` row with action CANCEL
  - checked-in booking untouched
  - meeting shorter than grace (`end − start <= grace`) untouched
  - `markNoShow` on a CANCELLED booking → stays CANCELLED (race guard)
  - new booking into the freed slot after sweep → 201 (constraint ignores NO_SHOW)
- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Implement** — `NoShowSweeper` with `@Scheduled(fixedDelayString = "${app.checkin.sweep-interval-ms}")`, skip when `!enabled`; fetch ids with `latestStart = now − grace`, Java-filter `end − start > grace` needs start/end, so return candidates as bookings (read-only tx) then call `markNoShow(id)` per id in try/catch (log + continue). `markNoShow`: reload; proceed only if still ACTIVE and `checkedInAt == null` and `now >= startTime + grace`; set `NO_SHOW`, save, `calendarSync.enqueue(b, OutboxAction.CANCEL)`, `notifications.bookingNoShow(snapshot, organizer + participants)`.
- [ ] **Step 4: Run → PASS**, full suite green.
- [ ] **Step 5: Commit** — `feat(booking): no-show sweeper releases unclaimed rooms`

### Task 4: Frontend check-in + docs

**Files:**
- Modify: `frontend/src/api/types.ts`, `frontend/src/api/client.ts`, `frontend/src/pages/MyBookingsPage.tsx`, `README.md`, `.env.example`

Read `MyBookingsPage.tsx` and `client.ts` first; follow their existing patterns exactly.

- [ ] **Step 1:** types: add `'NO_SHOW'` to the status union, `checkedInAt: string | null`; client: `checkIn(id)` → `POST /bookings/{id}/check-in`.
- [ ] **Step 2:** MyBookingsPage: "Check-in" button on ACTIVE bookings whose window is open (`now >= start − 5min && now < end`, client-side estimate only — server decides); after success show checked-in state; render a NO_SHOW badge (match existing CANCELLED badge styling).
- [ ] **Step 3:** README: document the feature (Vietnamese, same tone), new env vars in `.env.example` + README table.
- [ ] **Step 4: Verify** — `cd frontend && npm run build` passes; manual smoke optional.
- [ ] **Step 5: Commit** — `feat(frontend): check-in button and NO_SHOW state`

### Task 5: Recurring — migration V10, series entity, create endpoint

**Files:**
- Create: `src/main/resources/db/migration/V10__recurring_series.sql`, `src/main/java/pxt/dki/booking/RecurringSeries.java`, `RecurringSeriesRepository.java`, `dto/CreateRecurringBookingRequest.java`, `dto/RecurringBookingResponse.java`
- Modify: `Booking.java` (+`seriesId`), `dto/BookingResponse.java` (+`seriesId`), `BookingService.java`, `BookingController.java`
- Test: Create `src/test/java/pxt/dki/booking/RecurringBookingTest.java`

**Interfaces:**
- Produces:
  - V10: `recurring_series(id uuid PK, user_id uuid NOT NULL REFERENCES users(id), created_at timestamptz NOT NULL)`; `bookings.series_id uuid REFERENCES recurring_series(id)`; partial index `idx_bookings_series ON bookings(series_id) WHERE series_id IS NOT NULL`.
  - `Booking`: `@Column(name = "series_id") private UUID seriesId;` (plain UUID, no association — my call, keeps the aggregate flat).
  - `CreateRecurringBookingRequest(roomId, title, description, attendeeCount, startTime, endTime, participantIds, @NotNull @Min(2) @Max(26) Integer weeks)` — same validation annotations as `CreateBookingRequest`.
  - `RecurringBookingResponse(UUID seriesId, List<BookingResponse> created, List<SkippedOccurrence> skipped)`, `SkippedOccurrence(OffsetDateTime startTime, OffsetDateTime endTime, ConflictInfo conflict)`.
  - `RecurringBookingResponse BookingService.createRecurring(AuthUser, CreateRecurringBookingRequest)`; `POST /api/v1/bookings/recurring` → 201 (controller calls `calendarPreSync.preSync(roomId)` first, like create).

- [ ] **Step 1: Failing tests** in `RecurringBookingTest`:
  - weeks=4, free room → 201, 4 bookings sharing one non-null `seriesId`, `skipped` empty
  - week 2's slot pre-occupied → 201, 3 created, `skipped` has exactly that occurrence with the blocking booking's `ConflictInfo`
  - occurrence blocked only by the 15-min buffer of an adjacent booking → skipped, not 500
  - every occurrence occupied → 422 `RECURRING_ALL_CONFLICT`, no series row persisted
  - last occurrence on day 90 → 201; day 91 → 422 `TOO_FAR_AHEAD`
  - calendar outbox: one CREATE row per created occurrence
- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Implement** `createRecurring`: lock room (`findByIdForUpdate`), room active + capacity checks as in `create`; occurrences = `i in 0..weeks−1`, `start/end = requested times as ZonedDateTime(ZONE).plusWeeks(i)`; `validator.validateTimes` on occurrence 0; last occurrence start ≤ today+90d else `TOO_FAR_AHEAD`; resolve participants once; persist `RecurringSeries`; per occurrence: `ensureNoOverlap` in try/catch `BookingConflictException` → collect skipped, else save booking (copy of create's field set + `seriesId`) and `enqueue CREATE`. If created empty → `BusinessRuleException("RECURRING_ALL_CONFLICT", …)` (tx rolls back the series row). Emails: one summary to organizer and one invite summary to participants — add `NotificationService.recurringCreated(BookingSnapshot first, int totalCreated, String untilDate, User organizer, Collection<User> participants)` with copy "Lặp hàng tuần, N buổi, đến ngày …" (two sends inside, mirroring create()).
- [ ] **Step 4: Run → PASS**, full suite green (`ddl-auto: validate` catches entity/DDL drift).
- [ ] **Step 5: Commit** — `feat(booking): weekly recurring series with skip-and-report conflicts`

### Task 6: Cancel whole series (remaining occurrences)

**Files:**
- Modify: `BookingService.java`, `BookingController.java`, `BookingRepository.java`, `NotificationService.java`
- Test: extend `RecurringBookingTest.java` (or new `RecurringCancelTest.java`)

**Interfaces:**
- Produces: `List<BookingResponse> BookingService.cancelSeries(AuthUser, UUID seriesId)`; `POST /api/v1/bookings/series/{seriesId}/cancel` → 200 with the cancelled occurrences (empty list allowed — my call); `List<Booking> BookingRepository.findBySeriesIdAndStatusAndEndTimeGreaterThan(UUID, BookingStatus, Instant)`.

- [ ] **Step 1: Failing tests:**
  - owner cancels series → every remaining ACTIVE occurrence (`endTime > now`) becomes CANCELLED with `cancelledAt` set; past/finished occurrences untouched; one outbox CANCEL per cancelled occurrence
  - an occurrence already individually cancelled stays as it was
  - other non-admin user → 403; unknown seriesId → 404
- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Implement:** load series via `RecurringSeriesRepository` (404 if absent); authz owner-or-admin (same rule as `requireOwnerOrAdmin`); loop remaining, set CANCELLED + `cancelledAt`, enqueue CANCEL each; one summary email `seriesCancelled(snapshot of first cancelled, count, recipients)` to organizer + union of participants (skip email when nothing cancelled).
- [ ] **Step 4: Run → PASS**, full suite green.
- [ ] **Step 5: Commit** — `feat(booking): cancel remaining occurrences of a series`

### Task 7: Frontend recurring + docs

**Files:**
- Modify: `frontend/src/api/types.ts`, `client.ts`, `frontend/src/pages/FlowPage.tsx`, `MyBookingsPage.tsx`, `README.md`

Read `FlowPage.tsx` first; reuse its form patterns.

- [ ] **Step 1:** types: `seriesId: string | null` on Booking; recurring request/response types; client: `createRecurring(...)`, `cancelSeries(seriesId)`.
- [ ] **Step 2:** FlowPage: "Lặp hàng tuần" toggle + số tuần (2–26) input; on submit call recurring endpoint; render result: "Đã đặt N buổi" + list of skipped occurrences with the conflicting booking info.
- [ ] **Step 3:** MyBookingsPage: mark series occurrences (e.g. "Hàng tuần" chip) and a "Hủy cả chuỗi" action with confirm dialog.
- [ ] **Step 4:** README: recurring section (Vietnamese); `npm run build` passes; `mvn test` full suite green one last time.
- [ ] **Step 5: Commit** — `feat(frontend): weekly recurring booking with skipped-occurrence report`
