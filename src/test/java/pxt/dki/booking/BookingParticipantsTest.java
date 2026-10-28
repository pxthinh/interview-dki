package pxt.dki.booking;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import pxt.dki.AbstractIntegrationTest;
import pxt.dki.TestAuth;
import pxt.dki.TestData;
import pxt.dki.user.UserRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = "app.notifications.enabled=true")
@Import(BookingParticipantsTest.FakeMailConfig.class)
class BookingParticipantsTest extends AbstractIntegrationTest {

    /** Captures outgoing mail instead of talking SMTP. */
    static class CapturingMailSender implements JavaMailSender {
        final List<SimpleMailMessage> sent = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public void send(SimpleMailMessage message) {
            sent.add(message);
        }

        @Override
        public void send(SimpleMailMessage... messages) {
            sent.addAll(List.of(messages));
        }

        @Override
        public MimeMessage createMimeMessage() {
            throw new UnsupportedOperationException();
        }

        @Override
        public MimeMessage createMimeMessage(java.io.InputStream contentStream) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void send(MimeMessage mimeMessage) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void send(MimeMessage... mimeMessages) {
            throw new UnsupportedOperationException();
        }

        List<String> recipientsWithSubjectContaining(String needle) {
            synchronized (sent) {
                return sent.stream()
                        .filter(m -> m.getSubject() != null && m.getSubject().contains(needle))
                        .map(m -> m.getTo()[0])
                        .toList();
            }
        }
    }

    @TestConfiguration
    static class FakeMailConfig {
        @Bean
        @Primary
        CapturingMailSender capturingMailSender() {
            return new CapturingMailSender();
        }
    }

    @Autowired
    CapturingMailSender mail;

    @Autowired
    UserRepository userRepository;

    @Autowired
    BookingService bookingService;

    @Autowired
    BookingRepository bookingRepository;

    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactionManager;

    private String alice;
    private UUID bobId;
    private UUID carolId;
    private UUID room;
    private LocalDate day;

    @BeforeEach
    void setUp() throws Exception {
        mail.sent.clear();
        alice = TestAuth.token(mockMvc, objectMapper, TestAuth.ALICE, TestAuth.PASSWORD_USER);
        bobId = userRepository.findByEmailIgnoreCase(TestAuth.BOB).orElseThrow().getId();
        carolId = userRepository.findByEmailIgnoreCase("carol@dki.local").orElseThrow().getId();
        String admin = TestAuth.token(mockMvc, objectMapper, TestAuth.ADMIN, TestAuth.PASSWORD_ADMIN);
        String body = mockMvc.perform(post("/api/v1/rooms")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Part-" + UUID.randomUUID() + "\",\"capacity\":8}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        room = UUID.fromString(objectMapper.readTree(body).get("id").asText());
        day = TestData.nextWeekday().plusDays(63);
    }

    private String create(List<UUID> participants, int sh, int eh) throws Exception {
        String ids = participants.stream().map(u -> "\"" + u + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        String body = mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomId":"%s","title":"Team sync","startTime":"%s","endTime":"%s",
                                 "participantIds":[%s]}"""
                                .formatted(room, TestData.at(day, sh, 0), TestData.at(day, eh, 0), ids)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        // mails go out after commit, on the async executor: let them land before a test clears the inbox
        await().untilAsserted(() -> {
            assertThat(mail.recipientsWithSubjectContaining("Đặt phòng thành công")).containsExactly(TestAuth.ALICE);
            assertThat(mail.recipientsWithSubjectContaining("Mời họp")).hasSize(participants.size());
        });
        return body;
    }

    @Test
    void create_withParticipants_sendsInvites_andReturnsThem() throws Exception {
        String body = create(List.of(bobId, carolId), 9, 10);

        assertThat(objectMapper.readTree(body).get("participants")).hasSize(2);
        await().untilAsserted(() -> {
            assertThat(mail.recipientsWithSubjectContaining("Mời họp"))
                    .containsExactlyInAnyOrder(TestAuth.BOB, "carol@dki.local");
            // the organizer always gets a confirmation mail
            assertThat(mail.recipientsWithSubjectContaining("Đặt phòng thành công"))
                    .containsExactly(TestAuth.ALICE);
        });
    }

    @Test
    void cancel_notifiesParticipants() throws Exception {
        String id = objectMapper.readTree(create(List.of(bobId), 11, 12)).get("id").asText();
        mail.sent.clear();

        mockMvc.perform(post("/api/v1/bookings/" + id + "/cancel")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk());

        await().untilAsserted(() ->
                assertThat(mail.recipientsWithSubjectContaining("HỦY họp")).containsExactly(TestAuth.BOB));
    }

    @Test
    void update_notifiesAddedRemovedAndKept() throws Exception {
        String id = objectMapper.readTree(create(List.of(bobId), 13, 14)).get("id").asText();
        mail.sent.clear();

        // keep bob? no: replace bob with carol while keeping times -> bob removed, carol invited
        mockMvc.perform(put("/api/v1/bookings/" + id)
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Team sync","startTime":"%s","endTime":"%s",
                                 "participantIds":["%s"]}"""
                                .formatted(TestData.at(day, 13, 0), TestData.at(day, 14, 0), carolId)))
                .andExpect(status().isOk());

