package pxt.dki.calendar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import pxt.dki.booking.Booking;
import pxt.dki.booking.BookingRepository;
import pxt.dki.booking.CalendarPreSyncPort;
import pxt.dki.booking.BookingRuleValidator;
import pxt.dki.booking.BookingStatus;
import pxt.dki.room.Room;
import pxt.dki.room.RoomRepository;
import pxt.dki.user.Role;
import pxt.dki.user.User;
import pxt.dki.user.UserRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reverse sync: Google Calendar -> database. Events created, moved or cancelled directly on a
 * room's Google Calendar become bookings here, so the DB keeps being the single source of truth
 * for "is this room free".
 *
 * Per room it pulls incremental changes with Google's sync token (full listing from "now" on
 * the first run), applies them in a short transaction, and stores the next token on the room.
 * Events written by our own outbox come back with a known google_event_id and identical data,
 * so they no-op instead of echoing. Changes are applied directly through the repositories —
 * never through BookingService — so the import never enqueues ROOM rows and cannot bounce the
 * same change back to the room calendar. It does enqueue personal-copy rows for the people
 * taking part, whose own calendars must follow edits made on the room calendar. Bookings born
 * here are marked {@code source = GOOGLE}: their organizer gets no personal copy.
 *
 * An imported event that would double-book the room (15-minute break included) is skipped and
 * logged: the DB booking wins, the stray Google event stays on the calendar for humans to see.
 * All-day events (no concrete start/end time) are ignored.
 */
@Component
public class CalendarImportJob implements CalendarPreSyncPort {

    private static final Logger log = LoggerFactory.getLogger(CalendarImportJob.class);
    public static final String IMPORT_USER_EMAIL = "calendar-import@dki.local";
    /** Prefix stamped onto Google events the import rejected; also the re-patch guard. */
    static final String REJECT_PREFIX = "[TRÙNG LỊCH - KHÔNG HỢP LỆ] ";
    /** Marks bookings that originate from Google Calendar, so the app shows their source. */
    static final String CALENDAR_PREFIX = "[CALENDAR] ";
    /** How long an overlapping Google event is deferred while our own push of that slot is pending. */
    static final Duration DEFER_WINDOW = Duration.ofMinutes(15);

    private final RoomRepository roomRepository;
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final GoogleCalendarClient client;
    private final CalendarProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final pxt.dki.settings.SettingsService settings;
    private final CalendarSyncService calendarSync;
    private final CalendarOutboxRepository outboxRepository;

    public CalendarImportJob(RoomRepository roomRepository, BookingRepository bookingRepository,
                             UserRepository userRepository, GoogleCalendarClient client,
                             CalendarProperties properties, Clock clock, TransactionTemplate tx,
                             pxt.dki.settings.SettingsService settings, CalendarSyncService calendarSync,
                             CalendarOutboxRepository outboxRepository) {
        this.roomRepository = roomRepository;
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.client = client;
        this.properties = properties;
        this.clock = clock;
        this.tx = tx;
        this.settings = settings;
        this.calendarSync = calendarSync;
        this.outboxRepository = outboxRepository;
    }

    @Scheduled(fixedDelayString = "${app.calendar.import-interval-ms}",
            initialDelayString = "${app.scheduling.initial-delay-ms:0}")
    public void poll() {
        if (!properties.enabled() || !settings.calendarSyncEnabled()) {
            return;
        }
        runOnce();
    }

    /** One import pass over every room that has a calendar. Returns applied change count. */
    public int runOnce() {
        int applied = 0;
        List<Room> rooms = roomRepository.findAllByOrderByNameAsc().stream()
                .filter(r -> r.getGoogleCalendarId() != null)
                .toList();
        for (Room room : rooms) {
            applied += syncOne(room);
        }
        return applied;
    }

    /** Import one room, swallowing failures (token expiry resets the cursor). */
    private int syncOne(Room room) {
        try {
            return syncRoom(room);
        } catch (SyncTokenExpiredException e) {
            log.warn("Sync token for room '{}' expired; resetting for a full resync", room.getName());
            tx.executeWithoutResult(s -> roomRepository.findById(room.getId()).ifPresent(r -> {
                r.setGoogleSyncToken(null);
                roomRepository.save(r);
            }));
        } catch (Exception e) {
            log.warn("Calendar import failed for room '{}': {}", room.getName(), e.toString());
        }
        return 0;
    }

