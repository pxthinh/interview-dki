package pxt.dki.booking;

import org.springframework.stereotype.Component;
import pxt.dki.common.error.BusinessRuleException;
import pxt.dki.common.time.BusinessTime;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;

/**
 * Pure booking time rules, all evaluated on the Vietnam wall clock:
 * 15-minute grid, 15m..8h duration, future start, working hours 07:00-20:00 within one day,
 * at most 90 days ahead.
 */
@Component
public class BookingRuleValidator {

    static final Duration MIN_DURATION = Duration.ofMinutes(15);
    static final Duration MAX_DURATION = Duration.ofHours(8);
    static final LocalTime OPENING = LocalTime.of(7, 0);
    static final LocalTime CLOSING = LocalTime.of(20, 0);
    static final int MAX_DAYS_AHEAD = 90;

    private final Clock clock;

    public BookingRuleValidator(Clock clock) {
        this.clock = clock;
    }

    public void validateTimes(Instant start, Instant end) {
        if (!end.isAfter(start)) {
            throw new BusinessRuleException("END_BEFORE_START", "endTime must be after startTime");
        }

        ZonedDateTime startVn = BusinessTime.toBusiness(start);
        ZonedDateTime endVn = BusinessTime.toBusiness(end);
        if (!onGrid(startVn) || !onGrid(endVn)) {
            throw new BusinessRuleException("NOT_ON_15_MIN_GRID",
                    "startTime and endTime must align to 15-minute slots (minutes 00, 15, 30, 45)");
        }

        Duration duration = Duration.between(start, end);
        if (duration.compareTo(MIN_DURATION) < 0) {
            throw new BusinessRuleException("DURATION_TOO_SHORT", "Minimum booking length is 15 minutes");
        }
        if (duration.compareTo(MAX_DURATION) > 0) {
            throw new BusinessRuleException("DURATION_TOO_LONG", "Maximum booking length is 8 hours");
        }

        if (!start.isAfter(clock.instant())) {
            throw new BusinessRuleException("START_IN_PAST", "startTime must be in the future");
        }

        boolean sameDay = startVn.toLocalDate().equals(endVn.toLocalDate());
        boolean insideHours = !startVn.toLocalTime().isBefore(OPENING)
                && !endVn.toLocalTime().isAfter(CLOSING)
                && !endVn.toLocalTime().equals(LocalTime.MIDNIGHT);
        if (!sameDay || !insideHours) {
            throw new BusinessRuleException("OUTSIDE_WORKING_HOURS",
                    "Bookings must stay within 07:00-20:00 (Asia/Ho_Chi_Minh) on a single day");
        }

        LocalDate today = BusinessTime.today(clock);
        if (startVn.toLocalDate().isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
            throw new BusinessRuleException("TOO_FAR_AHEAD",
                    "Bookings can be made at most " + MAX_DAYS_AHEAD + " days in advance");
        }
    }

    public void validateCapacity(Integer attendeeCount, int capacity) {
        if (attendeeCount != null && attendeeCount > capacity) {
            throw new BusinessRuleException("EXCEEDS_CAPACITY",
                    "attendeeCount exceeds the room capacity of " + capacity);
        }
    }

    private boolean onGrid(ZonedDateTime t) {
        return t.getMinute() % 15 == 0 && t.getSecond() == 0 && t.getNano() == 0;
    }
}
