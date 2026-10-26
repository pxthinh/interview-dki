package pxt.dki.booking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID>, JpaSpecificationExecutor<Booking> {

    /**
     * ACTIVE bookings of a room overlapping [start, end), optionally excluding one booking
     * (used when updating a booking so it does not clash with itself).
     */
    @Query("""
            select b from Booking b where b.room.id = :roomId and b.status = pxt.dki.booking.BookingStatus.ACTIVE
              and b.startTime < :end and b.endTime > :start
              and (:excludeId is null or b.id <> :excludeId)
            order by b.startTime""")
    List<Booking> findActiveOverlapping(@Param("roomId") UUID roomId,
                                        @Param("start") Instant start,
                                        @Param("end") Instant end,
                                        @Param("excludeId") UUID excludeId);

    List<Booking> findByRoomIdInAndStatusAndStartTimeLessThanAndEndTimeGreaterThan(
            java.util.Collection<UUID> roomIds, BookingStatus status, Instant before, Instant after);

    /**
     * Targeted write used by the calendar outbox processor: stores the Google event id without
     * flushing a possibly stale full-row snapshot over concurrent user changes.
     */
    @Modifying
    @Query("update Booking b set b.googleEventId = :eventId where b.id = :id")
    void updateGoogleEventId(@Param("id") UUID id, @Param("eventId") String eventId);
}
