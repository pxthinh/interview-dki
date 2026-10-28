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
     * Rows of a disabled side (room sync / personal OAuth) are not claimed: they wait instead
     * of being consumed by a no-op client.
     */
    @Query(value = """
            SELECT * FROM calendar_outbox
            WHERE status = 'PENDING' AND next_attempt_at <= :now
              AND ((target_user_id IS NULL AND :roomOn) OR (target_user_id IS NOT NULL AND :personalOn))
            ORDER BY booking_id, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<CalendarOutbox> lockPendingBatch(@Param("now") Instant now, @Param("limit") int limit,
                                          @Param("roomOn") boolean roomOn,
                                          @Param("personalOn") boolean personalOn);

    /** Room-row probe: is this booking's room CREATE still waiting to be pushed? */
    boolean existsByBookingIdAndTargetUserIdIsNullAndActionAndStatus(UUID bookingId, OutboxAction action,
                                                                    OutboxStatus status);

    /** Same probe, but only for rows queued after {@code since}: an old stuck row must not hold an import forever. */
    boolean existsByBookingIdAndTargetUserIdIsNullAndActionAndStatusAndCreatedAtAfter(
            UUID bookingId, OutboxAction action, OutboxStatus status, Instant since);

    /** Bookings that already have a room row (any of the given states). */
    @Query("select distinct o.bookingId from CalendarOutbox o where o.targetUserId is null and o.status in :statuses")
    java.util.Set<UUID> bookingIdsWithRoomRow(@Param("statuses") java.util.Collection<OutboxStatus> statuses);

    /** Bookings that already have a personal-copy row for this user (any of the given states). */
    @Query("select distinct o.bookingId from CalendarOutbox o where o.targetUserId = :userId and o.status in :statuses")
    java.util.Set<UUID> bookingIdsWithPersonalRow(@Param("userId") UUID userId,
                                                  @Param("statuses") java.util.Collection<OutboxStatus> statuses);

    List<CalendarOutbox> findByStatusOrderByIdDesc(OutboxStatus status);

    List<CalendarOutbox> findByBookingIdOrderById(UUID bookingId);

    boolean existsByBookingIdAndTargetUserIdIsNullAndStatusIn(UUID bookingId, java.util.Collection<OutboxStatus> statuses);

    boolean existsByBookingIdAndTargetUserIdAndStatusIn(UUID bookingId, UUID targetUserId, java.util.Collection<OutboxStatus> statuses);
}
