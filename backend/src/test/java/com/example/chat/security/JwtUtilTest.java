package com.example.chat.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtUtilTest {

    private static final String SECRET = "unit-test-secret-0123456789-0123456789";
    private final JwtUtil jwtUtil = new JwtUtil(SECRET, 900_000, 604_800_000);

    @Test
    void accessTokenCarriesSubjectAndTypeClaim() {
        String token = jwtUtil.generateAccessToken("alice");
        Claims claims = jwtUtil.parseClaims(token);
        assertThat(claims.getSubject()).isEqualTo("alice");
        assertThat(claims.get("type", String.class)).isEqualTo("access");
        assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
        assertThat(jwtUtil.extractUsernameIfValid(token, JwtUtil.TYPE_ACCESS)).isEqualTo("alice");
    }

    @Test
    void tokenTypesAreNotInterchangeable() {
        String access = jwtUtil.generateAccessToken("alice");
        String refresh = jwtUtil.generateRefreshToken("alice");
        assertThat(jwtUtil.extractUsernameIfValid(access, JwtUtil.TYPE_REFRESH)).isNull();
        assertThat(jwtUtil.extractUsernameIfValid(refresh, JwtUtil.TYPE_ACCESS)).isNull();
        assertThat(jwtUtil.extractUsernameIfValid(refresh, JwtUtil.TYPE_REFRESH)).isEqualTo("alice");
    }

    @Test
    void expiredTokenIsRejected() {
        JwtUtil expiredUtil = new JwtUtil(SECRET, -1_000, -1_000);
        String token = expiredUtil.generateAccessToken("alice");
        assertThat(jwtUtil.extractUsernameIfValid(token, JwtUtil.TYPE_ACCESS)).isNull();
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = jwtUtil.generateAccessToken("alice");
        // Flip a char well inside the signature's significant bits. (The LAST
        // base64url char of a 32-byte HS256 signature carries padding bits that
        // decoders ignore, so flipping it can decode to identical bytes.)
        int idx = token.length() - 10;
        char c = token.charAt(idx);
        String tampered = token.substring(0, idx) + (c == 'A' ? 'B' : 'A') + token.substring(idx + 1);
        assertThat(jwtUtil.extractUsernameIfValid(tampered, JwtUtil.TYPE_ACCESS)).isNull();
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        JwtUtil other = new JwtUtil("another-secret-0123456789-0123456789-xyz", 900_000, 604_800_000);
        String foreign = other.generateAccessToken("alice");
        assertThat(jwtUtil.extractUsernameIfValid(foreign, JwtUtil.TYPE_ACCESS)).isNull();
    }

    @Test
    void garbageTokenIsRejected() {
        assertThat(jwtUtil.extractUsernameIfValid("garbage", JwtUtil.TYPE_ACCESS)).isNull();
        assertThat(jwtUtil.extractUsernameIfValid("", JwtUtil.TYPE_ACCESS)).isNull();
        assertThat(jwtUtil.extractUsernameIfValid(null, JwtUtil.TYPE_ACCESS)).isNull();
    }

    @Test
    void shortSecretFailsFastAtStartup() {
        assertThatThrownBy(() -> new JwtUtil("too-short", 900_000, 604_800_000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 characters");
    }
}
