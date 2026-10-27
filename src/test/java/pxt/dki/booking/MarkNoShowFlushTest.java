package pxt.dki.booking;

import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import pxt.dki.room.Room;
import pxt.dki.room.RoomRepository;
import pxt.dki.user.User;
import pxt.dki.user.UserRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The no-show release must hit the optimistic-lock check (flush) BEFORE it enqueues the
 * calendar CANCEL or sends the email: a user cancelling or checking in concurrently rolls the
 * sweeper back, and nothing may have leaked out of that transaction.
 */
class MarkNoShowFlushTest {

    @Test
    void optimisticLockAtFlush_sendsNoEmail_enqueuesNothing() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-03T03:00:00Z");

        Room room = new Room();
        room.setName("Mars");
        User user = new User();
        user.setFullName("Alice");

        Booking booking = new Booking();
        booking.setRoom(room);
        booking.setUser(user);
        booking.setTitle("Standup");
        booking.setStatus(BookingStatus.ACTIVE);
        booking.setStartTime(now.minus(Duration.ofMinutes(20)));
        booking.setEndTime(now.plus(Duration.ofMinutes(40)));

        BookingRepository bookingRepository = mock(BookingRepository.class);
        when(bookingRepository.findById(id)).thenReturn(Optional.of(booking));
        when(bookingRepository.saveAndFlush(any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(Booking.class, id));

        CalendarSyncPort calendarSync = mock(CalendarSyncPort.class);
        NotificationService notifications = mock(NotificationService.class);

        BookingService service = new BookingService(
                bookingRepository,
                mock(RecurringSeriesRepository.class),
                mock(RoomRepository.class),
                mock(UserRepository.class),
                mock(BookingRuleValidator.class),
                calendarSync,
                notifications,
                Clock.fixed(now, ZoneOffset.UTC),
                new CheckinProperties(true, 15, 5, 60000));

        assertThatThrownBy(() -> service.markNoShow(id))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        verifyNoInteractions(notifications);
        verify(calendarSync, never()).enqueue(any(), any());
    }
}
