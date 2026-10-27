package pxt.dki.calendar;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CalendarOutboxRepository extends JpaRepository<CalendarOutbox, Long> {

    /**
     * Claims a batch of due PENDING rows. SKIP LOCKED keeps multiple app instances from
     * processing the same row; ordering by booking_id, id keeps actions of one booking in order.
     */
    @Query(value = """
            SELECT * FROM calendar_outbox
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY booking_id, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<CalendarOutbox> lockPendingBatch(@Param("now") Instant now, @Param("limit") int limit);

    /** The personal UPDATE-defer probe: is the copy's CREATE still waiting in the queue? */
    boolean existsByBookingIdAndTargetUserIdAndActionAndStatus(UUID bookingId, UUID targetUserId,
                                                               OutboxAction action, OutboxStatus status);

    List<CalendarOutbox> findByStatusOrderByIdDesc(OutboxStatus status);

    List<CalendarOutbox> findByBookingIdOrderById(UUID bookingId);
}
