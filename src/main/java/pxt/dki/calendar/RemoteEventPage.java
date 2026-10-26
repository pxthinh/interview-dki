package pxt.dki.calendar;

import java.util.List;

/** Changes since the previous sync token, plus the cursor for the next run. */
public record RemoteEventPage(List<RemoteCalendarEvent> events, String nextSyncToken) {
}
