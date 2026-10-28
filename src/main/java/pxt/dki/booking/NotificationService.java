package pxt.dki.booking;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import pxt.dki.common.time.BusinessTime;
import pxt.dki.user.User;

import java.time.format.DateTimeFormatter;
import java.util.Collection;

/**
 * Best-effort email notifications to meeting participants. Runs async and never fails the
 * booking flow: a dead SMTP server only produces warn logs. Disabled by default
 * (app.notifications.enabled=false); docker compose ships Mailpit as the SMTP sink.
 * <p>
 * Called from inside a transaction, every notification is deferred to after commit: a
 * constraint or optimistic-lock failure at flush rolls the booking back and no mail about
 * it ever leaves. Outside a transaction it is handed to the async sender right away.
 */
@Service
public class NotificationService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

    private final NotificationSender sender;

    public NotificationService(NotificationSender sender) {
        this.sender = sender;
    }

    public void bookingCreated(BookingSnapshot b, Collection<User> invitees) {
        send(invitees, "[DKI Rooms] Mời họp: " + b.title(),
                """
                %s mời bạn tham gia cuộc họp.

                Cuộc họp: %s
                Phòng: %s
                Thời gian: %s - %s
                """.formatted(b.organizer(), b.title(), b.room(), start(b), end(b)));
    }

    public void bookingConfirmed(BookingSnapshot b, User organizer) {
        send(java.util.List.of(organizer), "[DKI Rooms] Đặt phòng thành công: " + b.title(),
                """
                Bạn đã đặt phòng thành công.

                Cuộc họp: %s
                Phòng: %s
                Thời gian: %s - %s
                """.formatted(b.title(), b.room(), start(b), end(b)));
    }

    public void bookingCancelled(BookingSnapshot b, Collection<User> participants) {
        send(participants, "[DKI Rooms] HỦY họp: " + b.title(),
                """
                Cuộc họp sau đã bị hủy.

                Cuộc họp: %s
                Phòng: %s
                Thời gian: %s - %s
                """.formatted(b.title(), b.room(), start(b), end(b)));
    }

    public void recurringCreated(BookingSnapshot first, int totalCreated, User organizer,
                                 Collection<User> participants) {
        String details = """
                Cuộc họp: %s
                Phòng: %s
                Lặp hàng tuần: %d buổi, buổi đầu %s - %s
                """.formatted(first.title(), first.room(), totalCreated, start(first), end(first));
        send(java.util.List.of(organizer), "[DKI Rooms] Đặt chuỗi họp hàng tuần: " + first.title(),
                "Bạn đã đặt chuỗi họp lặp hàng tuần thành công.\n\n" + details);
        send(participants, "[DKI Rooms] Mời họp hàng tuần: " + first.title(),
                first.organizer() + " mời bạn tham gia chuỗi họp lặp hàng tuần.\n\n" + details);
    }

    public void seriesCancelled(BookingSnapshot first, int totalCancelled, Collection<User> recipients) {
        send(recipients, "[DKI Rooms] HỦY chuỗi họp hàng tuần: " + first.title(),
                """
                Chuỗi họp lặp hàng tuần sau đã bị hủy (%d buổi còn lại).

                Cuộc họp: %s
                Phòng: %s
                Buổi gần nhất: %s - %s
                """.formatted(totalCancelled, first.title(), first.room(), start(first), end(first)));
    }

    public void bookingNoShow(BookingSnapshot b, Collection<User> recipients) {
        send(recipients, "[DKI Rooms] Thu hồi phòng (không check-in): " + b.title(),
                """
                Cuộc họp sau không được check-in trong thời gian quy định nên phòng đã được thu hồi
                cho người khác đặt.

                Cuộc họp: %s
                Phòng: %s
                Thời gian: %s - %s
                """.formatted(b.title(), b.room(), start(b), end(b)));
    }

    public void bookingUpdated(BookingSnapshot b, Collection<User> participants) {
        send(participants, "[DKI Rooms] Cập nhật họp: " + b.title(),
                """
                Cuộc họp bạn tham gia vừa được cập nhật.

                Cuộc họp: %s
                Phòng: %s
                Thời gian mới: %s - %s
                """.formatted(b.title(), b.room(), start(b), end(b)));
    }

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
        // copy: the caller's collection may be a managed JPA set that changes after the tx ends
        var to = java.util.List.copyOf(recipients);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sender.send(to, subject, body);
                }
            });
            return;
        }
        sender.send(to, subject, body);
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
