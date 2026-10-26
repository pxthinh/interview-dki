package pxt.dki.room;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pxt.dki.room.dto.AvailabilityQuery;
import pxt.dki.room.dto.RoomRequest;
import pxt.dki.room.dto.RoomResponse;
import pxt.dki.room.dto.RoomStatusRequest;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/rooms")
@Tag(name = "Rooms", description = "Quản lý phòng họp và tìm phòng trống")
public class RoomController {

    private final RoomService roomService;

    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    @GetMapping
    @Operation(summary = "Liệt kê phòng",
            description = "Trả về danh sách phòng. active=true chỉ trả phòng đang hoạt động, sắp theo sức chứa tăng dần.")
    public List<RoomResponse> list(
            @Parameter(description = "true = chỉ phòng đang hoạt động; bỏ trống = tất cả")
            @RequestParam(required = false) Boolean active) {
        return roomService.list(active);
    }

    @GetMapping("/availability")
    @Operation(summary = "Tìm phòng trống",
            description = "Trả về các phòng active còn trống trong khoảng [start, end), lọc theo sức chứa tối thiểu "
                    + "và tiện nghi (phòng phải có đủ mọi tiện nghi yêu cầu). Kết quả sắp theo sức chứa tăng dần "
                    + "để phòng vừa đủ đứng trước.")
    public List<RoomResponse> availability(
            @Parameter(description = "Giờ bắt đầu, ISO-8601 kèm offset, vd 2026-10-28T09:00:00+07:00", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime start,
            @Parameter(description = "Giờ kết thúc, ISO-8601 kèm offset", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime end,
            @Parameter(description = "Sức chứa tối thiểu")
            @RequestParam(required = false) Integer minCapacity,
            @Parameter(description = "Tiện nghi bắt buộc, vd amenities=projector&amenities=whiteboard")
            @RequestParam(required = false) List<String> amenities) {
        return roomService.findAvailable(new AvailabilityQuery(start, end, minCapacity, amenities));
    }

    @GetMapping("/time-slots")
    @Operation(summary = "Giờ bắt đầu / kết thúc có thể chọn",
            description = "Không truyền start: trả danh sách giờ bắt đầu hợp lệ trên lưới 15 phút của ngày đó "
                    + "(07:00-19:45), kèm số phòng trống. Truyền start: trả thêm các giờ kết thúc hợp lệ cho giờ "
                    + "bắt đầu đó (tối đa 8 giờ, không quá 20:00). Đã tính giờ nghỉ 15 phút giữa hai booking cùng "
                    + "phòng; giờ mà mọi phòng (khớp bộ lọc) đều kín không được trả về, nên UI chỉ cho chọn những "
                    + "giờ chắc chắn đặt được.")
    public pxt.dki.room.dto.TimeSlotsResponse timeSlots(
            @Parameter(description = "Ngày cần xem, vd 2026-10-28; mặc định hôm nay; tối đa 90 ngày tới")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) java.time.LocalDate date,
            @Parameter(description = "Giờ bắt đầu đã chọn (ISO-8601 kèm offset) để lấy giờ kết thúc hợp lệ")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime start,
            @Parameter(description = "Sức chứa tối thiểu")
            @RequestParam(required = false) Integer minCapacity,
            @Parameter(description = "Tiện nghi bắt buộc")
            @RequestParam(required = false) List<String> amenities,
            @Parameter(description = "Chỉ xét một phòng (dùng khi sửa booking)")
            @RequestParam(required = false) UUID roomId,
            @Parameter(description = "Bỏ qua booking này khi tính bận/trống (booking đang được sửa)")
            @RequestParam(required = false) UUID excludeBookingId) {
        return roomService.findTimeSlots(date, start, minCapacity, amenities, roomId, excludeBookingId);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Chi tiết một phòng")
    public RoomResponse get(@PathVariable UUID id) {
        return roomService.get(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Tạo phòng (ADMIN)",
            description = "Tên phòng là duy nhất. googleCalendarId là ID lịch Google của phòng, để trống nếu chưa dùng.")
    public RoomResponse create(
            @org.springframework.security.core.annotation.AuthenticationPrincipal pxt.dki.auth.AuthUser principal,
            @Valid @RequestBody RoomRequest request) {
        return roomService.create(principal, request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Sửa phòng (ADMIN)", description = "Thay toàn bộ thông tin phòng.")
    public RoomResponse update(@PathVariable UUID id, @Valid @RequestBody RoomRequest request) {
        return roomService.update(id, request);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Bật/tắt phòng (ADMIN)",
            description = "active=false: phòng ngừng nhận booking mới, booking cũ giữ nguyên. Không có xóa cứng.")
    public RoomResponse setStatus(@PathVariable UUID id, @Valid @RequestBody RoomStatusRequest request) {
        return roomService.setActive(id, request.active());
    }
}
