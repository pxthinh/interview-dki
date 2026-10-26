package pxt.dki.user.dto;

import jakarta.validation.constraints.Size;
import pxt.dki.user.Role;

/** Partial update: only non-null fields are applied. */
public record UpdateUserRequest(
        @Size(max = 255) String fullName,
        Role role,
        Boolean active) {
}
