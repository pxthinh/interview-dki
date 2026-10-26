package pxt.dki.booking.dto;

import pxt.dki.room.dto.RoomResponse;

import java.util.List;

public record RoomScheduleResponse(RoomResponse room, List<BookingResponse> bookings) {
}
