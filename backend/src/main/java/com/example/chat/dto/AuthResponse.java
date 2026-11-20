package com.example.chat.dto;

/** Returned by register / login / refresh. */
public record AuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        String username,
        boolean twoFactorEnabled
) {
    public static AuthResponse bearer(String accessToken, String refreshToken, long expiresInSeconds,
                                      String username, boolean twoFactorEnabled) {
        return new AuthResponse(accessToken, refreshToken, "Bearer", expiresInSeconds, username, twoFactorEnabled);
    }
}
