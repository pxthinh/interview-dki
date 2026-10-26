package pxt.dki.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pxt.dki.auth.dto.LoginRequest;
import pxt.dki.auth.dto.LoginResponse;
import pxt.dki.user.UserResponse;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Đăng nhập và thông tin tài khoản hiện tại")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    @SecurityRequirements // public endpoint
    @Operation(summary = "Đăng nhập",
            description = "Trả về JWT (HS256, hết hạn 8 giờ) kèm thông tin người dùng. Sai thông tin trả 401 BAD_CREDENTIALS.")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    @Operation(summary = "Tài khoản hiện tại", description = "Thông tin người dùng gắn với token đang dùng.")
    public UserResponse me(@AuthenticationPrincipal AuthUser principal) {
        return authService.me(principal);
    }
}
