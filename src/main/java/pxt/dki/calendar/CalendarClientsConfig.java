package pxt.dki.calendar;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pxt.dki.integrations.GoogleOAuthProperties;

/**
 * Picks the Google clients from the BOUND boolean flags. (An exact-string
 * {@code @ConditionalOnProperty(havingValue = "true"/"false")} pair matched neither for valid
 * boolean spellings like "yes", "1" or "0", leaving no bean and an app that would not boot.)
 */
@Configuration
public class CalendarClientsConfig {

    @Bean
    public GoogleCalendarClient googleCalendarClient(CalendarProperties properties) {
        return properties.enabled() ? new GoogleCalendarClientImpl(properties) : new NoOpGoogleCalendarClient();
    }

    @Bean
    public PersonalCalendarClient personalCalendarClient(GoogleOAuthProperties properties) {
        return properties.enabled() ? new PersonalCalendarClientImpl(properties) : new NoOpPersonalCalendarClient();
    }
}
