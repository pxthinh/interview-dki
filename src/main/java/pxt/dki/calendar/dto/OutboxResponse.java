package pxt.dki.calendar.dto;

import pxt.dki.calendar.CalendarOutbox;
import pxt.dki.calendar.OutboxAction;
import pxt.dki.calendar.OutboxStatus;
import pxt.dki.common.time.BusinessTime;

import java.time.OffsetDateTime;
import java.util.UUID;

public record OutboxResponse(
        Long id,
        UUID bookingId,
        OutboxAction action,
        OutboxStatus status,
        int attempts,
        OffsetDateTime nextAttemptAt,
        String lastError,
        OffsetDateTime createdAt,
        OffsetDateTime processedAt,
        UUID targetUserId) {

    public static OutboxResponse from(CalendarOutbox o) {
        return new OutboxResponse(o.getId(), o.getBookingId(), o.getAction(), o.getStatus(),
                o.getAttempts(), BusinessTime.toApi(o.getNextAttemptAt()), o.getLastError(),
                BusinessTime.toApi(o.getCreatedAt()), BusinessTime.toApi(o.getProcessedAt()),
                o.getTargetUserId());
    }
}
