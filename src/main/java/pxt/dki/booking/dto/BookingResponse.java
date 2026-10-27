package pxt.dki.booking.dto;

import pxt.dki.booking.Booking;
import pxt.dki.booking.BookingStatus;
import pxt.dki.common.time.BusinessTime;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record BookingResponse(
        UUID id,
        UUID roomId,
        String roomName,
        UUID userId,
        String userEmail,
        String title,
        String description,
        Integer attendeeCount,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        BookingStatus status,
        OffsetDateTime cancelledAt,
        OffsetDateTime checkedInAt,
        String googleEventId,
        UUID seriesId,
        int bufferMinutes,
        List<ParticipantResponse> participants,
        OffsetDateTime createdAt) {

    public record ParticipantResponse(UUID id, String email, String fullName) {
    }

    /** Must be called inside a transaction: it touches the lazy room and user associations. */
    public static BookingResponse from(Booking b) {
        return new BookingResponse(
                b.getId(),
                b.getRoom().getId(), b.getRoom().getName(),
                b.getUser().getId(), b.getUser().getEmail(),
                b.getTitle(), b.getDescription(), b.getAttendeeCount(),
                BusinessTime.toApi(b.getStartTime()), BusinessTime.toApi(b.getEndTime()),
                b.getStatus(), BusinessTime.toApi(b.getCancelledAt()),
                BusinessTime.toApi(b.getCheckedInAt()),
                b.getGoogleEventId(), b.getSeriesId(), b.getBufferMinutes(),
                b.getParticipants().stream()
                        .map(u -> new ParticipantResponse(u.getId(), u.getEmail(), u.getFullName()))
                        .toList(),
                BusinessTime.toApi(b.getCreatedAt()));
    }
}
