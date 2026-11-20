package com.example.chat.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, in-memory presence tracker.
 *
 * A user may hold several concurrent STOMP sessions (tabs/devices), so we map
 * username -> set of session ids and keep a reverse index for O(1) disconnects.
 * A user is online while at least one session remains.
 *
 * NOTE: single-node by design (Spring simple broker). For horizontal scaling,
 * swap this for Redis + a STOMP broker relay (see README roadmap).
 */
@Service
public class OnlineUserService {

    private final ConcurrentHashMap<String, Set<String>> sessionsByUser = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> userBySession = new ConcurrentHashMap<>();

    /** @return true if this session made the user transition from offline to online. */
    public boolean addSession(String username, String sessionId) {
        userBySession.put(sessionId, username);
        Set<String> sessions = sessionsByUser.computeIfAbsent(username, u -> ConcurrentHashMap.newKeySet());
        sessions.add(sessionId);
        return sessions.size() == 1;
    }

    /** @return true if the user has no remaining sessions (transitioned to offline). */
    public boolean removeSession(String sessionId) {
        String username = userBySession.remove(sessionId);
        if (username == null) {
            return false;
        }
        // compute() is atomic per key, so concurrent add/remove for the same user is safe.
        Boolean[] wentOffline = {false};
        sessionsByUser.compute(username, (u, sessions) -> {
            if (sessions == null) {
                return null;
            }
            sessions.remove(sessionId);
            if (sessions.isEmpty()) {
                wentOffline[0] = true;
                return null; // drop the entry entirely
            }
            return sessions;
        });
        return wentOffline[0];
    }

    public boolean isOnline(String username) {
        Set<String> sessions = sessionsByUser.get(username);
        return sessions != null && !sessions.isEmpty();
    }

    /** Sorted snapshot for deterministic broadcasts and easy testing. */
    public List<String> getOnlineUsers() {
        return sessionsByUser.keySet().stream().sorted().toList();
    }

    public int onlineCount() {
        return sessionsByUser.size();
    }
}
