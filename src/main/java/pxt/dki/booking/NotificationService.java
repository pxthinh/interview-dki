package pxt.dki.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import pxt.dki.common.time.BusinessTime;
import pxt.dki.user.User;

import java.time.format.DateTimeFormatter;
import java.util.Collection;

/**
 * Best-effort email notifications to meeting participants. Runs async and never fails the
 * booking flow: a dead SMTP server only produces warn logs. Disabled by default
 * (app.notifications.enabled=false); docker compose ships Mailpit as the SMTP sink.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

    private final JavaMailSender mailSender;
    private final boolean enabled;
    private final String from;

    public NotificationService(JavaMailSender mailSender,
                               @Value("${app.notifications.enabled}") boolean enabled,
                               @Value("${app.notifications.from}") String from) {
        this.mailSender = mailSender;
        this.enabled = enabled;
        this.from = from;
    }

    @Async
    public void bookingCreated(BookingSnapshot b, Collection<User> invitees) {
        send(invitees, "[DKI Rooms] Mời họp: " + b.title(),
                """
                %s mời bạn tham gia cuộc họp.

                Cuộc họp: %s
                Phòng: %s
                Thời gian: %s - %s
                """.formatted(b.organizer(), b.title(), b.room(), start(b), end(b)));
    }

    @Async
    public void bookingConfirmed(BookingSnapshot b, User organizer) {
        send(java.util.List.of(organizer), "[DKI Rooms] Đặt phòng thành công: " + b.title(),
                """
                Bạn đã đặt phòng thành công.

                Cuộc họp: %s
                Phòng: %s
                Thời gian: %s - %s
                """.formatted(b.title(), b.room(), start(b), end(b)));
    }

    @Async
    public void bookingCancelled(BookingSnapshot b, Collection<User> participants) {
        send(participants, "[DKI Rooms] HỦY họp: " + b.title(),
                """
                Cuộc họp sau đã bị hủy.

                Cuộc họp: %s
                Phòng: %s
                Thời gian: %s - %s
                """.formatted(b.title(), b.room(), start(b), end(b)));
    }

    @Async
    public void bookingUpdated(BookingSnapshot b, Collection<User> participants) {
        send(participants, "[DKI Rooms] Cập nhật họp: " + b.title(),
                """
                Cuộc họp bạn tham gia vừa được cập nhật.

                Cuộc họp: %s
                Phòng: %s
                Thời gian mới: %s - %s
                """.formatted(b.title(), b.room(), start(b), end(b)));
    }

    @Async
    public void participantRemoved(BookingSnapshot b, Collection<User> removed) {
        send(removed, "[DKI Rooms] Bạn được rút khỏi cuộc họp: " + b.title(),
                """
                Bạn không còn trong danh sách tham gia cuộc họp.

                Cuộc họp: %s
                Phòng: %s
                Thời gian: %s - %s
                """.formatted(b.title(), b.room(), start(b), end(b)));
    }

    private void send(Collection<User> recipients, String subject, String body) {
        if (!enabled || recipients.isEmpty()) {
            return;
        }
        for (User user : recipients) {
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(from);
                message.setTo(user.getEmail());
                message.setSubject(subject);
                message.setText(body);
                mailSender.send(message);
            } catch (Exception e) {
                log.warn("Could not send '{}' to {}: {}", subject, user.getEmail(), e.toString());
            }
        }
    }

    private static String start(BookingSnapshot b) {
        return FMT.format(b.start().atZone(BusinessTime.ZONE));
    }

    private static String end(BookingSnapshot b) {
        return FMT.format(b.end().atZone(BusinessTime.ZONE));
    }

    /** Immutable mail payload captured inside the transaction, safe to use on the async thread. */
    public record BookingSnapshot(String title, String room, String organizer,
                                  java.time.Instant start, java.time.Instant end) {

        public static BookingSnapshot of(Booking b) {
            return new BookingSnapshot(b.getTitle(), b.getRoom().getName(),
                    b.getUser().getFullName(), b.getStartTime(), b.getEndTime());
        }
    }
}
