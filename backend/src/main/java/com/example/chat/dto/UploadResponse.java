package com.example.chat.dto;

/** Response of POST /api/files/upload. fileUrl is a capability URL with an unguessable UUID name. */
public record UploadResponse(String fileUrl, String fileName, String fileType, long size) {
}
