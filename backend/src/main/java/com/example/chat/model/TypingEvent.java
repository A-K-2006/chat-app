package com.example.chat.model;

/**
 * Ephemeral typing indicator (never persisted).
 * receiver == null  -> public room event, broadcast on /topic/typing
 * receiver != null  -> private event, delivered to /user/{receiver}/queue/typing
 */
public record TypingEvent(String sender, String receiver, boolean typing) {
}
