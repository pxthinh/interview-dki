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
import pxt.dki.booking.BookingRepository;
import pxt.dki.room.RoomRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = "app.calendar.enabled=true")
@Import(CalendarOutboxProcessorTest.FakeClientConfig.class)
class CalendarOutboxProcessorTest extends AbstractIntegrationTest {

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
    CalendarOutboxProcessor processor;

    @Autowired
    CalendarOutboxRepository outboxRepository;

    @Autowired
    BookingRepository bookingRepository;

    @Autowired
    RoomRepository roomRepository;

    private static final java.util.concurrent.atomic.AtomicInteger HOUR =
            new java.util.concurrent.atomic.AtomicInteger(7);

    private String alice;
    private UUID earth;
    private LocalDate day;

    @BeforeEach
    void setUp() throws Exception {
        fake.reset();
        outboxRepository.deleteAll();
        alice = TestAuth.token(mockMvc, objectMapper, TestAuth.ALICE, TestAuth.PASSWORD_USER);
        var earthRoom = roomRepository.findByNameIgnoreCase("Earth").orElseThrow();
        earthRoom.setGoogleCalendarId("cal-earth");
        roomRepository.save(earthRoom);
        earth = earthRoom.getId();
        day = TestData.nextWeekday().plusDays(28);
    }

    /** Books the next free hour slot on Earth and returns the booking id. */
    private UUID book() throws Exception {
        int h = HOUR.getAndIncrement();
        String body = mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomId":"%s","title":"Synced","startTime":"%s","endTime":"%s"}"""
                                .formatted(earth, TestData.at(day, h, 0), TestData.at(day, h + 1, 0))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    @Test
    void createBooking_enqueuesCreate_processorStoresEventId() throws Exception {
        UUID id = book();

        List<CalendarOutbox> rows = outboxRepository.findByBookingIdOrderById(id);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(OutboxStatus.PENDING);

        processor.processBatch();

        assertThat(outboxRepository.findByBookingIdOrderById(id).get(0).getStatus())
                .isEqualTo(OutboxStatus.DONE);
        assertThat(bookingRepository.findById(id).orElseThrow().getGoogleEventId()).startsWith("evt-");
        assertThat(fake.calls).containsExactly("create:cal-earth");
    }

    @Test
    void roomWithoutCalendarId_noOutboxRow() throws Exception {
        var venus = roomRepository.findByNameIgnoreCase("Venus").orElseThrow();
        assertThat(venus.getGoogleCalendarId()).isNull();
        mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomId":"%s","title":"No sync","startTime":"%s","endTime":"%s"}"""
                                .formatted(venus.getId(), TestData.at(day, 18, 0), TestData.at(day, 19, 0))))
                .andExpect(status().isCreated());
        assertThat(outboxRepository.count()).isZero();
    }

    @Test
    void updateBeforeCreateDone_staysPending_thenProcessedInOrder() throws Exception {
        UUID id = book();
        int h = HOUR.getAndIncrement();
        mockMvc.perform(put("/api/v1/bookings/" + id)
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Synced v2","startTime":"%s","endTime":"%s"}"""
                                .formatted(TestData.at(day, h, 0), TestData.at(day, h + 1, 0))))
                .andExpect(status().isOk());

        // first pass: CREATE succeeds; UPDATE may run in the same ordered batch after it
        processor.processBatch();
        processor.processBatch();

        List<CalendarOutbox> rows = outboxRepository.findByBookingIdOrderById(id);
        assertThat(rows).hasSize(2);
        assertThat(rows).allMatch(r -> r.getStatus() == OutboxStatus.DONE);
        assertThat(fake.calls.get(0)).startsWith("create:");
        assertThat(fake.calls.get(1)).startsWith("update:");
    }

    @Test
    void cancelBeforeCreateFinished_skipsGoogleEntirely() throws Exception {
        UUID id = book();
        mockMvc.perform(post("/api/v1/bookings/" + id + "/cancel")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk());

        processor.processBatch();
        processor.processBatch();

        List<CalendarOutbox> rows = outboxRepository.findByBookingIdOrderById(id);
        assertThat(rows).hasSize(2);
        assertThat(rows).allMatch(r -> r.getStatus() == OutboxStatus.DONE);
        // CREATE skipped ("booking cancelled"), CANCEL had no event id -> no Google call at all
        assertThat(fake.calls).isEmpty();
    }

    @Test
    void googleFailure_retries_thenFailed_thenAdminRetrySucceeds() throws Exception {
        UUID id = book();
        fake.alwaysFail.set(true);

        // 6 passes; backoff pushes next_attempt_at forward, so re-arm the row each pass
        for (int i = 0; i < 6; i++) {
            processor.processBatch();
            var row = outboxRepository.findByBookingIdOrderById(id).get(0);
            if (row.getStatus() == OutboxStatus.PENDING) {
                row.setNextAttemptAt(java.time.Instant.now());
                outboxRepository.save(row);
            }
        }
        var failed = outboxRepository.findByBookingIdOrderById(id).get(0);
        assertThat(failed.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(6);
        assertThat(failed.getLastError()).contains("simulated google outage");

        // admin retry
        fake.alwaysFail.set(false);
        String admin = TestAuth.token(mockMvc, objectMapper, TestAuth.ADMIN, TestAuth.PASSWORD_ADMIN);
        mockMvc.perform(post("/api/v1/admin/calendar-outbox/" + failed.getId() + "/retry")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        processor.processBatch();
        assertThat(outboxRepository.findByBookingIdOrderById(id).get(0).getStatus())
                .isEqualTo(OutboxStatus.DONE);
    }

    @Test
    void cancelWhenGoogleReturns404_isDone() throws Exception {
        UUID id = book();
        processor.processBatch(); // CREATE done, event id stored

        mockMvc.perform(post("/api/v1/bookings/" + id + "/cancel")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk());
        fake.notFoundOnDelete.set(true);
        processor.processBatch();

        List<CalendarOutbox> rows = outboxRepository.findByBookingIdOrderById(id);
        assertThat(rows.get(1).getAction()).isEqualTo(OutboxAction.CANCEL);
        assertThat(rows.get(1).getStatus()).isEqualTo(OutboxStatus.DONE);
    }
}
