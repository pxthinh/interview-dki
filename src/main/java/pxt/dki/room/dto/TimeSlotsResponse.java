package pxt.dki.room.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Pickable times for the booking form. {@code startTimes} lists valid start times for the day;
 * {@code endTimes} is filled once a start is supplied and lists valid end times for it.
 * Times where every matching room is taken (15-minute break included) are omitted.
 */
public record TimeSlotsResponse(List<TimeOption> startTimes, List<TimeOption> endTimes) {

    public record TimeOption(OffsetDateTime time, int availableRooms) {
    }
}
