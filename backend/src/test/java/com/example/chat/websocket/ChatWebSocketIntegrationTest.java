package com.example.chat.websocket;

import com.example.chat.dto.MessageDto;
import com.example.chat.dto.ReadReceiptDto;
import com.example.chat.entity.User;
import com.example.chat.model.TypingEvent;
import com.example.chat.repository.MessageRepository;
import com.example.chat.repository.UserRepository;
import com.example.chat.security.JwtUtil;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end STOMP tests against a REAL running server (random port, raw
 * WebSocket transport of the SockJS endpoint): CONNECT auth, public broadcast,
 * private delivery, typing, read receipts, presence and topic-injection defense.
 *
 * STOMP SUBSCRIBE registration is asynchronous on the broker channel, so a SEND
 * from another session can race ahead of a fresh subscription. Triggers are
 * therefore retried until delivery is observed — the same thing real clients do
 * (typing heartbeats, reconnect re-broadcasts). All retried operations are
 * idempotent or append-only, so retries never corrupt assertions.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ChatWebSocketIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private UserRepository userRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private com.example.chat.service.OnlineUserService onlineUserService;

    private final List<WebSocketStompClient> clients = new ArrayList<>();
    private final List<StompSession> sessions = new ArrayList<>();

    @AfterEach
    void tearDown() {
        sessions.forEach(s -> {
            if (s.isConnected()) {
                s.disconnect();
            }
        });
        clients.forEach(WebSocketStompClient::stop);
        sessions.clear();
        clients.clear();
    }

    private String newUser() {
        String username = "w" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        userRepository.save(new User(username, passwordEncoder.encode("password123")));
        return username;
    }

    private StompSession connect(String username) throws Exception {
        return connectWithToken(jwtUtil.generateAccessToken(username));
    }

    private StompSession connectWithToken(String token) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(mapper);
        client.setMessageConverter(converter);
        clients.add(client);

        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + token);
        StompSession session = client.connectAsync(
                        "ws://localhost:" + port + "/ws/websocket",
                        new WebSocketHttpHeaders(), headers, new StompSessionHandlerAdapter() {})
                .get(10, TimeUnit.SECONDS);
        sessions.add(session);
        return session;
    }

    private <T> BlockingQueue<T> subscribe(StompSession session, String destination, Class<T> type) {
        BlockingQueue<T> queue = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return type;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.add((T) payload);
            }
        });
        return queue;
    }

    private BlockingQueue<List<String>> subscribeUsers(StompSession session) {
        BlockingQueue<List<String>> queue = new LinkedBlockingQueue<>();
        Type listType = new ParameterizedTypeReference<List<String>>() {}.getType();
        session.subscribe("/topic/users", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return listType;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.add((List<String>) payload);
            }
        });
        return queue;
    }

    /** Re-runs the trigger until the queue yields a frame (subscription-registration races). */
    private <T> T awaitFrame(BlockingQueue<T> queue, Runnable trigger) throws InterruptedException {
        for (int attempt = 0; attempt < 8; attempt++) {
            trigger.run();
            T frame = queue.poll(700, TimeUnit.MILLISECONDS);
            if (frame != null) {
                return frame;
            }
        }
        throw new AssertionError("No frame delivered after 8 trigger attempts");
    }

    @Test
    void connectWithoutValidTokenIsRejected() {
        assertThatThrownBy(() -> connectWithToken("invalid.token.value"))
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    void publicMessageIsBroadcastToEveryoneAndPersisted() throws Exception {
        String alice = newUser();
        String bob = newUser();
        StompSession aliceSession = connect(alice);
        StompSession bobSession = connect(bob);
        BlockingQueue<MessageDto> aliceInbox = subscribe(aliceSession, "/topic/public", MessageDto.class);
        BlockingQueue<MessageDto> bobInbox = subscribe(bobSession, "/topic/public", MessageDto.class);

        Runnable send = () -> aliceSession.send("/app/chat.send",
                Map.of("content", "hello room", "clientId", "c-" + UUID.randomUUID()));
        MessageDto atBob = awaitFrame(bobInbox, send);
        MessageDto atAlice = aliceInbox.poll(5, TimeUnit.SECONDS);

        assertThat(atBob.sender()).isEqualTo(alice);
        assertThat(atBob.content()).isEqualTo("hello room");
        assertThat(atBob.isPrivate()).isFalse();
        assertThat(atAlice).isNotNull();
        assertThat(atAlice.content()).isEqualTo("hello room");
        assertThat(messageRepository.findById(atBob.id())).isPresent();
    }

    @Test
    void privateMessageReachesOnlyTheTwoParticipants() throws Exception {
        String alice = newUser();
        String bob = newUser();
        String eve = newUser();
        StompSession aliceSession = connect(alice);
        StompSession bobSession = connect(bob);
        StompSession eveSession = connect(eve);
        BlockingQueue<MessageDto> aliceDms = subscribe(aliceSession, "/user/queue/messages", MessageDto.class);
        BlockingQueue<MessageDto> bobDms = subscribe(bobSession, "/user/queue/messages", MessageDto.class);
        BlockingQueue<MessageDto> eveDms = subscribe(eveSession, "/user/queue/messages", MessageDto.class);
        BlockingQueue<MessageDto> evePublic = subscribe(eveSession, "/topic/public", MessageDto.class);

        MessageDto atBob = awaitFrame(bobDms, () -> aliceSession.send("/app/chat.private",
                Map.of("receiver", bob, "content", "secret hi")));
        MessageDto echoAtAlice = aliceDms.poll(5, TimeUnit.SECONDS);

        assertThat(atBob.sender()).isEqualTo(alice);
        assertThat(atBob.receiver()).isEqualTo(bob);
        assertThat(atBob.isPrivate()).isTrue();
        assertThat(echoAtAlice).isNotNull(); // sender echo for optimistic-UI reconciliation
        assertThat(eveDms.poll(500, TimeUnit.MILLISECONDS)).isNull();
        assertThat(evePublic.poll(500, TimeUnit.MILLISECONDS)).isNull();
        assertThat(messageRepository.findById(atBob.id()).orElseThrow().isPrivate()).isTrue();
    }

    @Test
    void typingIndicatorIsDeliveredToTheReceiver() throws Exception {
        String alice = newUser();
        String bob = newUser();
        StompSession aliceSession = connect(alice);
        StompSession bobSession = connect(bob);
        BlockingQueue<TypingEvent> bobTyping = subscribe(bobSession, "/user/queue/typing", TypingEvent.class);

        TypingEvent event = awaitFrame(bobTyping, () ->
                aliceSession.send("/app/chat.typing", Map.of("receiver", bob, "typing", true)));

        assertThat(event.sender()).isEqualTo(alice);
        assertThat(event.receiver()).isEqualTo(bob);
        assertThat(event.typing()).isTrue();
    }

    @Test
    void readReceiptFlowsBackToTheSenderAndPersists() throws Exception {
        String alice = newUser();
        String bob = newUser();
        StompSession aliceSession = connect(alice);
        StompSession bobSession = connect(bob);
        BlockingQueue<MessageDto> bobDms = subscribe(bobSession, "/user/queue/messages", MessageDto.class);
        BlockingQueue<ReadReceiptDto> aliceReceipts = subscribe(aliceSession, "/user/queue/read", ReadReceiptDto.class);

        MessageDto atBob = awaitFrame(bobDms, () -> aliceSession.send("/app/chat.private",
                Map.of("receiver", bob, "content", "ping")));

        ReadReceiptDto receipt = awaitFrame(aliceReceipts, () ->
                bobSession.send("/app/chat.read", Map.of("messageId", atBob.id())));

        assertThat(receipt.messageId()).isEqualTo(atBob.id());
        assertThat(receipt.reader()).isEqualTo(bob);
        assertThat(messageRepository.findById(atBob.id()).orElseThrow().isRead()).isTrue();
    }

    @Test
    void presenceListUpdatesOnConnectAndDisconnect() throws Exception {
        String watcher = newUser();
        StompSession watcherSession = connect(watcher);
        BlockingQueue<List<String>> presence = subscribeUsers(watcherSession);

        // Connecting a newcomer broadcasts the list; retry with fresh users until
        // the watcher's subscription is provably live and the newcomer appears.
        String newcomer = newUser();
        StompSession newcomerSession = connect(newcomer);
        List<String> online = null;
        for (int attempt = 0; attempt < 8 && (online == null || !online.contains(newcomer)); attempt++) {
            online = presence.poll(700, TimeUnit.MILLISECONDS);
            if (online == null || !online.contains(newcomer)) {
                String extra = newUser();
                connect(extra); // each connect triggers another broadcast
            }
        }
        assertThat(online).isNotNull();
        assertThat(online).contains(newcomer, watcher);

        newcomerSession.disconnect();
        // Several broadcasts may still be queued (snapshot + retry-loop connects).
        // Drain until a post-disconnect frame arrives; also verify tracker state.
        List<String> afterDisconnect = null;
        for (int attempt = 0; attempt < 10 && afterDisconnect == null; attempt++) {
            List<String> frame = presence.poll(700, TimeUnit.MILLISECONDS);
            if (frame != null && !frame.contains(newcomer)) {
                afterDisconnect = frame;
            }
        }
        assertThat(afterDisconnect).isNotNull();
        assertThat(afterDisconnect).doesNotContain(newcomer);
        assertThat(onlineUserService.isOnline(newcomer)).isFalse();
    }

    @Test
    void clientCannotPublishDirectlyToTopics() throws Exception {
        String alice = newUser();
        String bob = newUser();
        StompSession aliceSession = connect(alice);
        StompSession bobSession = connect(bob);
        BlockingQueue<MessageDto> bobInbox = subscribe(bobSession, "/topic/public", MessageDto.class);

        // Malicious client tries to bypass /app handlers and inject into the topic
        aliceSession.send("/topic/public", Map.of("sender", "admin", "content", "injected"));

        assertThat(bobInbox.poll(1500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void unknownReceiverProducesPersonalError() throws Exception {
        String alice = newUser();
        StompSession aliceSession = connect(alice);
        BlockingQueue<Map> errors = subscribe(aliceSession, "/user/queue/errors", Map.class);

        Map<?, ?> error = awaitFrame(errors, () -> aliceSession.send("/app/chat.private",
                Map.of("receiver", "no_such_user_xyz", "content", "hi")));

        assertThat(String.valueOf(error.get("error"))).contains("Unknown receiver");
    }
}
