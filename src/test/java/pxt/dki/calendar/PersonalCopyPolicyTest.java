package pxt.dki.calendar;

import org.junit.jupiter.api.Test;
import pxt.dki.booking.Booking;
import pxt.dki.booking.BookingSource;
import pxt.dki.booking.BookingStatus;
import pxt.dki.room.Room;
import pxt.dki.user.User;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Who gets a personal copy of a meeting. */
class PersonalCopyPolicyTest {

    private final User organizer = user();
    private final User participant = user();

    private static User user() {
        User u = new User();
        u.setId(UUID.randomUUID());
        return u;
    }

    private Booking booking(BookingSource source, String roomCalendarId) {
        Room room = new Room();
        room.setGoogleCalendarId(roomCalendarId);
        Booking b = new Booking();
        b.setRoom(room);
        b.setUser(organizer);
        b.setStatus(BookingStatus.ACTIVE);
        b.setSource(source);
        b.setParticipants(new LinkedHashSet<>(List.of(participant)));
        return b;
    }

    private static PersonalCopyPolicy policy(boolean roomSync, boolean inviteBooker) {
        return new PersonalCopyPolicy(new CalendarProperties(roomSync, "", inviteBooker, "", 1, 1, 1, 1, 1));
    }

    @Test
    void appBooking_organizerAndParticipants_getCopies() {
        Booking b = booking(BookingSource.APP, "cal");
        assertThat(policy(true, false).wants(b, organizer.getId())).isTrue();
        assertThat(policy(true, false).wants(b, participant.getId())).isTrue();
        assertThat(policy(true, false).wants(b, UUID.randomUUID())).isFalse();
    }

    @Test
    void cancelledOrNoShow_nobodyGetsACopy() {
        Booking b = booking(BookingSource.APP, "cal");
        b.setStatus(BookingStatus.NO_SHOW);
        assertThat(policy(true, false).wants(b, organizer.getId())).isFalse();
        assertThat(policy(true, false).wants(b, participant.getId())).isFalse();
    }

    @Test
    void createdOnGoogle_organizerAlreadyHasIt_orIsJustTheRoomAdmin_noCopy() {
        Booking b = booking(BookingSource.GOOGLE, "cal");
        assertThat(policy(true, false).wants(b, organizer.getId())).isFalse();
        assertThat(policy(true, false).wants(b, participant.getId())).isTrue(); // invited later in the app
    }

    @Test
    void inviteBooker_roomEventAlreadyOnOrganizersCalendar_noDuplicateCopy() {
        Booking b = booking(BookingSource.APP, "cal");
        assertThat(policy(true, true).wants(b, organizer.getId())).isFalse();
        assertThat(policy(true, true).wants(b, participant.getId())).isTrue();
        // no room event (room without calendar / room sync off): the organizer needs the copy
        assertThat(policy(true, true).wants(booking(BookingSource.APP, null), organizer.getId())).isTrue();
        assertThat(policy(false, true).wants(b, organizer.getId())).isTrue();
    }
}
