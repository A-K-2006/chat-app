package com.example.chat.controller;

import com.example.chat.dto.ChatMessageRequest;
import com.example.chat.dto.MessageDto;
import com.example.chat.dto.PrivateMessageRequest;
import com.example.chat.dto.ReadReceiptDto;
import com.example.chat.dto.ReadRequest;
import com.example.chat.entity.Message;
import com.example.chat.model.TypingEvent;
import com.example.chat.repository.UserRepository;
import com.example.chat.service.MessageService;
import com.example.chat.service.MessageService.Attachment;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.Map;

/**
 * STOMP message handlers. The sender identity ALWAYS comes from the authenticated
 * Principal attached at CONNECT time — payload sender fields are never trusted.
 */
@Controller
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final MessageService messageService;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public ChatController(MessageService messageService,
                          UserRepository userRepository,
                          SimpMessagingTemplate messagingTemplate) {
        this.messageService = messageService;
        this.userRepository = userRepository;
        this.messagingTemplate = messagingTemplate;
    }

    /** /app/chat.send -> persist -> broadcast on /topic/public */
    @MessageMapping("/chat.send")
    public void sendPublicMessage(@Valid ChatMessageRequest request, Principal principal) {
        Message saved = messageService.savePublicMessage(
                principal.getName(), request.content(), attachment(request));
        messagingTemplate.convertAndSend("/topic/public", MessageDto.from(saved, request.clientId()));
    }

    /** /app/chat.private -> persist -> deliver to receiver AND sender (multi-tab sync + optimistic-UI ack). */
    @MessageMapping("/chat.private")
    public void sendPrivateMessage(@Valid PrivateMessageRequest request, Principal principal) {
        String sender = principal.getName();
        if (!userRepository.existsByUsername(request.receiver())) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,
                    "Unknown receiver: " + request.receiver());
        }
        Message saved = messageService.savePrivateMessage(
                sender, request.receiver(), request.content(), attachment(request));
        MessageDto dto = MessageDto.from(saved, request.clientId());
        messagingTemplate.convertAndSendToUser(request.receiver(), "/queue/messages", dto);
        messagingTemplate.convertAndSendToUser(sender, "/queue/messages", dto);
    }

    /** /app/chat.typing -> ephemeral fan-out, never persisted. */
    @MessageMapping("/chat.typing")
    public void typing(TypingEvent payload, Principal principal) {
        TypingEvent event = new TypingEvent(principal.getName(), payload.receiver(), payload.typing());
        if (payload.receiver() == null || payload.receiver().isBlank()) {
            messagingTemplate.convertAndSend("/topic/typing", event);
        } else {
            messagingTemplate.convertAndSendToUser(payload.receiver(), "/queue/typing", event);
        }
    }

    /** /app/chat.read -> mark persisted message read -> notify the original sender. */
    @MessageMapping("/chat.read")
    public void markRead(@Valid ReadRequest request, Principal principal) {
        Message message = messageService.markAsRead(request.messageId(), principal.getName());
        messagingTemplate.convertAndSendToUser(message.getSender(), "/queue/read",
                new ReadReceiptDto(message.getId(), principal.getName(), message.getReadAt()));
    }

    /** Any handler failure is reported to the offending client only, on /user/queue/errors. */
    @MessageExceptionHandler(Exception.class)
    @SendToUser("/queue/errors")
    public Map<String, String> handleException(Exception e) {
        String reason = (e instanceof ResponseStatusException rse && rse.getReason() != null)
                ? rse.getReason()
                : "Message could not be processed";
        if (!(e instanceof ResponseStatusException)) {
            log.error("STOMP handler failure", e);
        }
        return Map.of("error", reason);
    }

    private Attachment attachment(ChatMessageRequest request) {
        return new Attachment(request.fileType(), request.fileName(), request.fileUrl());
    }

    private Attachment attachment(PrivateMessageRequest request) {
        return new Attachment(request.fileType(), request.fileName(), request.fileUrl());
    }
}
