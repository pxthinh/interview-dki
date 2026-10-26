package pxt.dki.calendar;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.calendar")
public record CalendarProperties(
        boolean enabled,
        String serviceAccountKeyPath,
        boolean inviteBooker,
        String impersonateUser,
        long pollIntervalMs,
        long importIntervalMs,
        int batchSize,
        int maxAttempts) {
}
