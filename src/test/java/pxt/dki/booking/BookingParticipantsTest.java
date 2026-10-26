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
        return mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomId":"%s","title":"Team sync","startTime":"%s","endTime":"%s",
                                 "participantIds":[%s]}"""
                                .formatted(room, TestData.at(day, sh, 0), TestData.at(day, eh, 0), ids)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void create_withParticipants_sendsInvites_andReturnsThem() throws Exception {
        String body = create(List.of(bobId, carolId), 9, 10);

        assertThat(objectMapper.readTree(body).get("participants")).hasSize(2);
        await().untilAsserted(() ->
                assertThat(mail.recipientsWithSubjectContaining("Mời họp"))
                        .containsExactlyInAnyOrder(TestAuth.BOB, "carol@dki.local"));
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
