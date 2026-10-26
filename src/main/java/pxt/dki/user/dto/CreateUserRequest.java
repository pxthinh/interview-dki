package pxt.dki.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import pxt.dki.user.Role;

public record CreateUserRequest(
        @Email @NotBlank String email,
        @NotBlank @Size(max = 255) String fullName,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotNull Role role) {
}
