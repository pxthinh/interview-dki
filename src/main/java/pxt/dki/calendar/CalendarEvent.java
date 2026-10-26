package pxt.dki.calendar;

import pxt.dki.booking.Booking;

import java.time.Instant;

/** Provider-agnostic calendar event payload built from a booking. */
public record CalendarEvent(
        String summary,
        String description,
        String location,
        Instant start,
        Instant end,
        String attendeeEmail) {

    public static CalendarEvent from(Booking booking, boolean inviteBooker) {
        String booker = "Người đặt: %s <%s>".formatted(
                booking.getUser().getFullName(), booking.getUser().getEmail());
        String description = booking.getDescription() == null || booking.getDescription().isBlank()
                ? booker
                : booker + "\n\n" + booking.getDescription();
        return new CalendarEvent(
                booking.getTitle(),
                description,
                booking.getRoom().getName(),
                booking.getStartTime(),
                booking.getEndTime(),
                inviteBooker ? booking.getUser().getEmail() : null);
    }
}
