package pxt.dki.settings;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pxt.dki.settings.dto.SettingsResponse;
import pxt.dki.settings.dto.UpdateSettingsRequest;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Settings", description = "Giới hạn đặt phòng do ADMIN điều chỉnh")
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping("/settings")
    @Operation(summary = "Giới hạn hiện hành",
            description = "Số ngày đặt trước tối đa và số tuần tối đa của chuỗi định kỳ; UI dùng để giới hạn form.")
    public SettingsResponse current() {
        return settingsService.current();
    }

    @PutMapping("/admin/settings")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Đổi giới hạn (ADMIN)",
            description = "maxDaysAhead 1-365, maxRecurringWeeks 2-52; trường null giữ nguyên. "
                    + "Booking đã đặt xa hơn mốc mới giữ nguyên, luật mới chỉ áp cho đặt/sửa về sau.")
    public SettingsResponse update(@RequestBody UpdateSettingsRequest request) {
        return settingsService.update(request);
    }
}
