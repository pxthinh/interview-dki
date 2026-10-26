package pxt.dki.booking;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pxt.dki.auth.AuthUser;
import pxt.dki.booking.dto.BookingResponse;
import pxt.dki.booking.dto.BookingSearchFilter;
import pxt.dki.booking.dto.CreateBookingRequest;
import pxt.dki.booking.dto.UpdateBookingRequest;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bookings")
@Tag(name = "Bookings", description = "Đặt, sửa, hủy và tra cứu booking")
public class BookingController {

    private final BookingService bookingService;
    private final CalendarPreSyncPort calendarPreSync;

    public BookingController(BookingService bookingService, CalendarPreSyncPort calendarPreSync) {
        this.bookingService = bookingService;
        this.calendarPreSync = calendarPreSync;
    }

    @PostMapping
    @Operation(summary = "Đặt phòng",
            description = "Quy tắc: lưới 15 phút, dài 15 phút đến 8 giờ, trong 07:00-20:00 giờ Việt Nam cùng một ngày, "
                    + "không quá 90 ngày tới, attendeeCount không vượt sức chứa phòng. "
                    + "Trùng lịch trả 409 kèm thông tin booking đang chiếm chỗ trong trường conflict.")
    public ResponseEntity<BookingResponse> create(@AuthenticationPrincipal AuthUser principal,
                                                  @Valid @RequestBody CreateBookingRequest request) {
        // pull in any event just created on the room's Google Calendar (outside the booking tx)
        calendarPreSync.preSync(request.roomId());
        BookingResponse response = bookingService.create(principal, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/v1/bookings/" + response.id()))
                .body(response);
    }

    @GetMapping("/me")
    @Operation(summary = "Booking của tôi",
            description = "Mặc định trả các booking ACTIVE sắp tới. Đổi from/to/status để xem lịch sử.")
    public List<BookingResponse> myBookings(
            @AuthenticationPrincipal AuthUser principal,
            @Parameter(description = "Từ thời điểm; mặc định bây giờ")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @Parameter(description = "Đến thời điểm; mặc định không giới hạn")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @Parameter(description = "ACTIVE (mặc định) hoặc CANCELLED")
            @RequestParam(required = false) BookingStatus status) {
        Instant fromInstant = from == null ? null : from.toInstant();
        Instant toInstant = to == null ? null : to.toInstant();
        return bookingService.myBookings(principal, fromInstant, toInstant, status);
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Tìm kiếm mọi booking (ADMIN)",
            description = "Lọc theo phòng, người đặt, khoảng thời gian, trạng thái; có phân trang.")
    public Page<BookingResponse> search(
            @RequestParam(required = false) UUID roomId,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) BookingStatus status,
            @PageableDefault(size = 20, sort = "startTime") Pageable pageable) {
        return bookingService.search(new BookingSearchFilter(roomId, userId, from, to, status), pageable);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Chi tiết booking", description = "Chỉ chủ booking hoặc ADMIN xem được; người khác nhận 403.")
    public BookingResponse get(@AuthenticationPrincipal AuthUser principal, @PathVariable UUID id) {
        return bookingService.get(principal, id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Sửa booking",
            description = "Chỉ chủ booking hoặc ADMIN, khi booking còn ACTIVE và chưa bắt đầu. "
                    + "Không đổi được phòng: hủy rồi đặt phòng khác. Mọi quy tắc thời gian áp lại như khi tạo.")
    public BookingResponse update(@AuthenticationPrincipal AuthUser principal, @PathVariable UUID id,
                                  @Valid @RequestBody UpdateBookingRequest request) {
        calendarPreSync.preSync(bookingService.roomIdOf(id));
        return bookingService.update(principal, id, request);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Hủy booking",
            description = "Chỉ chủ booking hoặc ADMIN. Hủy được cả khi cuộc họp đang diễn ra (giải phóng phòng); "
                    + "không hủy được booking đã kết thúc. Bản ghi giữ lại với trạng thái CANCELLED.")
    public BookingResponse cancel(@AuthenticationPrincipal AuthUser principal, @PathVariable UUID id) {
        return bookingService.cancel(principal, id);
    }
}
