package pxt.dki.calendar;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface BookingUserEventRepository extends JpaRepository<BookingUserEvent, BookingUserEventId> {

    List<BookingUserEvent> findByBookingId(UUID bookingId);

    List<BookingUserEvent> findByUserId(UUID userId);

    /** Drops mappings created in a different Google account than the one now connected. */
    @Modifying
    @Query("""
            delete from BookingUserEvent m
            where m.userId = :userId and (m.googleEmail is null or lower(m.googleEmail) <> lower(:email))""")
    int deleteStaleForUser(@Param("userId") UUID userId, @Param("email") String email);
}
