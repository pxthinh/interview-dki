package pxt.dki.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import pxt.dki.user.User;

import java.util.Collection;

/**
 * The asynchronous half of {@link NotificationService}: a separate bean so the {@link Async}
 * proxy is always in play (a self-invocation inside NotificationService would bypass it).
 */
@Component
public class NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(NotificationSender.class);

    private final JavaMailSender mailSender;
    private final boolean enabled;
    private final String from;
    private final pxt.dki.settings.SettingsService settings;

    public NotificationSender(JavaMailSender mailSender,
                              @Value("${app.notifications.enabled}") boolean enabled,
                              @Value("${app.notifications.from}") String from,
                              pxt.dki.settings.SettingsService settings) {
        this.mailSender = mailSender;
        this.enabled = enabled;
        this.from = from;
        this.settings = settings;
    }

    @Async
    public void send(Collection<User> recipients, String subject, String body) {
        // env switch provisions mail; the admin toggle pauses it at runtime
        if (!enabled || !settings.mailEnabled() || recipients.isEmpty()) {
            return;
        }
        for (User user : recipients) {
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(from);
                message.setTo(user.getEmail());
                message.setSubject(subject);
                message.setText(body);
                mailSender.send(message);
            } catch (Exception e) {
                log.warn("Could not send '{}' to {}: {}", subject, user.getEmail(), e.toString());
            }
        }
    }
}
