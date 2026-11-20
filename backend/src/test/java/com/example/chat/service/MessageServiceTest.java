package com.example.chat.service;

import com.example.chat.dto.MessageDto;
import com.example.chat.entity.Message;
import com.example.chat.repository.MessageRepository;
import com.example.chat.service.MessageService.Attachment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    @Mock private MessageRepository messageRepository;

    private MessageService messageService;

    @BeforeEach
    void setUp() {
        messageService = new MessageService(messageRepository);
        // save() echoes the entity back, like JPA would
        org.mockito.Mockito.lenient().when(messageRepository.save(any(Message.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void savesPublicMessageWithSanitizedContent() {
        Message saved = messageService.savePublicMessage("alice", "  <script>alert('x')</script>  ", null);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(captor.capture());
        assertThat(captor.getValue().getContent())
                .isEqualTo("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;");
        assertThat(saved.isPrivate()).isFalse();
        assertThat(saved.getReceiver()).isNull();
        assertThat(saved.getSender()).isEqualTo("alice");
        assertThat(saved.getTimestamp()).isNotNull();
    }

    @Test
    void savesPrivateMessage() {
        Message saved = messageService.savePrivateMessage("alice", "bob", "hello bob", null);

        assertThat(saved.isPrivate()).isTrue();
        assertThat(saved.getReceiver()).isEqualTo("bob");
        assertThat(saved.isRead()).isFalse();
        verify(messageRepository).save(any(Message.class));
    }

    @Test
    void rejectsEmptyMessageWithoutAttachment() {
        assertThatThrownBy(() -> messageService.savePublicMessage("alice", "   ", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("content or an attachment");
    }

    @Test
    void rejectsSelfDirectedPrivateMessage() {
        assertThatThrownBy(() -> messageService.savePrivateMessage("alice", "alice", "hi me", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("yourself");
    }

    @Test
    void rejectsOverlongContent() {
        assertThatThrownBy(() -> messageService.savePublicMessage("alice", "x".repeat(4001), null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("too long");
    }

    @Test
    void acceptsValidAttachmentAndRejectsForeignUrls() {
        String url = "/api/files/" + UUID.randomUUID() + ".png";
        Message saved = messageService.savePublicMessage("alice", null, new Attachment("IMAGE", "cat.png", url));
        assertThat(saved.getFileType()).isEqualTo("IMAGE");
        assertThat(saved.getFileUrl()).isEqualTo(url);
        assertThat(saved.getFileName()).isEqualTo("cat.png");

        assertThatThrownBy(() -> messageService.savePublicMessage(
                "alice", "hi", new Attachment("IMAGE", "evil.png", "https://evil.example/x.png")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("attachment URL");

        assertThatThrownBy(() -> messageService.savePublicMessage(
                "alice", "hi", new Attachment("EXE", "evil.exe", url)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("attachment type");
    }

    @Test
    void publicHistoryIsReturnedOldestFirstAndCapped() {
        Message older = new Message("alice", null, "first", false);
        older.setTimestamp(Instant.parse("2026-01-01T10:00:00Z"));
        Message newer = new Message("bob", null, "second", false);
        newer.setTimestamp(Instant.parse("2026-01-01T10:01:00Z"));
        when(messageRepository.findPublicHistory(any(Pageable.class))).thenReturn(List.of(newer, older));

        List<MessageDto> history = messageService.getPublicHistory(500);

        assertThat(history).extracting(MessageDto::content).containsExactly("first", "second");
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(messageRepository).findPublicHistory(pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(MessageService.MAX_HISTORY);
    }

    @Test
    void privateHistoryQueriesBothDirections() {
        Message m = new Message("bob", "alice", "hey", true);
        when(messageRepository.findConversation(eq("alice"), eq("bob"), any(Pageable.class)))
                .thenReturn(List.of(m));

        List<MessageDto> history = messageService.getPrivateHistory("alice", "bob", 50);

        assertThat(history).hasSize(1);
        assertThat(history.get(0).sender()).isEqualTo("bob");
        verify(messageRepository).findConversation(eq("alice"), eq("bob"), any(Pageable.class));
    }

    @Test
    void markAsReadSetsFlagAndTimestampForTheReceiver() {
        Message message = new Message("alice", "bob", "hi", true);
        when(messageRepository.findById(1L)).thenReturn(Optional.of(message));

        Message result = messageService.markAsRead(1L, "bob");

        assertThat(result.isRead()).isTrue();
        assertThat(result.getReadAt()).isNotNull();
        verify(messageRepository).save(message);
    }

    @Test
    void markAsReadIsIdempotent() {
        Message message = new Message("alice", "bob", "hi", true);
        message.setRead(true);
        Instant originalReadAt = Instant.parse("2026-01-01T10:00:00Z");
        message.setReadAt(originalReadAt);
        when(messageRepository.findById(1L)).thenReturn(Optional.of(message));

        Message result = messageService.markAsRead(1L, "bob");

        assertThat(result.getReadAt()).isEqualTo(originalReadAt);
        verify(messageRepository, never()).save(any());
    }

    @Test
    void markAsReadRejectsNonReceiverAndPublicMessages() {
        Message privateMessage = new Message("alice", "bob", "hi", true);
        when(messageRepository.findById(1L)).thenReturn(Optional.of(privateMessage));
        assertThatThrownBy(() -> messageService.markAsRead(1L, "alice"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("receiver");

        Message publicMessage = new Message("alice", null, "hi", false);
        when(messageRepository.findById(2L)).thenReturn(Optional.of(publicMessage));
        assertThatThrownBy(() -> messageService.markAsRead(2L, "bob"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("private messages");

        when(messageRepository.findById(3L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> messageService.markAsRead(3L, "bob"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void searchValidatesQueryAndDelegatesToRepository() {
        assertThatThrownBy(() -> messageService.searchPublic("x"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("at least 2");

        when(messageRepository.searchPublic(anyString())).thenReturn(List.of());
        messageService.searchPublic("  hello world  ");
        verify(messageRepository).searchPublic("hello world");

        when(messageRepository.searchPrivate("alice", "bob", "deploy")).thenReturn(List.of());
        messageService.searchPrivate("alice", "bob", "deploy");
        verify(messageRepository).searchPrivate("alice", "bob", "deploy");
    }
}
