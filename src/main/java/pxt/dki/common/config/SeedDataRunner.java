package pxt.dki.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import pxt.dki.room.RoomRepository;
import pxt.dki.user.Role;
import pxt.dki.user.User;
import pxt.dki.user.UserRepository;

/**
 * Seeds demo accounts on first start so the reviewer can log in right away.
 * Runs only when the users table is empty; disable entirely with SEED_ENABLED=false.
 * Credentials are documented in the README.
 */
@Component
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true", matchIfMissing = true)
public class SeedDataRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedDataRunner.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RoomRepository roomRepository;

    public SeedDataRunner(UserRepository userRepository, PasswordEncoder passwordEncoder,
                          RoomRepository roomRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.roomRepository = roomRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.count() == 0) {
            User admin = user("admin@dki.local", "Admin", "Admin@123", Role.ADMIN);
            user("alice@dki.local", "Alice Nguyen", "User@123", Role.USER);
            user("bob@dki.local", "Bob Tran", "User@123", Role.USER);
            user("carol@dki.local", "Carol Le", "User@123", Role.USER);
            log.info("Seeded 1 admin and 3 users");
            // V6 backfills rooms.admin_user_id from admin@dki.local, but on a fresh DB Flyway runs
            // before this seeder, so the seeded rooms are still admin-less at this point.
            int rooms = roomRepository.assignAdminWhereMissing(admin.getId());
            log.info("Assigned {} admin-less rooms to {}", rooms, admin.getEmail());
        }
        // Rooms are seeded by Flyway migration V3__seed_rooms.sql.
    }

    private User user(String email, String fullName, String rawPassword, Role role) {
        User u = new User();
        u.setEmail(email);
        u.setFullName(fullName);
        u.setPasswordHash(passwordEncoder.encode(rawPassword));
        u.setRole(role);
        u.setActive(true);
        return userRepository.save(u);
    }
}
