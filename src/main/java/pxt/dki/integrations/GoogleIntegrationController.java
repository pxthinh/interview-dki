package pxt.dki.integrations;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pxt.dki.auth.AuthUser;
import pxt.dki.integrations.dto.GoogleIntegrationStatusResponse;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/integrations/google")
@Tag(name = "Google cá nhân", description = "Kết nối Google Calendar cá nhân (OAuth2 từng người)")
public class GoogleIntegrationController {

    private static final Logger log = LoggerFactory.getLogger(GoogleIntegrationController.class);
    static final String NONCE_COOKIE = "g_oauth_nonce";
    private static final String COOKIE_PATH = "/api/v1/integrations/google";

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
            description = "Trả về authUrl để chuyển hướng trình duyệt (scope calendar.events + openid email, "
                    + "access_type=offline, prompt=consent) và đặt cookie HttpOnly gắn lượt kết nối với "
                    + "chính trình duyệt này; callback từ trình duyệt khác bị từ chối.")
    public ResponseEntity<ConnectResponse> connect(@AuthenticationPrincipal AuthUser principal,
                                                   HttpServletRequest request) {
        String nonce = oauthService.newNonce();
        String authUrl = oauthService.buildAuthUrl(principal.id(), nonce);
        ResponseCookie cookie = nonceCookie(nonce, Duration.ofMinutes(10), request.isSecure());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new ConnectResponse(authUrl));
    }

    @GetMapping("/callback")
    @Operation(summary = "OAuth callback (Google gọi, permitAll)",
            description = "Nhận code + state, kiểm tra cookie của trình duyệt đã bấm Kết nối, đổi lấy refresh "
                    + "token (mã hóa khi lưu) rồi chuyển hướng về /account?google=connected|scope|error.")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error,
                                         @CookieValue(name = NONCE_COOKIE, required = false) String nonce,
                                         HttpServletRequest request) {
        GoogleOAuthService.CallbackResult result;
        try {
            result = error != null
                    ? GoogleOAuthService.CallbackResult.ERROR
                    : oauthService.handleCallback(code, state, nonce);
        } catch (Exception e) {
            log.warn("OAuth callback failed: {}", e.toString());
            result = GoogleOAuthService.CallbackResult.ERROR;
        }
        String location = switch (result) {
            case CONNECTED -> "/account?google=connected";
            case SCOPE -> "/account?google=scope";
            case ERROR -> "/account?google=error";
        };
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, location)
                // one connect attempt per nonce: the cookie is spent either way
                .header(HttpHeaders.SET_COOKIE, nonceCookie("", Duration.ZERO, request.isSecure()).toString())
                .build();
    }

    @DeleteMapping
    @Operation(summary = "Ngắt kết nối",
            description = "Xóa liên kết + thu hồi token (best-effort). Event đã tạo trên calendar cá nhân giữ nguyên.")
    public ResponseEntity<Void> disconnect(@AuthenticationPrincipal AuthUser principal) {
        oauthService.disconnect(principal.id());
        return ResponseEntity.noContent().build();
    }

    /** SameSite=Lax: still sent on Google's top-level GET redirect back to the callback. */
    private static ResponseCookie nonceCookie(String value, Duration maxAge, boolean secure) {
        return ResponseCookie.from(NONCE_COOKIE, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }
}
