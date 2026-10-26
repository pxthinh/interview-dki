package pxt.dki.calendar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import pxt.dki.booking.Booking;
import pxt.dki.booking.BookingRepository;
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
 * never through BookingService — so the import cannot enqueue outbox rows and bounce the same
 * change back to Google.
 *
 * An imported event that would double-book the room (15-minute break included) is skipped and
 * logged: the DB booking wins, the stray Google event stays on the calendar for humans to see.
 * All-day events (no concrete start/end time) are ignored.
 */
@Component
public class CalendarImportJob {

    private static final Logger log = LoggerFactory.getLogger(CalendarImportJob.class);
    static final String IMPORT_USER_EMAIL = "calendar-import@dki.local";

    private final RoomRepository roomRepository;
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final GoogleCalendarClient client;
    private final CalendarProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;

    public CalendarImportJob(RoomRepository roomRepository, BookingRepository bookingRepository,
                             UserRepository userRepository, GoogleCalendarClient client,
                             CalendarProperties properties, Clock clock, TransactionTemplate tx) {
        this.roomRepository = roomRepository;
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.client = client;
        this.properties = properties;
        this.clock = clock;
        this.tx = tx;
    }

    @Scheduled(fixedDelayString = "${app.calendar.import-interval-ms}")
    public void poll() {
        if (!properties.enabled()) {
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
            try {
                applied += syncRoom(room);
            } catch (SyncTokenExpiredException e) {
                log.warn("Sync token for room '{}' expired; resetting for a full resync", room.getName());
                tx.executeWithoutResult(s -> roomRepository.findById(room.getId()).ifPresent(r -> {
                    r.setGoogleSyncToken(null);
                    roomRepository.save(r);
                }));
            } catch (Exception e) {
                log.warn("Calendar import failed for room '{}': {}", room.getName(), e.toString());
            }
        }
        return applied;
    }

    private int syncRoom(Room room) {
        // Google call outside any transaction; applying the changes is a short transaction.
        RemoteEventPage page = client.listChanges(
                room.getGoogleCalendarId(), room.getGoogleSyncToken(), clock.instant());
        Integer applied = tx.execute(status -> {
            int count = 0;
            for (RemoteCalendarEvent event : page.events()) {
                if (applyEvent(room, event)) {
                    count++;
                }
            }
            roomRepository.findById(room.getId()).ifPresent(r -> {
                r.setGoogleSyncToken(page.nextSyncToken());
                roomRepository.save(r);
            });
            return count;
        });
        return applied == null ? 0 : applied;
    }

    /** @return true when the event changed something in the database */
    private boolean applyEvent(Room room, RemoteCalendarEvent event) {
        Optional<Booking> linked = bookingRepository.findByGoogleEventId(event.id());

        if (event.cancelled()) {
            if (linked.isPresent() && linked.get().getStatus() == BookingStatus.ACTIVE) {
                Booking b = linked.get();
                b.setStatus(BookingStatus.CANCELLED);
                b.setCancelledAt(clock.instant());
                bookingRepository.save(b);
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
            String summary = title(event);
            boolean sameTitle = summary.equals(b.getTitle());
            if (sameTimes && sameTitle) {
                return false; // our own outbox echo, or nothing changed
            }
            if (!sameTimes && overlapsOthers(room.getId(), event.start(), event.end(), b.getId())) {
                log.warn("Google edit of event {} overlaps another booking; keeping DB times", event.id());
                return false;
            }
            b.setTitle(summary);
            b.setStartTime(event.start());
            b.setEndTime(event.end());
            bookingRepository.save(b);
            log.info("Imported update from Google: booking {} ({})", b.getId(), b.getTitle());
            return true;
        }

        if (overlapsOthers(room.getId(), event.start(), event.end(), null)) {
            log.warn("Google event {} ('{}') overlaps an existing booking of room '{}'; skipped",
                    event.id(), event.summary(), room.getName());
            return false;
        }
        Booking b = new Booking();
        b.setRoom(room);
        b.setUser(resolveUser(room, event.creatorEmail()));
        b.setTitle(title(event));
        b.setDescription(event.description());
        b.setStartTime(event.start());
        b.setEndTime(event.end());
        b.setStatus(BookingStatus.ACTIVE);
        b.setGoogleEventId(event.id());
        bookingRepository.save(b);
        log.info("Imported booking from Google: '{}' in room '{}'", b.getTitle(), room.getName());
        return true;
    }

    private boolean overlapsOthers(UUID roomId, Instant start, Instant end, UUID excludeId) {
        Duration gap = BookingRuleValidator.BOOKING_GAP;
        return !bookingRepository
                .findActiveOverlapping(roomId, start.minus(gap), end.plus(gap), excludeId)
                .isEmpty();
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
