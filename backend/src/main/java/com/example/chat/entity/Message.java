package com.example.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "messages")
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String sender;

    /** NULL for public (room) messages. */
    @Column(length = 30)
    private String receiver;

    @Column(columnDefinition = "TEXT")
    private String content;

    // Backtick-quoted so Hibernate emits "timestamp" — safe on both PostgreSQL and H2.
    @Column(name = "`timestamp`", nullable = false)
    private Instant timestamp;

    @Column(name = "is_private", nullable = false)
    private boolean isPrivate;

    @Column(name = "is_read", nullable = false)
    private boolean isRead = false;

    @Column(name = "read_at")
    private Instant readAt;

    /** 'IMAGE', 'FILE' or NULL for plain text messages. */
    @Column(name = "file_type", length = 50)
    private String fileType;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "file_url", length = 500)
    private String fileUrl;

    protected Message() {
        // JPA
    }

    public Message(String sender, String receiver, String content, boolean isPrivate) {
        this.sender = sender;
        this.receiver = receiver;
        this.content = content;
        this.isPrivate = isPrivate;
        this.timestamp = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getSender() {
        return sender;
    }

    public String getReceiver() {
        return receiver;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public boolean isPrivate() {
        return isPrivate;
    }

    public void setPrivate(boolean aPrivate) {
        isPrivate = aPrivate;
    }

    public boolean isRead() {
        return isRead;
    }

    public void setRead(boolean read) {
        isRead = read;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public void setReadAt(Instant readAt) {
        this.readAt = readAt;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileUrl() {
        return fileUrl;
    }

    public void setFileUrl(String fileUrl) {
        this.fileUrl = fileUrl;
    }
}
