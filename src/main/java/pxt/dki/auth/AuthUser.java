package pxt.dki.auth;

import pxt.dki.user.Role;

import java.util.UUID;

/** Authenticated principal stored in the SecurityContext. */
public record AuthUser(UUID id, String email, Role role) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
