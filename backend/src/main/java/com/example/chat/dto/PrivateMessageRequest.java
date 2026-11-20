package com.example.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** STOMP payload for /app/chat.private. */
public record PrivateMessageRequest(
        @NotBlank String receiver,
        @Size(max = 4000, message = "Message too long (max 4000 chars)")
        String content,
        String clientId,
        String fileType,
        String fileName,
        String fileUrl
) {
}
