package com.example.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Registration payload. Username is restricted to a safe character set. */
public record RegisterRequest(
        @NotBlank
        @Size(min = 3, max = 30, message = "Username must be 3-30 characters")
        @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "Username may only contain letters, digits and underscores")
        String username,

        @NotBlank
        @Size(min = 8, max = 72, message = "Password must be 8-72 characters")
        String password
) {
}
