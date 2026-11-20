package com.example.chat.controller;

import com.example.chat.repository.UserRepository;
import com.example.chat.security.JwtUtil;
import com.example.chat.service.TwoFactorService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private TwoFactorService twoFactorService;

    private static String randomUsername() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private MvcResult register(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void registerSuccessReturnsTokensAndHashesPassword() throws Exception {
        String username = randomUsername();
        JsonNode body = json(register(username, "password123"));

        assertThat(body.get("accessToken").asText()).isNotBlank();
        assertThat(body.get("refreshToken").asText()).isNotBlank();
        assertThat(body.get("username").asText()).isEqualTo(username);
        assertThat(body.get("twoFactorEnabled").asBoolean()).isFalse();
        assertThat(jwtUtil.extractUsernameIfValid(body.get("accessToken").asText(), JwtUtil.TYPE_ACCESS))
                .isEqualTo(username);

        String storedHash = userRepository.findByUsername(username).orElseThrow().getPassword();
        assertThat(storedHash).startsWith("$2").isNotEqualTo("password123");
    }

    @Test
    void registerDuplicateUsernameIsRejected() throws Exception {
        String username = randomUsername();
        register(username, "password123");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", "password123"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Username is already taken"));
    }

    @Test
    void registerValidatesInput() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", randomUsername(), "password", "short"))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "bad name!", "password", "password123"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loginSuccessReturnsValidAccessToken() throws Exception {
        String username = randomUsername();
        register(username, "password123");

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", "password123"))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = json(result);
        assertThat(jwtUtil.extractUsernameIfValid(body.get("accessToken").asText(), JwtUtil.TYPE_ACCESS))
                .isEqualTo(username);
        assertThat(jwtUtil.extractUsernameIfValid(body.get("refreshToken").asText(), JwtUtil.TYPE_REFRESH))
                .isEqualTo(username);
    }

    @Test
    void loginWithInvalidCredentialsIsRejected() throws Exception {
        String username = randomUsername();
        register(username, "password123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", "wrong-password"))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "ghost_user_xyz", "password", "password123"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshTokenFlowRotatesTokensAndEnforcesType() throws Exception {
        String username = randomUsername();
        JsonNode initial = json(register(username, "password123"));

        // Valid refresh token -> new token pair
        MvcResult refreshed = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", initial.get("refreshToken").asText()))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(jwtUtil.extractUsernameIfValid(json(refreshed).get("accessToken").asText(), JwtUtil.TYPE_ACCESS))
                .isEqualTo(username);

        // An ACCESS token must never be accepted as a refresh token
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", initial.get("accessToken").asText()))))
                .andExpect(status().isUnauthorized());

        // Garbage token
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", "not.a.jwt"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/messages/public")).andExpect(status().isUnauthorized());
    }

    @Test
    void fullTwoFactorEnrollmentAndLoginFlow() throws Exception {
        String username = randomUsername();
        String accessToken = json(register(username, "password123")).get("accessToken").asText();

        // 1. Setup: secret + QR code
        MvcResult setup = mockMvc.perform(post("/api/auth/2fa/setup")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn();
        String secret = json(setup).get("secret").asText();
        assertThat(json(setup).get("qrCodeDataUri").asText()).startsWith("data:image/png;base64,");

        // 2. Wrong code cannot enable 2FA
        String wrongCode = String.format("%06d", (Integer.parseInt(twoFactorService.currentCode(secret)) + 500000) % 1000000);
        mockMvc.perform(post("/api/auth/2fa/verify")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", wrongCode))))
                .andExpect(status().isBadRequest());

        // 3. Correct code enables 2FA
        mockMvc.perform(post("/api/auth/2fa/verify")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", twoFactorService.currentCode(secret)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.twoFactorEnabled").value(true));

        // 4. Login without a code -> 2FA_REQUIRED
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", "password123"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("2FA_REQUIRED"));

        // 5. Login with a valid TOTP code succeeds
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "password", "password123",
                                "twoFactorCode", twoFactorService.currentCode(secret)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.twoFactorEnabled").value(true));
    }
}
