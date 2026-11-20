package com.example.chat.dto;

import jakarta.validation.constraints.Size;

/**
 * STOMP payload for /app/chat.send. The sender is NEVER taken from the payload —
 * it is derived from the authenticated Principal. Attachment fields are optional
 * and reference a file previously uploaded via POST /api/files/upload.
 */
public record ChatMessageRequest(
        @Size(max = 4000, message = "Message too long (max 4000 chars)")
        String content,
        String clientId,
        String fileType,
        String fileName,
        String fileUrl
) {
}
