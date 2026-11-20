package com.example.chat.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * JWT issuing/validation with jjwt 0.12.x.
 * HS256, secret from the JWT_SECRET environment variable (32+ chars enforced at startup).
 * Access and refresh tokens are cryptographically identical but carry a distinct
 * "type" claim that every consumer MUST check — a refresh token can never be used
 * to call the API, and an access token can never be used to refresh.
 */
@Component
public class JwtUtil {

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";
    private static final String TYPE_CLAIM = "type";

    private final SecretKey key;
    private final long accessExpirationMs;
    private final long refreshExpirationMs;

    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.access-expiration-ms}") long accessExpirationMs,
                   @Value("${jwt.refresh-expiration-ms}") long refreshExpirationMs) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET must be set to a value of at least 32 characters (see .env.example)");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessExpirationMs = accessExpirationMs;
        this.refreshExpirationMs = refreshExpirationMs;
    }

    public String generateAccessToken(String username) {
        return buildToken(username, TYPE_ACCESS, accessExpirationMs);
    }

    public String generateRefreshToken(String username) {
        return buildToken(username, TYPE_REFRESH, refreshExpirationMs);
    }

    /**
     * @return the username iff the token has a valid signature, is unexpired,
     *         and carries exactly the expected type claim; otherwise null.
     */
    public String extractUsernameIfValid(String token, String expectedType) {
        try {
            Claims claims = parseClaims(token);
            if (!expectedType.equals(claims.get(TYPE_CLAIM, String.class))) {
                return null;
            }
            return claims.getSubject();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long getAccessExpirationSeconds() {
        return accessExpirationMs / 1000;
    }

    private String buildToken(String username, String type, long ttlMs) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim(TYPE_CLAIM, type)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(ttlMs)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }
}
