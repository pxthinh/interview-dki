package pxt.dki.integrations;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pxt.dki.auth.AuthUser;
import pxt.dki.integrations.dto.GoogleIntegrationStatusResponse;

@RestController
@RequestMapping("/api/v1/integrations/google")
@Tag(name = "Google cá nhân", description = "Kết nối Google Calendar cá nhân (OAuth2 từng người)")
public class GoogleIntegrationController {

    private final GoogleOAuthService oauthService;

    public GoogleIntegrationController(GoogleOAuthService oauthService) {
        this.oauthService = oauthService;
    }

    public record ConnectResponse(String authUrl) {
    }

    @GetMapping
    @Operation(summary = "Trạng thái kết nối Google cá nhân")
    public GoogleIntegrationStatusResponse status(@AuthenticationPrincipal AuthUser principal) {
        return oauthService.status(principal.id());
    }

    @PostMapping("/connect")
    @Operation(summary = "Lấy URL ủy quyền Google",
            description = "Trả về authUrl để chuyển hướng trình duyệt; scope calendar.events + openid email, "
                    + "access_type=offline, prompt=consent.")
    public ConnectResponse connect(@AuthenticationPrincipal AuthUser principal) {
        return new ConnectResponse(oauthService.buildAuthUrl(principal.id()));
    }

    @GetMapping("/callback")
    @Operation(summary = "OAuth callback (Google gọi, permitAll)",
            description = "Nhận code + state, đổi lấy refresh token (mã hóa khi lưu) rồi chuyển hướng về "
                    + "/account?google=connected|error.")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error) {
        GoogleOAuthService.CallbackResult result = error != null
                ? GoogleOAuthService.CallbackResult.ERROR
                : oauthService.handleCallback(code, state);
        String location = switch (result) {
            case CONNECTED -> "/account?google=connected";
            case SCOPE -> "/account?google=scope";
            case ERROR -> "/account?google=error";
        };
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location).build();
    }

    @DeleteMapping
    @Operation(summary = "Ngắt kết nối",
            description = "Xóa liên kết + thu hồi token (best-effort). Event đã tạo trên calendar cá nhân giữ nguyên.")
    public ResponseEntity<Void> disconnect(@AuthenticationPrincipal AuthUser principal) {
        oauthService.disconnect(principal.id());
        return ResponseEntity.noContent().build();
    }
}
