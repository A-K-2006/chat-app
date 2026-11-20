package com.example.chat.service;

import com.example.chat.dto.MessageDto;
import com.example.chat.entity.Message;
import com.example.chat.repository.MessageRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class MessageService {

    public static final int MAX_HISTORY = 100;
    private static final int MAX_CONTENT_LENGTH = 4000;

    private final MessageRepository messageRepository;

    public MessageService(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    /** Attachment metadata attached to a chat message (already uploaded via REST). */
    public record Attachment(String fileType, String fileName, String fileUrl) {
        public boolean present() {
            return fileUrl != null && !fileUrl.isBlank();
        }
    }

    @Transactional
    public Message savePublicMessage(String sender, String content, Attachment attachment) {
        Message message = new Message(sender, null, sanitize(content), false);
        applyAttachment(message, attachment);
        requirePayload(message);
        return messageRepository.save(message);
    }

    @Transactional
    public Message savePrivateMessage(String sender, String receiver, String content, Attachment attachment) {
        if (sender.equals(receiver)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot send a private message to yourself");
        }
        Message message = new Message(sender, receiver, sanitize(content), true);
        applyAttachment(message, attachment);
        requirePayload(message);
        return messageRepository.save(message);
    }

    @Transactional(readOnly = true)
    public List<MessageDto> getPublicHistory(int limit) {
        int capped = Math.min(Math.max(limit, 1), MAX_HISTORY);
        List<Message> newestFirst = messageRepository.findPublicHistory(PageRequest.of(0, capped));
        return toChronologicalDtos(newestFirst);
    }

    @Transactional(readOnly = true)
    public List<MessageDto> getPrivateHistory(String me, String other, int limit) {
        int capped = Math.min(Math.max(limit, 1), MAX_HISTORY);
        List<Message> newestFirst = messageRepository.findConversation(me, other, PageRequest.of(0, capped));
        return toChronologicalDtos(newestFirst);
    }

    @Transactional(readOnly = true)
    public List<MessageDto> searchPublic(String query) {
        String q = requireQuery(query);
        return messageRepository.searchPublic(q).stream().map(MessageDto::from).toList();
    }

    @Transactional(readOnly = true)
    public List<MessageDto> searchPrivate(String me, String other, String query) {
        String q = requireQuery(query);
        return messageRepository.searchPrivate(me, other, q).stream().map(MessageDto::from).toList();
    }

    /**
     * Marks a private message as read. Only the intended receiver may do so;
     * repeated calls are idempotent and preserve the original readAt.
     */
    @Transactional
    public Message markAsRead(Long messageId, String reader) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found"));
        if (!message.isPrivate()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Read receipts only apply to private messages");
        }
        if (!reader.equals(message.getReceiver())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the receiver can mark a message as read");
        }
        if (!message.isRead()) {
            message.setRead(true);
            message.setReadAt(Instant.now());
            message = messageRepository.save(message);
        }
        return message;
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /** HTML-escape content so stored messages can never carry active markup (XSS defense in depth). */
    private String sanitize(String content) {
        if (content == null) {
            return null;
        }
        String trimmed = content.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_CONTENT_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message too long (max 4000 chars)");
        }
        return HtmlUtils.htmlEscape(trimmed);
    }

    private void applyAttachment(Message message, Attachment attachment) {
        if (attachment != null && attachment.present()) {
            String type = attachment.fileType();
            if (!"IMAGE".equals(type) && !"FILE".equals(type)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid attachment type");
            }
            // Only accept URLs minted by our own FileController.
            if (!attachment.fileUrl().matches("^/api/files/[0-9a-f\\-]{36}\\.[a-z0-9]+$")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid attachment URL");
            }
            message.setFileType(type);
            message.setFileName(attachment.fileName());
            message.setFileUrl(attachment.fileUrl());
        }
    }

    private void requirePayload(Message message) {
        if (message.getContent() == null && message.getFileUrl() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message must have content or an attachment");
        }
    }

    private String requireQuery(String query) {
        if (query == null || query.strip().length() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Search query must be at least 2 characters");
        }
        return query.strip();
    }

    /** Repository returns newest-first (for LIMIT); UI wants oldest-first. */
    private List<MessageDto> toChronologicalDtos(List<Message> newestFirst) {
        List<MessageDto> dtos = new ArrayList<>(newestFirst.stream().map(MessageDto::from).toList());
        Collections.reverse(dtos);
        return dtos;
    }
}
