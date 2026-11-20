package com.example.chat.dto;

import java.time.Instant;

/** Pushed to the original sender on /user/queue/read when their message is read. */
public record ReadReceiptDto(Long messageId, String reader, Instant readAt) {
}
