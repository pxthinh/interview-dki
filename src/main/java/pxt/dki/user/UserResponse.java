package pxt.dki.user;

import pxt.dki.common.time.BusinessTime;

import java.time.OffsetDateTime;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String fullName,
        Role role,
        boolean active,
        OffsetDateTime createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFullName(),
                user.getRole(), user.isActive(), BusinessTime.toApi(user.getCreatedAt()));
    }
}
