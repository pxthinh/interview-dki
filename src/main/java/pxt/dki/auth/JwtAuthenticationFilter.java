package pxt.dki.auth;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import pxt.dki.user.User;
import pxt.dki.user.UserRepository;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Resolves a Bearer token into an {@link AuthUser} principal. The user is reloaded from the
 * database on every request so deactivated accounts lose access immediately, token or not.
 * Tokens issued before the user's last password change are rejected the same way.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                JwtService.TokenClaims token = jwtService.parse(header.substring(7));
                userRepository.findById(token.subject())
                        .filter(user -> user.isActive())
                        .filter(user -> issuedAfterPasswordChange(token, user))
                        .ifPresent(user -> {
                            AuthUser principal = new AuthUser(user.getId(), user.getEmail(), user.getRole());
                            var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
                            var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
                            SecurityContextHolder.getContext().setAuthentication(authentication);
                        });
            } catch (JwtException | IllegalArgumentException ignored) {
                // invalid token -> stay anonymous, entry point answers 401
            }
        }
        filterChain.doFilter(request, response);
    }

    /** JWT iat has second precision, so compare against the change time truncated to seconds. */
    private static boolean issuedAfterPasswordChange(JwtService.TokenClaims token, User user) {
        Instant changedAt = user.getPasswordChangedAt();
        return changedAt == null || !token.issuedAt().isBefore(changedAt.truncatedTo(ChronoUnit.SECONDS));
    }
}
