package pxt.dki.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Releases rooms whose booking was never checked in: every sweep, ACTIVE bookings past
 * {@code start + grace} without a check-in become NO_SHOW. Each booking is handled in its own
 * transaction ({@link BookingService#markNoShow}), so one concurrent cancel or check-in only
 * skips that booking, never the batch.
 */
@Component
public class NoShowSweeper {

    private static final Logger log = LoggerFactory.getLogger(NoShowSweeper.class);

    private final BookingRepository bookingRepository;
    private final BookingService bookingService;
    private final CheckinProperties checkin;
    private final Clock clock;

    public NoShowSweeper(BookingRepository bookingRepository, BookingService bookingService,
                         CheckinProperties checkin, Clock clock) {
        this.bookingRepository = bookingRepository;
        this.bookingService = bookingService;
        this.checkin = checkin;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.checkin.sweep-interval-ms}")
    public void sweep() {
        if (!checkin.enabled()) {
            return;
        }
        Instant now = clock.instant();
        Duration grace = Duration.ofMinutes(checkin.graceMinutes());
        List<Booking> candidates = bookingRepository.findNoShowCandidates(now.minus(grace), now);
        for (Booking b : candidates) {
            // meetings shorter than the grace period are never released
            if (!b.getEndTime().isAfter(b.getStartTime().plus(grace))) {
                continue;
            }
            try {
                bookingService.markNoShow(b.getId());
            } catch (Exception e) {
                log.warn("No-show release failed for booking {}: {}", b.getId(), e.toString());
            }
        }
    }
}
