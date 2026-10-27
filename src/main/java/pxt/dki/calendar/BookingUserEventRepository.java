package pxt.dki.calendar;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BookingUserEventRepository extends JpaRepository<BookingUserEvent, BookingUserEventId> {

    List<BookingUserEvent> findByBookingId(UUID bookingId);
}
