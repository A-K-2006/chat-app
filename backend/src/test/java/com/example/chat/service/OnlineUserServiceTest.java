package com.example.chat.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class OnlineUserServiceTest {

    private final OnlineUserService service = new OnlineUserService();

    @Test
    void tracksOnlineTransitions() {
        assertThat(service.addSession("alice", "s1")).isTrue();   // offline -> online
        assertThat(service.isOnline("alice")).isTrue();
        assertThat(service.getOnlineUsers()).containsExactly("alice");

        assertThat(service.removeSession("s1")).isTrue();         // online -> offline
        assertThat(service.isOnline("alice")).isFalse();
        assertThat(service.getOnlineUsers()).isEmpty();
    }

    @Test
    void multipleSessionsKeepUserOnlineUntilLastDisconnect() {
        assertThat(service.addSession("alice", "s1")).isTrue();
        assertThat(service.addSession("alice", "s2")).isFalse();  // already online (second tab)
        assertThat(service.removeSession("s1")).isFalse();        // still one session left
        assertThat(service.isOnline("alice")).isTrue();
        assertThat(service.removeSession("s2")).isTrue();
        assertThat(service.isOnline("alice")).isFalse();
    }

    @Test
    void onlineListIsSortedAndUnknownSessionsAreIgnored() {
        service.addSession("zoe", "s1");
        service.addSession("alice", "s2");
        service.addSession("bob", "s3");
        assertThat(service.getOnlineUsers()).containsExactly("alice", "bob", "zoe");
        assertThat(service.onlineCount()).isEqualTo(3);
        assertThat(service.removeSession("does-not-exist")).isFalse();
    }

    @Test
    void concurrentAddRemoveLeavesConsistentState() throws Exception {
        int threads = 8;
        int sessionsPerThread = 250;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int id = t;
            futures.add(pool.submit(() -> {
                for (int i = 0; i < sessionsPerThread; i++) {
                    String session = "t" + id + "-s" + i;
                    service.addSession("alice", session);
                    service.removeSession(session);
                }
            }));
        }
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(service.isOnline("alice")).isFalse();
        assertThat(service.getOnlineUsers()).isEmpty();
    }
}
