package pxt.dki.auth.dto;

import pxt.dki.user.UserResponse;

public record LoginResponse(String accessToken, long expiresInSeconds, UserResponse user) {
}
