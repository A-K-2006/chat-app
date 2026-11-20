package com.example.chat.security;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

import java.security.Principal;

/**
 * Authenticates the STOMP CONNECT frame and hardens every subsequent frame.
 *
 *  - CONNECT:   requires "Authorization: Bearer &lt;access token&gt;" native header;
 *               the validated Principal is attached to the whole WebSocket session.
 *  - SEND:      must be authenticated and may ONLY target /app/** — this stops
 *               clients from publishing straight into /topic/* and impersonating
 *               the server or other users.
 *  - SUBSCRIBE: must be authenticated; only /topic/** and personal /user/queue/**
 *               destinations are allowed.
 *
 * Message senders are always derived from the session Principal, never the payload.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;
    private final UserDetailsService userDetailsService;

    public StompAuthChannelInterceptor(JwtUtil jwtUtil, UserDetailsService userDetailsService) {
        this.jwtUtil = jwtUtil;
        this.userDetailsService = userDetailsService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message; // heartbeats / non-STOMP frames
        }
        switch (accessor.getCommand()) {
            case CONNECT -> authenticateConnect(accessor);
            case SEND -> {
                requirePrincipal(accessor);
                String destination = accessor.getDestination();
                if (destination == null || !destination.startsWith("/app/")) {
                    throw new MessageDeliveryException("Clients may only send to /app/** destinations");
                }
            }
            case SUBSCRIBE -> {
                requirePrincipal(accessor);
                String destination = accessor.getDestination();
                boolean allowed = destination != null
                        && (destination.startsWith("/topic/") || destination.startsWith("/user/queue/"));
                if (!allowed) {
                    throw new MessageDeliveryException("Subscription destination not allowed");
                }
            }
            default -> {
                // DISCONNECT, ACK, etc. — nothing to enforce
            }
        }
        return message;
    }

    private void authenticateConnect(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw new MessageDeliveryException("Missing bearer token in STOMP CONNECT");
        }
        String token = header.substring(BEARER_PREFIX.length()).strip();
        String username = jwtUtil.extractUsernameIfValid(token, JwtUtil.TYPE_ACCESS);
        if (username == null) {
            throw new MessageDeliveryException("Invalid or expired access token");
        }
        try {
            UserDetails userDetails = userDetailsService.loadUserByUsername(username);
            Principal authentication = new UsernamePasswordAuthenticationToken(
                    userDetails, null, userDetails.getAuthorities());
            accessor.setUser(authentication);
        } catch (UsernameNotFoundException e) {
            throw new MessageDeliveryException("Unknown account");
        }
    }

    private void requirePrincipal(StompHeaderAccessor accessor) {
        if (accessor.getUser() == null) {
            throw new MessageDeliveryException("Unauthenticated STOMP session");
        }
    }
}
