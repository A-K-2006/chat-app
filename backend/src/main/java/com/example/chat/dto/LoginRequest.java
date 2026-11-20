package com.example.chat.dto;

import jakarta.validation.constraints.NotBlank;

/** Login payload. twoFactorCode is only required when the account has 2FA enabled. */
public record LoginRequest(
        @NotBlank String username,
        @NotBlank String password,
        String twoFactorCode
) {
}
