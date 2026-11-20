package com.example.chat.controller;

import com.example.chat.dto.AuthResponse;
import com.example.chat.dto.LoginRequest;
import com.example.chat.dto.RefreshRequest;
import com.example.chat.dto.RegisterRequest;
import com.example.chat.dto.TwoFactorSetupResponse;
import com.example.chat.dto.TwoFactorVerifyRequest;
import com.example.chat.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request));
    }

    /** Requires a valid access token. Returns the TOTP secret + QR code (2FA stays disabled until verified). */
    @PostMapping("/2fa/setup")
    public ResponseEntity<TwoFactorSetupResponse> setupTwoFactor(Principal principal) {
        return ResponseEntity.ok(authService.setupTwoFactor(principal.getName()));
    }

    /** Confirms a 6-digit TOTP code and enables 2FA on the account. */
    @PostMapping("/2fa/verify")
    public ResponseEntity<Map<String, Object>> verifyTwoFactor(@Valid @RequestBody TwoFactorVerifyRequest request,
                                                               Principal principal) {
        authService.verifyAndEnableTwoFactor(principal.getName(), request.code());
        return ResponseEntity.ok(Map.of("twoFactorEnabled", true));
    }
}
