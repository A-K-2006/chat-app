package com.example.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record TwoFactorVerifyRequest(
        @NotBlank
        @Pattern(regexp = "^\\d{6}$", message = "Code must be 6 digits")
        String code
) {
}