    private final java.util.concurrent.ExecutorService preSyncExecutor =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "calendar-presync");
                t.setDaemon(true);
                return t;
            });

    /**
     * Pre-emptive import of one room right before a booking write: pulls freshly created
     * calendar events in so they take part in the conflict check instead of waiting for the
     * next poll. Bounded by app.calendar.pre-sync-timeout-ms and never throws; on timeout the
     * import simply finishes in the background like a normal poll.
     */
    @Override
    public void preSync(java.util.UUID roomId) {
        try {
            if (!properties.enabled() || !settings.calendarSyncEnabled()) {
                return;
            }
            java.util.concurrent.CompletableFuture
                    .runAsync(() -> roomRepository.findById(roomId)
                            .filter(r -> r.getGoogleCalendarId() != null)
                            .ifPresent(this::syncOne), preSyncExecutor)
                    .get(properties.preSyncTimeoutMs(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("Pre-sync for room {} skipped: {}", roomId, e.toString());
        }
    }

    private int syncRoom(Room room) {
        // Google call outside any transaction; applying the changes is a short transaction.
        RemoteEventPage page = client.listChanges(
                room.getGoogleCalendarId(), room.getGoogleSyncToken(), clock.instant());
        List<RemoteCalendarEvent> rejected = new java.util.ArrayList<>();
        List<RemoteCalendarEvent> deferred = new java.util.ArrayList<>();
        Integer applied = tx.execute(status -> {
            // Same row lock BookingService takes first: imports of a room serialize with the
            // app's own writes to it, so neither side checks conflicts against a half-done other.
            Room locked = roomRepository.findByIdForUpdate(room.getId()).orElse(null);
            if (locked == null) {
                return 0;
            }
            int count = 0;
            for (RemoteCalendarEvent event : page.events()) {
                if (applyEvent(room, event, rejected, deferred)) {
                    count++;
                }
            }
            // A deferred event must come back in the next poll, so the cursor stays put; the
            // events already applied no-op when they are listed again.
            if (deferred.isEmpty()) {
                locked.setGoogleSyncToken(page.nextSyncToken());
                roomRepository.save(locked);
            }
            return count;
        });
        // Outside the transaction: stamp rejected events on Google so the calendar does not
        // look double-booked. The prefixed title guards against re-stamping when the patch
        // itself echoes back through the change feed.
        for (RemoteCalendarEvent event : rejected) {
            try {
                client.updateEvent(room.getGoogleCalendarId(), event.id(), new CalendarEvent(
                        REJECT_PREFIX + (event.summary() == null ? "" : event.summary()),
                        "Khung giờ này đã có booking khác trong hệ thống đặt phòng (tính cả giờ nghỉ 15 phút). "
                                + "Sự kiện không được nhận; vui lòng dời sang giờ còn trống hoặc xóa sự kiện.",
                        room.getName(), event.start(), event.end(), null));
            } catch (Exception e) {
                log.warn("Could not stamp rejected event {}: {}", event.id(), e.toString());
            }
        }
        return applied == null ? 0 : applied;
    }

    /** @return true when the event changed something in the database */
    private boolean applyEvent(Room room, RemoteCalendarEvent event, List<RemoteCalendarEvent> rejected,
                               List<RemoteCalendarEvent> deferred) {
        Optional<Booking> linked = bookingRepository.findByGoogleEventId(event.id());

        if (event.cancelled()) {
            if (linked.isPresent() && linked.get().getStatus() == BookingStatus.ACTIVE) {
                Booking b = linked.get();
                b.setStatus(BookingStatus.CANCELLED);
                b.setCancelledAt(clock.instant());
                bookingRepository.save(b);
                // personal copies only: the room calendar is where the change came from
                calendarSync.enqueuePersonal(b, OutboxAction.CANCEL);
                log.info("Imported cancellation from Google: booking {} ({})", b.getId(), b.getTitle());
                return true;
            }
            return false;
        }
        if (event.start() == null || event.end() == null || !event.end().isAfter(event.start())) {
            return false; // all-day or malformed: not bookable room time
        }

        if (linked.isPresent()) {
            Booking b = linked.get();
            if (b.getStatus() != BookingStatus.ACTIVE) {
                return false; // cancelled here; a stray Google edit does not resurrect it
            }
            boolean sameTimes = b.getStartTime().equals(event.start()) && b.getEndTime().equals(event.end());
            // Imported (prefixed) bookings keep their prefix across Google renames; the echo of
            // our own outbox push (summary already prefixed) must never double-prefix. Bookings
            // born in the app stay unprefixed.
            String raw = title(event);
            String stripped = raw.startsWith(CALENDAR_PREFIX) ? raw.substring(CALENDAR_PREFIX.length()) : raw;
            String summary = b.getTitle().startsWith(CALENDAR_PREFIX) ? CALENDAR_PREFIX + stripped : raw;
            boolean sameTitle = summary.equals(b.getTitle());
            if (sameTimes && sameTitle) {
                return false; // our own outbox echo, or nothing changed
            }
            if (!sameTimes && overlapsOthers(room, event.start(), event.end(), b.getId())) {
                log.warn("Google edit of event {} overlaps another booking; keeping DB times", event.id());
                return false;
            }
            b.setTitle(summary);
            b.setStartTime(event.start());
            b.setEndTime(event.end());
            bookingRepository.save(b);
            calendarSync.enqueuePersonal(b, OutboxAction.UPDATE);
            log.info("Imported update from Google: booking {} ({})", b.getId(), b.getTitle());
            return true;
        }

        List<Booking> conflicts = conflicting(room, event.start(), event.end(), null);
        if (!conflicts.isEmpty()) {
            // Outbox race: the processor inserts the Google event first and links the booking
            // (google_event_id) in a later transaction. Listed in that gap, our own event is
            // unlinked and overlaps our own booking - it must not be rejected and must never get a
            // trace row carrying the id the real booking is about to receive. The pending room
            // CREATE row is the signal (nothing in the pushed event marks it as ours); the event
            // is left for the next poll, by which time it is linked and no-ops as an echo.
            // Bounded: a CREATE row still PENDING after DEFER_WINDOW is stuck (processor off,
            // retries exhausted soon) and must not freeze this room's import cursor forever.
            Instant since = clock.instant().minus(DEFER_WINDOW);
            boolean ourPushInFlight = conflicts.stream().anyMatch(b ->
                    outboxRepository.existsByBookingIdAndTargetUserIdIsNullAndActionAndStatusAndCreatedAtAfter(
                            b.getId(), OutboxAction.CREATE, OutboxStatus.PENDING, since));
            if (ourPushInFlight) {
                log.info("Google event {} overlaps a booking whose push is in flight; deferred", event.id());
                deferred.add(event);
                return false;
            }
            log.warn("Google event {} ('{}') overlaps an existing booking of room '{}'; rejected",
                    event.id(), event.summary(), room.getName());
            if (event.summary() == null || !event.summary().startsWith(REJECT_PREFIX)) {
                rejected.add(event);
            }
            // Keep a trace in the database too: a CANCELLED booking with the rejection label.
            // CANCELLED rows do not hold the room and do not trip the exclusion constraint,
            // but they show up in the app (admin search, history).
            Booking trace = new Booking();
            trace.setRoom(room);
            trace.setUser(resolveUser(room, event.creatorEmail()));
            trace.setTitle(title(event).startsWith(REJECT_PREFIX) ? title(event) : REJECT_PREFIX + title(event));
            trace.setDescription(event.description());
            trace.setStartTime(event.start());
            trace.setEndTime(event.end());
            trace.setStatus(BookingStatus.CANCELLED);
            trace.setCancelledAt(clock.instant());
            trace.setGoogleEventId(event.id());
            trace.setBufferMinutes(bufferMinutes(room));
            trace.setSource(pxt.dki.booking.BookingSource.GOOGLE);
            bookingRepository.save(trace);
            return true;
        }
        Booking b = new Booking();
        b.setRoom(room);
        b.setUser(resolveUser(room, event.creatorEmail()));
        b.setTitle(CALENDAR_PREFIX + title(event));
        b.setDescription(event.description());
        b.setStartTime(event.start());
        b.setEndTime(event.end());
        b.setStatus(BookingStatus.ACTIVE);
        b.setGoogleEventId(event.id());
        b.setBufferMinutes(bufferMinutes(room));
        // born in Google: the creator has the event already, so no personal copy for the organizer
        b.setSource(pxt.dki.booking.BookingSource.GOOGLE);
        bookingRepository.save(b);
        log.info("Imported booking from Google: '{}' in room '{}'", b.getTitle(), room.getName());
        return true;
    }

    private boolean overlapsOthers(Room room, Instant start, Instant end, UUID excludeId) {
        return !conflicting(room, start, end, excludeId).isEmpty();
    }

    /** ACTIVE bookings of the room that conflict with [start, end), buffers included. */
    private List<Booking> conflicting(Room room, Instant start, Instant end, UUID excludeId) {
        Duration gap = BookingRuleValidator.BOOKING_GAP;
        int newBuffer = bufferMinutes(room);
        return bookingRepository
                .findActiveOverlapping(room.getId(), start.minus(gap), end.plus(gap), excludeId)
                .stream()
                .filter(b -> BookingRuleValidator.conflicts(
                        b.getStartTime(), b.getEndTime(), b.getBufferMinutes(), start, end, newBuffer))
                .toList();
    }

    private static int bufferMinutes(Room room) {
        return room.isBufferEnabled() ? (int) BookingRuleValidator.BOOKING_GAP.toMinutes() : 0;
    }

    private String title(RemoteCalendarEvent event) {
        return event.summary() == null || event.summary().isBlank() ? "(Google Calendar)" : event.summary();
    }

    /**
     * Booker resolution for an imported event: the Google creator when their email exists here,
     * else the room's owning admin, else the system import user.
     */
    private User resolveUser(Room room, String creatorEmail) {
        if (creatorEmail != null) {
            Optional<User> match = userRepository.findByEmailIgnoreCase(creatorEmail);
            if (match.isPresent()) {
                return match.get();
            }
        }
        if (room.getAdminUserId() != null) {
            Optional<User> admin = userRepository.findById(room.getAdminUserId());
            if (admin.isPresent()) {
                return admin.get();
            }
        }
        return userRepository.findByEmailIgnoreCase(IMPORT_USER_EMAIL).orElseGet(() -> {
            User u = new User();
            u.setEmail(IMPORT_USER_EMAIL);
            u.setFullName("Google Calendar Import");
            u.setPasswordHash("{imported}"); // never a valid BCrypt hash: login impossible
            u.setRole(Role.USER);
            u.setActive(false); // cannot log in; exists only to own imported bookings
            return userRepository.save(u);
        });
    }
}
