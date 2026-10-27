package pxt.dki.calendar;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import pxt.dki.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature flags bind as booleans, so "yes"/"1"/"0"/"no" are valid values. The app must boot
 * with them and pick the right client (an exact-string @ConditionalOnProperty matched neither
 * "true" nor "false" and left no bean at all).
 */
@TestPropertySource(properties = {"app.calendar.enabled=yes", "app.google-oauth.enabled=0"})
class RelaxedBooleanFlagsTest extends AbstractIntegrationTest {

    @Autowired
    ApplicationContext context;

    @Test
    void relaxedValues_bootAndSelectTheMatchingClients() {
        assertThat(context.getBean(GoogleCalendarClient.class)).isInstanceOf(GoogleCalendarClientImpl.class);
        assertThat(context.getBean(PersonalCalendarClient.class)).isInstanceOf(NoOpPersonalCalendarClient.class);
    }
}
