package pxt.dki.auth;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import pxt.dki.auth.dto.LoginRequest;
import pxt.dki.auth.dto.LoginResponse;
import pxt.dki.common.error.ResourceNotFoundException;
import pxt.dki.user.User;
import pxt.dki.user.UserRepository;
import pxt.dki.user.UserResponse;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    /** Matched against when the email is unknown, so both failures cost one BCrypt round. */
    private final String dummyHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.dummyHash = passwordEncoder.encode("timing-oracle-dummy-password");
    }

    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmailIgnoreCase(request.email())
                .filter(User::isActive)
                .orElse(null);
        if (user == null) {
            // unknown or inactive account: burn the same hashing time as a wrong password
            passwordEncoder.matches(request.password(), dummyHash);
            throw new BadCredentialsException("Invalid credentials");
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid credentials");
        }
        return new LoginResponse(jwtService.generate(user), jwtService.expiresInSeconds(), UserResponse.from(user));
    }

    public UserResponse me(AuthUser principal) {
        return userRepository.findById(principal.id())
                .map(UserResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
