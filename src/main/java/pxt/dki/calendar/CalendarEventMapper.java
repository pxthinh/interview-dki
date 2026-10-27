package pxt.dki.calendar;

import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import com.google.api.services.calendar.model.EventDateTime;
import pxt.dki.common.time.BusinessTime;

import java.time.Instant;
import java.util.List;

/** Shared CalendarEvent -> Google Event mapping for the room (SA) and personal (OAuth) clients. */
final class CalendarEventMapper {

    private CalendarEventMapper() {
    }

    static Event toGoogle(CalendarEvent event) {
        Event google = new Event()
                .setSummary(event.summary())
                .setDescription(event.description())
                .setLocation(event.location())
                .setStart(eventDateTime(event.start()))
                .setEnd(eventDateTime(event.end()));
        if (event.attendeeEmail() != null) {
            google.setAttendees(List.of(new EventAttendee().setEmail(event.attendeeEmail())));
        }
        return google;
    }

    static EventDateTime eventDateTime(Instant instant) {
        return new EventDateTime()
                .setDateTime(new DateTime(instant.toEpochMilli()))
                .setTimeZone(BusinessTime.ZONE.getId());
    }
}