        await().untilAsserted(() -> {
            assertThat(mail.recipientsWithSubjectContaining("Mời họp")).containsExactly("carol@dki.local");
            assertThat(mail.recipientsWithSubjectContaining("rút khỏi")).containsExactly(TestAuth.BOB);
        });

        mail.sent.clear();
        // move the meeting with carol kept -> she gets the update mail
        mockMvc.perform(put("/api/v1/bookings/" + id)
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Team sync","startTime":"%s","endTime":"%s",
                                 "participantIds":["%s"]}"""
                                .formatted(TestData.at(day, 15, 0), TestData.at(day, 16, 0), carolId)))
                .andExpect(status().isOk());

        await().untilAsserted(() ->
                assertThat(mail.recipientsWithSubjectContaining("Cập nhật họp")).containsExactly("carol@dki.local"));
    }

    @Test
    void update_participantIdsOmitted_keeps_emptyList_clears() throws Exception {
        String id = objectMapper.readTree(create(List.of(bobId), 9, 10)).get("id").asText();

        // participantIds omitted -> bob stays
        String kept = mockMvc.perform(put("/api/v1/bookings/" + id)
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Team sync","startTime":"%s","endTime":"%s"}"""
                                .formatted(TestData.at(day, 9, 0), TestData.at(day, 10, 0))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(kept).get("participants")).hasSize(1);
        assertThat(objectMapper.readTree(kept).get("participants").get(0).get("email").asText())
                .isEqualTo(TestAuth.BOB);

        // explicit empty list -> cleared
        String cleared = mockMvc.perform(put("/api/v1/bookings/" + id)
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Team sync","startTime":"%s","endTime":"%s","participantIds":[]}"""
                                .formatted(TestData.at(day, 9, 0), TestData.at(day, 10, 0))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(cleared).get("participants")).isEmpty();
    }

    @Test
    void rolledBackTransaction_sendsNoMail() throws Exception {
        UUID aliceId = userRepository.findByEmailIgnoreCase(TestAuth.ALICE).orElseThrow().getId();
        var actor = new pxt.dki.auth.AuthUser(aliceId, TestAuth.ALICE, pxt.dki.user.Role.USER);
        var request = new pxt.dki.booking.dto.CreateBookingRequest(room, "Doomed", null, null,
                TestData.at(day, 11, 0), TestData.at(day, 12, 0), List.of(bobId));

        // create inside a transaction that is then rolled back: the mails were requested
        // inside the tx, so none may go out
        new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    bookingService.create(actor, request);
                    status.setRollbackOnly();
                });

        await().pollDelay(java.time.Duration.ofMillis(500)).untilAsserted(() -> assertThat(mail.sent).isEmpty());
        assertThat(bookingRepository.findAll()).noneMatch(b -> "Doomed".equals(b.getTitle()));
    }

    @Test
    void unknownParticipant_422() throws Exception {
        mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomId":"%s","title":"Bad","startTime":"%s","endTime":"%s",
                                 "participantIds":["%s"]}"""
                                .formatted(room, TestData.at(day, 17, 0), TestData.at(day, 18, 0), UUID.randomUUID())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("PARTICIPANT_NOT_FOUND"));
    }

    @Test
    void directory_availableToEveryUser() throws Exception {
        mockMvc.perform(get("/api/v1/users/directory").header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].email").value(org.hamcrest.Matchers.hasItem(TestAuth.BOB)));
    }
}
