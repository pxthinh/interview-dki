package pxt.dki.booking;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Check-in / no-show tuning. A booking not checked in within {@code graceMinutes} after start
 * is released as NO_SHOW by the sweeper; check-in opens {@code earlyWindowMinutes} before start.
 */
@ConfigurationProperties("app.checkin")
public record CheckinProperties(
        boolean enabled,
        int graceMinutes,
        int earlyWindowMinutes,
        long sweepIntervalMs) {
}
