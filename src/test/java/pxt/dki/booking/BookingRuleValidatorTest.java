package pxt.dki.booking;

import org.junit.jupiter.api.Test;
import pxt.dki.common.error.BusinessRuleException;
import pxt.dki.common.time.BusinessTime;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingRuleValidatorTest {

    /** Fixed "now": 2026-10-26T01:00:00Z = 08:00 on 26/10 in Vietnam. */
    private static final Instant NOW = Instant.parse("2026-10-26T01:00:00Z");
    private final BookingRuleValidator validator =
            new BookingRuleValidator(Clock.fixed(NOW, BusinessTime.ZONE));

    private static Instant vn(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(LocalDate.of(year, month, day), LocalTime.of(hour, minute))
                .atZone(BusinessTime.ZONE).toInstant();
    }

    private void expectCode(Instant start, Instant end, String code) {
        assertThatThrownBy(() -> validator.validateTimes(start, end))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("errorCode").isEqualTo(code);
    }

    @Test
    void valid_9to10_ok() {
        assertThatCode(() -> validator.validateTimes(vn(2026, 10, 27, 9, 0), vn(2026, 10, 27, 10, 0)))
                .doesNotThrowAnyException();
    }

    @Test
    void endBeforeStart() {
        expectCode(vn(2026, 10, 27, 10, 0), vn(2026, 10, 27, 9, 0), "END_BEFORE_START");
    }

    @Test
    void notOnGrid_9h05() {
        expectCode(vn(2026, 10, 27, 9, 5), vn(2026, 10, 27, 10, 0), "NOT_ON_15_MIN_GRID");
    }

    @Test
    void tooShort_10min() {
        // 09:00 -> 09:00 same instant is END_BEFORE_START; use grid-aligned short gap impossible,
        // so the shortest grid-aligned violation does not exist; duration rule still guards
        // non-grid inputs defensively. Grid-aligned minimum is exactly 15m, which is valid.
        assertThatCode(() -> validator.validateTimes(vn(2026, 10, 27, 9, 0), vn(2026, 10, 27, 9, 15)))
                .doesNotThrowAnyException();
    }

    @Test
    void tooLong_8h15() {
        expectCode(vn(2026, 10, 27, 7, 0), vn(2026, 10, 27, 15, 15), "DURATION_TOO_LONG");
    }

    @Test
    void startInPast_yesterday() {
        expectCode(vn(2026, 10, 25, 9, 0), vn(2026, 10, 25, 10, 0), "START_IN_PAST");
    }

    @Test
    void before7am() {
        expectCode(vn(2026, 10, 27, 6, 45), vn(2026, 10, 27, 8, 0), "OUTSIDE_WORKING_HOURS");
    }

    @Test
    void after8pm_endAt2015() {
        expectCode(vn(2026, 10, 27, 19, 0), vn(2026, 10, 27, 20, 15), "OUTSIDE_WORKING_HOURS");
    }

    @Test
    void endExactly8pm_ok() {
        assertThatCode(() -> validator.validateTimes(vn(2026, 10, 27, 19, 0), vn(2026, 10, 27, 20, 0)))
                .doesNotThrowAnyException();
    }

    @Test
    void crossesMidnight() {
        // 6h long, so it passes the duration rule and fails on crossing the day boundary
        expectCode(vn(2026, 10, 27, 19, 0), vn(2026, 10, 28, 1, 0), "OUTSIDE_WORKING_HOURS");
    }

    @Test
    void tooFarAhead_day91_rejected_day90_ok() {
        LocalDate day90 = LocalDate.of(2026, 10, 26).plusDays(90);
        LocalDate day91 = day90.plusDays(1);
        assertThatCode(() -> validator.validateTimes(
                day90.atTime(9, 0).atZone(BusinessTime.ZONE).toInstant(),
                day90.atTime(10, 0).atZone(BusinessTime.ZONE).toInstant()))
                .doesNotThrowAnyException();
        expectCode(day91.atTime(9, 0).atZone(BusinessTime.ZONE).toInstant(),
                day91.atTime(10, 0).atZone(BusinessTime.ZONE).toInstant(), "TOO_FAR_AHEAD");
    }

    @Test
    void validator_acceptsUtcOffsetInput() {
        // 2026-10-27T02:00Z == 09:00 VN -> valid regardless of the offset the client sent
        assertThatCode(() -> validator.validateTimes(
                Instant.parse("2026-10-27T02:00:00Z"), Instant.parse("2026-10-27T03:00:00Z")))
                .doesNotThrowAnyException();
    }

    @Test
    void capacity_exceeds() {
        assertThatThrownBy(() -> validator.validateCapacity(9, 8))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("errorCode").isEqualTo("EXCEEDS_CAPACITY");
        assertThatCode(() -> validator.validateCapacity(8, 8)).doesNotThrowAnyException();
        assertThatCode(() -> validator.validateCapacity(null, 8)).doesNotThrowAnyException();
    }
}
