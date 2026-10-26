package pxt.dki.calendar;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BackoffTest {

    @Test
    void backoff_doublesFrom30Seconds() {
        assertThat(CalendarOutboxProcessor.backoff(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(CalendarOutboxProcessor.backoff(2)).isEqualTo(Duration.ofSeconds(60));
        assertThat(CalendarOutboxProcessor.backoff(3)).isEqualTo(Duration.ofSeconds(120));
        assertThat(CalendarOutboxProcessor.backoff(6)).isEqualTo(Duration.ofSeconds(960));
    }
}
