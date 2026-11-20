package com.example.chat.controller;

import com.example.chat.entity.User;
import com.example.chat.repository.UserRepository;
import com.example.chat.security.JwtUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FileControllerTest {

    /** Smallest possible valid PNG (1x1 transparent pixel). */
    private static final byte[] PNG_BYTES = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtUtil jwtUtil;

    private String tokenForNewUser() {
        String username = "f" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        userRepository.save(new User(username, passwordEncoder.encode("password123")));
        return jwtUtil.generateAccessToken(username);
    }

    @Test
    void uploadImageThenDownloadItWithoutAuthHeader() throws Exception {
        String token = tokenForNewUser();
        MvcResult upload = mockMvc.perform(multipart("/api/files/upload")
                        .file(new MockMultipartFile("file", "cat.png", "image/png", PNG_BYTES))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode body = objectMapper.readTree(upload.getResponse().getContentAsString());
        assertThat(body.get("fileType").asText()).isEqualTo("IMAGE");
        assertThat(body.get("fileName").asText()).isEqualTo("cat.png");
        String fileUrl = body.get("fileUrl").asText();
        assertThat(fileUrl).matches("^/api/files/[0-9a-f\\-]{36}\\.png$");

        // Capability URL: <img src> works without an Authorization header
        byte[] downloaded = mockMvc.perform(get(fileUrl))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(downloaded).isEqualTo(PNG_BYTES);
    }

    @Test
    void uploadRequiresAuthentication() throws Exception {
        mockMvc.perform(multipart("/api/files/upload")
                        .file(new MockMultipartFile("file", "cat.png", "image/png", PNG_BYTES)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsDisallowedExtension() throws Exception {
        mockMvc.perform(multipart("/api/files/upload")
                        .file(new MockMultipartFile("file", "evil.exe", "application/octet-stream", new byte[]{1}))
                        .header("Authorization", "Bearer " + tokenForNewUser()))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void rejectsEmptyFile() throws Exception {
        mockMvc.perform(multipart("/api/files/upload")
                        .file(new MockMultipartFile("file", "empty.png", "image/png", new byte[0]))
                        .header("Authorization", "Bearer " + tokenForNewUser()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsFileOverTenMegabytes() throws Exception {
        byte[] big = new byte[11 * 1024 * 1024];
        mockMvc.perform(multipart("/api/files/upload")
                        .file(new MockMultipartFile("file", "big.png", "image/png", big))
                        .header("Authorization", "Bearer " + tokenForNewUser()))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void rejectsPathTraversalAndUnknownNames() throws Exception {
        // Encoded slashes are rejected by Spring's StrictHttpFirewall (400) before
        // the controller; anything that slips past must still 404. Both are denials.
        mockMvc.perform(get("/api/files/..%2F..%2Fapplication.properties"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(get("/api/files/not-a-uuid.png"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/files/" + UUID.randomUUID() + ".png"))
                .andExpect(status().isNotFound());
    }

    @Test
    void acceptsDocumentUpload() throws Exception {
        MvcResult upload = mockMvc.perform(multipart("/api/files/upload")
                        .file(new MockMultipartFile("file", "notes.pdf", "application/pdf", "%PDF-1.4 test".getBytes()))
                        .header("Authorization", "Bearer " + tokenForNewUser()))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(upload.getResponse().getContentAsString());
        assertThat(body.get("fileType").asText()).isEqualTo("FILE");
    }
}
