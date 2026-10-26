package pxt.dki.booking;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pxt.dki.booking.dto.BookingResponse;
import pxt.dki.booking.dto.RoomScheduleResponse;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Schedule", description = "Xem lịch phòng họp")
public class ScheduleController {

    private final BookingService bookingService;

    public ScheduleController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @GetMapping("/rooms/{id}/schedule")
    @Operation(summary = "Lịch một phòng",
            description = "Các booking ACTIVE của phòng trong khoảng ngày [from, to] (giờ Việt Nam). "
                    + "Mặc định cả hai là hôm nay. Khoảng tối đa 31 ngày.")
    public List<BookingResponse> roomSchedule(
            @PathVariable UUID id,
            @Parameter(description = "Từ ngày, vd 2026-10-28; mặc định hôm nay")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Đến ngày (bao gồm); mặc định bằng from")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return bookingService.roomSchedule(id, from, to);
    }

    @GetMapping("/schedule")
    @Operation(summary = "Lịch mọi phòng trong một ngày",
            description = "Mỗi phòng active kèm danh sách booking ACTIVE của ngày đó (giờ Việt Nam), "
                    + "kể cả phòng không có booking. Mặc định là hôm nay.")
    public List<RoomScheduleResponse> daySchedule(
            @Parameter(description = "Ngày cần xem, vd 2026-10-28; mặc định hôm nay")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return bookingService.daySchedule(date);
    }
}
