package com.example.chat.dto;

import jakarta.validation.constraints.NotNull;

/** STOMP payload for /app/chat.read. */
public record ReadRequest(@NotNull Long messageId) {
}
