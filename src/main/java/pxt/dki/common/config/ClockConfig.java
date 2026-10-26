package pxt.dki.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Single source of "now" for the application. Services take {@link Clock} as a dependency so
 * tests can freeze or move time instead of sleeping.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
