package pxt.dki.common.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Business time zone helpers. All booking rules (working hours, 15-minute grid, day boundaries)
 * are evaluated in Vietnam time; storage stays in UTC instants.
 */
public final class BusinessTime {

    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private BusinessTime() {
    }

    /** Render a stored instant for the API, always at the +07:00 business offset. */
    public static OffsetDateTime toApi(Instant instant) {
        return instant == null ? null : instant.atZone(ZONE).toOffsetDateTime();
    }

    /** View an instant on the business-zone wall clock. */
    public static ZonedDateTime toBusiness(Instant instant) {
        return instant.atZone(ZONE);
    }

    /** Today's date on the business-zone wall clock. */
    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(ZONE));
    }
}
