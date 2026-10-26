package pxt.dki.calendar;

/** Google answered 404/410: the event no longer exists on the calendar. */
public class CalendarEventNotFoundException extends RuntimeException {

    public CalendarEventNotFoundException(String message) {
        super(message);
    }
}
