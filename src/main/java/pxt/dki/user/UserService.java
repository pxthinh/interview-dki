package pxt.dki.user;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pxt.dki.auth.AuthUser;
import pxt.dki.common.error.BusinessRuleException;
import pxt.dki.common.error.ResourceNotFoundException;
import pxt.dki.user.dto.ChangePasswordRequest;
import pxt.dki.user.dto.CreateUserRequest;
import pxt.dki.user.dto.UpdateUserRequest;

import java.util.UUID;

@Service
@Transactional
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final pxt.dki.integrations.GoogleOAuthService googleOAuth;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       pxt.dki.integrations.GoogleOAuthService googleOAuth) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.googleOAuth = googleOAuth;
    }

    public UserResponse create(CreateUserRequest request) {
        if (userRepository.existsByEmailIgnoreCase(request.email())) {
            throw new BusinessRuleException("EMAIL_TAKEN", "Email is already registered");
        }
        User user = new User();
        user.setEmail(request.email());
        user.setFullName(request.fullName());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(request.role());
        user.setActive(true);
        return UserResponse.from(userRepository.save(user));
    }

    @Transactional(readOnly = true)
    public Page<UserResponse> list(Pageable pageable) {
        return userRepository.findAll(pageable).map(UserResponse::from);
    }

    @Transactional(readOnly = true)
    public UserResponse get(UUID id) {
        return UserResponse.from(require(id));
    }

    public UserResponse update(UUID id, UpdateUserRequest request) {
        User user = require(id);
        if (request.fullName() != null) {
            user.setFullName(request.fullName());
        }
        if (request.role() != null) {
            user.setRole(request.role());
        }
        if (request.active() != null) {
            if (user.isActive() && !request.active()) {
                // a deactivated user can no longer log in to disconnect: unlink for them, so no
                // more meeting details reach their personal Google calendar
                googleOAuth.disconnect(user.getId());
            }
            user.setActive(request.active());
        }
        return UserResponse.from(userRepository.save(user));
    }

    public void changePassword(AuthUser actor, ChangePasswordRequest request) {
        User user = require(actor.id());
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BusinessRuleException("WRONG_PASSWORD", "Current password is incorrect");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
    }

    private User require(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
