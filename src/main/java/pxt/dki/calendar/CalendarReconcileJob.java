package pxt.dki.calendar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import pxt.dki.integrations.GoogleOAuthProperties;

/**
 * Safety net for bookings that reached the database without passing through BookingService:
 * SQL seeds / imports, manual repairs, or rows written while sync was switched off at the
 * environment level. Those never got an outbox row, so nothing would ever push them. This job
 * periodically runs the same back-fill the admin "sync-all" button uses; it is cheap (two set
 * queries) and idempotent, so running it every minute costs nothing when there is no backlog.
 * Updates and cancellations made directly in SQL are out of scope: they need a changed-since
 * cursor, and such edits are not expected outside the API.
 */
@Component
public class CalendarReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(CalendarReconcileJob.class);

    private final CalendarSyncService syncService;
    private final CalendarProperties properties;
    private final GoogleOAuthProperties oauthProperties;

    public CalendarReconcileJob(CalendarSyncService syncService, CalendarProperties properties,
                                GoogleOAuthProperties oauthProperties) {
        this.syncService = syncService;
        this.properties = properties;
        this.oauthProperties = oauthProperties;
    }

    @Scheduled(fixedDelayString = "${app.calendar.reconcile-interval-ms}",
            initialDelayString = "${app.scheduling.initial-delay-ms:0}")
    public void reconcile() {
        if (!properties.enabled() && !oauthProperties.enabled()) {
            return;
        }
        try {
            int queued = syncService.enqueueAllExisting();
            if (queued > 0) {
                log.info("Calendar reconcile: queued {} booking(s) that had no outbox row", queued);
            }
        } catch (RuntimeException e) {
            log.warn("Calendar reconcile failed: {}", e.toString());
        }
    }
}
