package pxt.dki.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import pxt.dki.room.Room;
import pxt.dki.room.RoomRepository;
import pxt.dki.user.UserRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * First-start seeding links demo rooms to their Google calendars from config
 * (app.seed.room-calendars = "Room=calendarId,..."), so a fresh `docker compose up` syncs
 * without any admin step. Rooms that already have a calendar are never overwritten.
 */
class SeedRoomCalendarsTest {

    private final UserRepository users = mock(UserRepository.class);
    private final RoomRepository rooms = mock(RoomRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);

    private Room room(String name, String calendarId) {
        Room r = new Room();
        r.setName(name);
        r.setGoogleCalendarId(calendarId);
        when(rooms.findByNameIgnoreCase(name)).thenReturn(Optional.of(r));
        return r;
    }

    @Test
    void firstStart_linksListedRoomsWithoutCalendar_only() {
        when(users.count()).thenReturn(0L);
        Room earth = room("Earth", null);
        Room mars = room("Mars", "already-linked");
        when(rooms.findByNameIgnoreCase("Nowhere")).thenReturn(Optional.empty());

        new SeedDataRunner(users, encoder, rooms, " Earth = cal-earth@group.calendar.google.com , Mars=cal-new,Nowhere=x")
                .run(null);

        assertThat(earth.getGoogleCalendarId()).isEqualTo("cal-earth@group.calendar.google.com");
        assertThat(mars.getGoogleCalendarId()).isEqualTo("already-linked");
        verify(rooms).save(earth);
        verify(rooms, never()).save(mars);
    }

    @Test
    void existingDatabase_isLeftAlone() {
        when(users.count()).thenReturn(4L); // not a first start
        Room earth = room("Earth", null);

        new SeedDataRunner(users, encoder, rooms, "Earth=cal-earth").run(null);

        assertThat(earth.getGoogleCalendarId()).isNull();
        verify(rooms, never()).save(any());
    }

    @Test
    void blankConfig_linksNothing() {
        when(users.count()).thenReturn(0L);

        new SeedDataRunner(users, encoder, rooms, "").run(null);

        verify(rooms, never()).findByNameIgnoreCase(any());
    }
}
