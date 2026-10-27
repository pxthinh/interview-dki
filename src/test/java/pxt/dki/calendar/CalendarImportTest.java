package pxt.dki.calendar;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import pxt.dki.AbstractIntegrationTest;
import pxt.dki.TestAuth;
import pxt.dki.TestData;
import pxt.dki.booking.Booking;
import pxt.dki.booking.BookingRepository;
import pxt.dki.booking.BookingStatus;
import pxt.dki.room.Room;
import pxt.dki.room.RoomRepository;
import pxt.dki.user.UserRepository;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Reverse sync: events created/cancelled directly on Google Calendar land in the database. */
@TestPropertySource(properties = "app.calendar.enabled=true")
@Import(CalendarImportTest.FakeClientConfig.class)
class CalendarImportTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class FakeClientConfig {
        @Bean
        @Primary
        FakeGoogleCalendarClient fakeGoogleCalendarClient() {
            return new FakeGoogleCalendarClient();
        }
    }

    @Autowired
    FakeGoogleCalendarClient fake;

    @Autowired
    CalendarImportJob importJob;

    @Autowired
    BookingRepository bookingRepository;

    @Autowired
    RoomRepository roomRepository;

    @Autowired
    UserRepository userRepository;

    private Room room;
    private String calId;
    private LocalDate day;

    @BeforeEach
    void setUp() {
        fake.reset();
        // a dedicated room + calendar per test so shared-DB state never interferes
        room = new Room();
        room.setName("Import-" + UUID.randomUUID().toString().substring(0, 8));
        room.setCapacity(6);
        calId = "cal-import-" + UUID.randomUUID().toString().substring(0, 8);
        room.setGoogleCalendarId(calId);
        room = roomRepository.save(room);
        day = TestData.nextWeekday().plusDays(56);
    }

    @Test
    void remoteEventBecomesBooking_creatorMatchedByEmail() {
        fake.addRemoteEvent(calId, "gev-1", "Họp từ Google",
                TestData.at(day, 9, 0).toInstant(), TestData.at(day, 10, 0).toInstant(),
                TestAuth.ALICE);

        int applied = importJob.runOnce();

        assertThat(applied).isEqualTo(1);
        Booking b = bookingRepository.findByGoogleEventId("gev-1").orElseThrow();
        assertThat(b.getTitle()).isEqualTo(CalendarImportJob.CALENDAR_PREFIX + "Họp từ Google");
        assertThat(b.getStatus()).isEqualTo(BookingStatus.ACTIVE);
        assertThat(userRepository.findById(b.getUser().getId()).orElseThrow().getEmail())
                .isEqualTo(TestAuth.ALICE);
        // sync token stored -> second run sees no new changes
        assertThat(importJob.runOnce()).isZero();
    }

    @Test
    void unknownCreator_fallsBackToImportUser() {
        fake.addRemoteEvent(calId, "gev-2", "Khách ngoài",
                TestData.at(day, 11, 0).toInstant(), TestData.at(day, 12, 0).toInstant(),
                "someone@gmail.com");

        importJob.runOnce();

        Booking b = bookingRepository.findByGoogleEventId("gev-2").orElseThrow();
        assertThat(userRepository.findById(b.getUser().getId()).orElseThrow().getEmail())
                .isEqualTo(CalendarImportJob.IMPORT_USER_EMAIL);
    }

    @Test
    void unknownCreator_roomWithAdmin_bookedAsRoomAdmin() {
        var admin = userRepository.findByEmailIgnoreCase(TestAuth.ADMIN).orElseThrow();
        room.setAdminUserId(admin.getId());
        room = roomRepository.save(room);
        fake.addRemoteEvent(calId, "gev-admin", "Khách đặt hộ",
                TestData.at(day, 18, 0).toInstant(), TestData.at(day, 19, 0).toInstant(),
                "stranger@gmail.com");

        importJob.runOnce();

        var b = bookingRepository.findByGoogleEventId("gev-admin").orElseThrow();
        assertThat(userRepository.findById(b.getUser().getId()).orElseThrow().getEmail())
                .isEqualTo(TestAuth.ADMIN);
    }

    @Test
    void remoteCancellation_cancelsBooking() {
        fake.addRemoteEvent(calId, "gev-3", "Sẽ hủy",
                TestData.at(day, 13, 0).toInstant(), TestData.at(day, 14, 0).toInstant(), null);
        importJob.runOnce();
        fake.cancelRemoteEvent(calId, "gev-3");

        importJob.runOnce();

        assertThat(bookingRepository.findByGoogleEventId("gev-3").orElseThrow().getStatus())
                .isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void overlappingRemoteEvent_skipped_dbWins() throws Exception {
        // existing DB booking 15:00-16:00
        String alice = TestAuth.token(mockMvc, objectMapper, TestAuth.ALICE, TestAuth.PASSWORD_USER);
        mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomId":"%s","title":"DB first","startTime":"%s","endTime":"%s"}"""
                                .formatted(room.getId(), TestData.at(day, 15, 0), TestData.at(day, 16, 0))))
                .andExpect(status().isCreated());

        fake.addRemoteEvent(calId, "gev-4", "Lấn giờ",
                TestData.at(day, 15, 30).toInstant(), TestData.at(day, 16, 30).toInstant(), null);

        importJob.runOnce();

        // stored as a CANCELLED trace with the rejection label: visible in the app,
        // but it does not hold the room
        Booking trace = bookingRepository.findByGoogleEventId("gev-4").orElseThrow();
        assertThat(trace.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(trace.getTitle()).startsWith(CalendarImportJob.REJECT_PREFIX);
        assertThat(bookingRepository.findActiveOverlapping(room.getId(),
                TestData.at(day, 15, 30).toInstant(), TestData.at(day, 16, 30).toInstant(), null))
                .hasSize(1); // only the original DB booking holds the slot
        // the stray event gets stamped on Google so the calendar explains itself
        assertThat(fake.calls).anyMatch(c -> c.startsWith("update:" + calId + ":gev-4"));

        // the stamp echoes back through the feed; it must not be stamped again
        fake.calls.clear();
        fake.addRemoteEvent(calId, "gev-4", CalendarImportJob.REJECT_PREFIX + "Lấn giờ",
                TestData.at(day, 15, 30).toInstant(), TestData.at(day, 16, 30).toInstant(), null);
        importJob.runOnce();
        assertThat(fake.calls).noneMatch(c -> c.startsWith("update:"));
    }

    @Test
    void ourOwnOutboxEvent_echoesBack_noDuplicate() throws Exception {
        String alice = TestAuth.token(mockMvc, objectMapper, TestAuth.ALICE, TestAuth.PASSWORD_USER);
        String body = mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomId":"%s","title":"App booking","startTime":"%s","endTime":"%s"}"""
                                .formatted(room.getId(), TestData.at(day, 17, 0), TestData.at(day, 18, 0))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID bookingId = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        // simulate: outbox pushed it to Google, which later echoes it in the change feed
        Booking b = bookingRepository.findById(bookingId).orElseThrow();
        b.setGoogleEventId("gev-echo");
        bookingRepository.save(b);
        fake.addRemoteEvent(calId, "gev-echo", "App booking",
                TestData.at(day, 17, 0).toInstant(), TestData.at(day, 18, 0).toInstant(), TestAuth.ALICE);

        int applied = importJob.runOnce();

        assertThat(applied).isZero();
        assertThat(bookingRepository.findActiveOverlapping(room.getId(),
                TestData.at(day, 17, 0).toInstant(), TestData.at(day, 18, 0).toInstant(), null)).hasSize(1);
    }

    @Test
    void preSync_freshCalendarEvent_blocksWebBookingImmediately() throws Exception {
        // event created on the calendar but NOT yet polled in (no runOnce here)
        fake.addRemoteEvent(calId, "gev-race", "Vua tao tren calendar",
                TestData.at(day, 19, 0).toInstant(), TestData.at(day, 20, 0).toInstant(), null);

        // booking the same slot via the API triggers the controller's pre-sync,
        // which imports the event first -> the web booking gets 409
        String alice = TestAuth.token(mockMvc, objectMapper, TestAuth.ALICE, TestAuth.PASSWORD_USER);
        mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomId":"%s","title":"Web sau","startTime":"%s","endTime":"%s"}"""
                                .formatted(room.getId(), TestData.at(day, 19, 0), TestData.at(day, 20, 0))))
                .andExpect(status().isConflict());
        assertThat(bookingRepository.findByGoogleEventId("gev-race")).isPresent();
    }

    @Test
    void preSync_googleDown_bookingStillSucceeds() throws Exception {
        fake.alwaysFail.set(true);
        String alice = TestAuth.token(mockMvc, objectMapper, TestAuth.ALICE, TestAuth.PASSWORD_USER);
        mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomId":"%s","title":"Google chet van dat duoc","startTime":"%s","endTime":"%s"}"""
                                .formatted(room.getId(), TestData.at(day, 6 + 1, 0), TestData.at(day, 6 + 1, 45))))
                .andExpect(status().isCreated());
        fake.alwaysFail.set(false);
    }

    @Test
    void importedBooking_prefixedEcho_noDoublePrefix_renameKeepsPrefix() {
        fake.addRemoteEvent(calId, "gev-pfx", "Standup",
                TestData.at(day, 6, 0).toInstant(), TestData.at(day, 6, 45).toInstant(), null);
        importJob.runOnce();
        Booking b = bookingRepository.findByGoogleEventId("gev-pfx").orElseThrow();
        assertThat(b.getTitle()).isEqualTo(CalendarImportJob.CALENDAR_PREFIX + "Standup");

        // app edits the imported booking -> outbox pushes "[CALENDAR] Standup" -> feed echoes it
        fake.addRemoteEvent(calId, "gev-pfx", CalendarImportJob.CALENDAR_PREFIX + "Standup",
                TestData.at(day, 6, 0).toInstant(), TestData.at(day, 6, 45).toInstant(), null);
        assertThat(importJob.runOnce()).isZero();
        assertThat(bookingRepository.findByGoogleEventId("gev-pfx").orElseThrow().getTitle())
                .isEqualTo(CalendarImportJob.CALENDAR_PREFIX + "Standup");

        // renamed on Google -> keeps the prefix on the new name
        fake.addRemoteEvent(calId, "gev-pfx", "Retro",
                TestData.at(day, 6, 0).toInstant(), TestData.at(day, 6, 45).toInstant(), null);
        importJob.runOnce();
        assertThat(bookingRepository.findByGoogleEventId("gev-pfx").orElseThrow().getTitle())
                .isEqualTo(CalendarImportJob.CALENDAR_PREFIX + "Retro");
    }

    @Test
    void remoteMove_updatesBookingTimes() {
        fake.addRemoteEvent(calId, "gev-5", "Dời giờ",
                TestData.at(day, 8, 0).toInstant(), TestData.at(day, 9, 0).toInstant(), null);
        importJob.runOnce();
        // moved on Google to 10:00-11:00
        fake.addRemoteEvent(calId, "gev-5", "Dời giờ",
                TestData.at(day, 10, 0).toInstant(), TestData.at(day, 11, 0).toInstant(), null);

        importJob.runOnce();

        Booking b = bookingRepository.findByGoogleEventId("gev-5").orElseThrow();
        assertThat(b.getStartTime()).isEqualTo(TestData.at(day, 10, 0).toInstant());
    }
}
