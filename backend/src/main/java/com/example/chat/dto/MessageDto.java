package com.example.chat.dto;

import com.example.chat.entity.Message;

import java.time.Instant;

/**
 * Wire format for every message (REST history + WebSocket events).
 * clientId echoes the sender's optimistic-UI correlation id (nullable for history loads).
 */
public record MessageDto(
        Long id,
        String sender,
        String receiver,
        String content,
        Instant timestamp,
        boolean isPrivate,
        boolean isRead,
        Instant readAt,
        String fileType,
        String fileName,
        String fileUrl,
        String clientId
) {
    public static MessageDto from(Message m) {
        return from(m, null);
    }

    public static MessageDto from(Message m, String clientId) {
        return new MessageDto(
                m.getId(), m.getSender(), m.getReceiver(), m.getContent(), m.getTimestamp(),
                m.isPrivate(), m.isRead(), m.getReadAt(),
                m.getFileType(), m.getFileName(), m.getFileUrl(), clientId);
    }
}
