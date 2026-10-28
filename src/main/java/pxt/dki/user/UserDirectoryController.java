package pxt.dki.user;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Minimal company directory for every signed-in user: just enough to pick meeting
 * participants. Full user management stays admin-only in UserController.
 */
@RestController
@RequestMapping("/api/v1/users/directory")
@Tag(name = "Users", description = "Quản trị người dùng (ADMIN) và đổi mật khẩu")
public class UserDirectoryController {

    public record DirectoryEntry(UUID id, String fullName, String email) {
    }

    private final UserRepository userRepository;

    public UserDirectoryController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping
    @Operation(summary = "Danh bạ nhân viên",
            description = "Mọi user đã đăng nhập. Trả id, họ tên, email của các tài khoản đang hoạt động "
                    + "để chọn người tham gia cuộc họp.")
    public List<DirectoryEntry> directory() {
        // active accounts only; the technical owner of imported bookings is never a colleague,
        // even if an admin re-activates that account by mistake
        return userRepository.findByActiveTrueOrderByFullNameAsc().stream()
                .filter(u -> !pxt.dki.calendar.CalendarImportJob.IMPORT_USER_EMAIL.equalsIgnoreCase(u.getEmail()))
                .map(u -> new DirectoryEntry(u.getId(), u.getFullName(), u.getEmail()))
                .toList();
    }
}
