package pxt.dki.booking;

import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import pxt.dki.settings.SettingsService;
import pxt.dki.user.User;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The admin mail toggle silences every notification even when the env switch is on. */
class NotificationToggleTest {

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final SettingsService settings = mock(SettingsService.class);

    private NotificationService service() {
        return new NotificationService(mailSender, true, "rooms@dki.local", settings);
    }

    private NotificationService.BookingSnapshot snapshot() {
        return new NotificationService.BookingSnapshot("Standup", "Mars", "Alice",
                Instant.parse("2026-11-03T02:00:00Z"), Instant.parse("2026-11-03T03:00:00Z"));
    }

    private User recipient() {
        User u = new User();
        u.setEmail("bob@dki.local");
        return u;
    }

    @Test
    void mailToggleOff_sendsNothing() {
        when(settings.mailEnabled()).thenReturn(false);
        service().bookingConfirmed(snapshot(), recipient());
        verify(mailSender, never()).send(any(org.springframework.mail.SimpleMailMessage.class));
    }

    @Test
    void mailToggleOn_sends() {
        when(settings.mailEnabled()).thenReturn(true);
        service().bookingCancelled(snapshot(), List.of(recipient()));
        verify(mailSender).send(any(org.springframework.mail.SimpleMailMessage.class));
    }
}
