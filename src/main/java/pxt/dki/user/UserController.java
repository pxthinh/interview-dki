package pxt.dki.user;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pxt.dki.auth.AuthUser;
import pxt.dki.user.dto.ChangePasswordRequest;
import pxt.dki.user.dto.CreateUserRequest;
import pxt.dki.user.dto.UpdateUserRequest;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "Quản trị người dùng (ADMIN) và đổi mật khẩu")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Tạo người dùng (ADMIN)",
            description = "Không có self-signup; admin cấp tài khoản với mật khẩu tạm. Email trùng trả 422 EMAIL_TAKEN.")
    public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        return userService.create(request);
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Liệt kê người dùng (ADMIN)", description = "Phân trang chuẩn Spring: page, size, sort.")
    public Page<UserResponse> list(@PageableDefault(size = 20) Pageable pageable) {
        return userService.list(pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Chi tiết người dùng (ADMIN)")
    public UserResponse get(@PathVariable UUID id) {
        return userService.get(id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Sửa người dùng (ADMIN)",
            description = "Partial update: chỉ áp các trường gửi lên (fullName, role, active). active=false khóa đăng nhập ngay.")
    public UserResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return userService.update(id, request);
    }

    @PostMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Đổi mật khẩu của tôi",
            description = "Mọi người dùng đã đăng nhập. Sai mật khẩu hiện tại trả 422 WRONG_PASSWORD.")
    public void changePassword(@AuthenticationPrincipal AuthUser principal,
                               @Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(principal, request);
    }
}
