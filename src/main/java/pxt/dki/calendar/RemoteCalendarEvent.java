package pxt.dki.calendar;

import java.time.Instant;

/**
 * One changed event pulled from a Google Calendar during reverse sync.
 * {@code start}/{@code end} are null for cancelled or all-day events.
 */
public record RemoteCalendarEvent(
        String id,
        String summary,
        String description,
        Instant start,
        Instant end,
        boolean cancelled,
        String creatorEmail) {
}
