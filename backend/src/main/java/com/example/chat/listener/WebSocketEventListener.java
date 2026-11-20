package com.example.chat.listener;

import com.example.chat.service.OnlineUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.security.Principal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Keeps presence in sync with STOMP session lifecycle and broadcasts the
 * online-user list on /topic/users whenever it actually changes.
 */
@Component
public class WebSocketEventListener {

    private static final Logger log = LoggerFactory.getLogger(WebSocketEventListener.class);
    private static final String USERS_TOPIC = "/topic/users";

    private final OnlineUserService onlineUserService;
    private final SimpMessagingTemplate messagingTemplate;

    public WebSocketEventListener(OnlineUserService onlineUserService, SimpMessagingTemplate messagingTemplate) {
        this.onlineUserService = onlineUserService;
        this.messagingTemplate = messagingTemplate;
    }

    @EventListener
    public void onConnect(SessionConnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Principal user = accessor.getUser();
        String sessionId = accessor.getSessionId();
        if (user == null || sessionId == null) {
            return; // unauthenticated CONNECT (will be rejected by the interceptor)
        }
        boolean cameOnline = onlineUserService.addSession(user.getName(), sessionId);
        log.debug("STOMP connect: user={} session={}", user.getName(), sessionId);
        if (cameOnline) {
            broadcastOnlineUsers();
        }
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        if (sessionId == null) {
            return;
        }
        boolean wentOffline = onlineUserService.removeSession(sessionId);
        if (wentOffline) {
            broadcastOnlineUsers();
        }
    }

    /**
     * A freshly connected client subscribes AFTER its own CONNECT broadcast fired,
     * so it would miss the current snapshot. Re-broadcast when anyone subscribes
     * to the presence topic — cheap with the in-memory broker.
     *
     * The broadcast is delayed slightly because SessionSubscribeEvent fires when
     * the SUBSCRIBE frame is forwarded, BEFORE the broker has necessarily
     * registered the subscription; sending immediately could race past it.
     */
    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        if (USERS_TOPIC.equals(accessor.getDestination())) {
            CompletableFuture.runAsync(
                    this::broadcastOnlineUsers,
                    CompletableFuture.delayedExecutor(150, TimeUnit.MILLISECONDS));
        }
    }

    private void broadcastOnlineUsers() {
        messagingTemplate.convertAndSend(USERS_TOPIC, onlineUserService.getOnlineUsers());
    }
}
