package pxt.dki.common.time;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessTimeTest {

    @Test
    void toApi_rendersInstantAtVietnamOffset() {
        OffsetDateTime api = BusinessTime.toApi(Instant.parse("2026-11-03T02:00:00Z"));

        assertThat(api.getOffset()).isEqualTo(ZoneOffset.ofHours(7));
        assertThat(api.getHour()).isEqualTo(9);
        assertThat(api.toInstant()).isEqualTo(Instant.parse("2026-11-03T02:00:00Z"));
    }

    @Test
    void toApi_nullSafe() {
        assertThat(BusinessTime.toApi(null)).isNull();
    }
}
