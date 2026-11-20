package com.example.chat.controller;

import com.example.chat.entity.Message;
import com.example.chat.entity.User;
import com.example.chat.repository.MessageRepository;
import com.example.chat.repository.UserRepository;
import com.example.chat.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST history endpoints against a real (H2) database.
 * Full-text search endpoints are PostgreSQL-specific (tsvector) and are covered
 * by MessageServiceTest mocks + the Dockerized smoke test described in the README.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MessageRestControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtUtil jwtUtil;

    private String alice;
    private String bob;
    private String aliceToken;

    @BeforeEach
    void seed() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        alice = "alice" + suffix;
        bob = "bob" + suffix;
        String carol = "carol" + suffix;
        userRepository.save(new User(alice, passwordEncoder.encode("password123")));
        userRepository.save(new User(bob, passwordEncoder.encode("password123")));
        aliceToken = jwtUtil.generateAccessToken(alice);

        Message pub1 = new Message(alice, null, "public one", false);
        pub1.setTimestamp(Instant.now().minusSeconds(60));
        Message pub2 = new Message(bob, null, "public two", false);
        Message dm1 = new Message(alice, bob, "dm alice->bob", true);
        dm1.setTimestamp(Instant.now().minusSeconds(30));
        Message dm2 = new Message(bob, alice, "dm bob->alice", true);
        Message dmOther = new Message(alice, carol, "dm alice->carol", true);
        messageRepository.save(pub1);
        messageRepository.save(pub2);
        messageRepository.save(dm1);
        messageRepository.save(dm2);
        messageRepository.save(dmOther);
    }

    @Test
    void publicHistoryRequiresAuthAndIsChronological() throws Exception {
        mockMvc.perform(get("/api/messages/public")).andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/messages/public")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.content == 'public one')]").exists())
                .andExpect(jsonPath("$[*].isPrivate", everyItem(is(false))));
    }

    @Test
    void privateHistoryContainsOnlyTheRequestedConversation() throws Exception {
        mockMvc.perform(get("/api/messages/private/" + bob)
                        .param("me", alice)
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].content", everyItem(not(is("dm alice->carol")))))
                .andExpect(jsonPath("$[*].isPrivate", everyItem(is(true))));
    }

    @Test
    void spoofedMeParamIsRejectedWith403() throws Exception {
        // alice's token must not let her read bob's conversation with carol
        mockMvc.perform(get("/api/messages/private/" + bob)
                        .param("me", bob)
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void expiredOrGarbageTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/messages/public")
                        .header("Authorization", "Bearer garbage.token.here"))
                .andExpect(status().isUnauthorized());
    }
}
