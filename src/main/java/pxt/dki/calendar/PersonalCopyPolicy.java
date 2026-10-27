package pxt.dki.calendar;

import org.springframework.stereotype.Component;
import pxt.dki.booking.Booking;
import pxt.dki.booking.BookingSource;
import pxt.dki.booking.BookingStatus;

import java.util.UUID;

/**
 * The single rule personal copies converge to: the meeting is ACTIVE and the user takes part -
 * except where the organizer already has the meeting on their own calendar:
 * <ul>
 *   <li>it was created in Google (imported from the room calendar): the creator has the
 *       original, or the organizer is only the room-admin fallback attribution;</li>
 *   <li>the room event invites the booker (GOOGLE_CALENDAR_INVITE_BOOKER) and that room
 *       actually syncs: a copy would show the meeting twice.</li>
 * </ul>
 */
@Component
public class PersonalCopyPolicy {

    private final CalendarProperties calendar;

    public PersonalCopyPolicy(CalendarProperties calendar) {
        this.calendar = calendar;
    }

    public boolean wants(Booking booking, UUID userId) {
        if (booking.getStatus() != BookingStatus.ACTIVE) {
            return false;
        }
        if (booking.getParticipants().stream().anyMatch(u -> u.getId().equals(userId))) {
            return true;
        }
        if (!booking.getUser().getId().equals(userId)) {
            return false;
        }
        if (booking.getSource() == BookingSource.GOOGLE) {
            return false;
        }
        boolean roomEventInvitesBooker = calendar.enabled() && calendar.inviteBooker()
                && booking.getRoom().getGoogleCalendarId() != null;
        return !roomEventInvitesBooker;
    }
}
