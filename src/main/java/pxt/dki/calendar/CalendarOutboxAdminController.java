package pxt.dki.calendar;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pxt.dki.calendar.dto.OutboxResponse;
import pxt.dki.common.error.ResourceNotFoundException;

import java.time.Clock;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/calendar-outbox")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Calendar outbox (admin)", description = "Giám sát và retry đồng bộ Google Calendar")
public class CalendarOutboxAdminController {

    private final CalendarOutboxRepository outboxRepository;
    private final CalendarSyncService calendarSyncService;
    private final Clock clock;

    public CalendarOutboxAdminController(CalendarOutboxRepository outboxRepository,
                                         CalendarSyncService calendarSyncService,
                                         Clock clock) {
        this.outboxRepository = outboxRepository;
        this.calendarSyncService = calendarSyncService;
        this.clock = clock;
    }

    @GetMapping
    @Operation(summary = "Liệt kê outbox theo trạng thái",
            description = "Mặc định liệt kê các bản ghi FAILED (đã hết lượt retry) để admin xử lý.")
    public List<OutboxResponse> list(
            @Parameter(description = "PENDING, DONE hoặc FAILED; mặc định FAILED")
            @RequestParam(required = false) OutboxStatus status) {
        OutboxStatus effective = status != null ? status : OutboxStatus.FAILED;
        return outboxRepository.findByStatusOrderByIdDesc(effective)
                .stream().map(OutboxResponse::from).toList();
    }

    @PostMapping("/{id}/retry")
    @Operation(summary = "Retry một bản ghi",
            description = "Đưa bản ghi về PENDING với attempts=0; processor sẽ xử lý ở vòng poll kế tiếp.")
    public OutboxResponse retry(@PathVariable Long id) {
        CalendarOutbox item = outboxRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Outbox item not found"));
        item.setStatus(OutboxStatus.PENDING);
        item.setAttempts(0);
        item.setNextAttemptAt(clock.instant());
        item.setProcessedAt(null);
        return OutboxResponse.from(outboxRepository.save(item));
    }

    @PostMapping("/sync-all")
    @Operation(summary = "Đồng bộ toàn bộ booking hiện có",
            description = "Quét các booking ACTIVE sắp tới chưa được đồng bộ và đưa vào outbox để đẩy lên Google Calendar.")
    public java.util.Map<String, Object> syncAll() {
        int count = calendarSyncService.enqueueAllExisting();
        return java.util.Map.of("enqueuedCount", count);
    }
}
