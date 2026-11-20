package com.example.chat.service;

import com.example.chat.dto.AuthResponse;
import com.example.chat.dto.LoginRequest;
import com.example.chat.dto.RefreshRequest;
import com.example.chat.dto.RegisterRequest;
import com.example.chat.dto.TwoFactorSetupResponse;
import com.example.chat.entity.User;
import com.example.chat.repository.UserRepository;
import com.example.chat.security.JwtUtil;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final TwoFactorService twoFactorService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager,
                       JwtUtil jwtUtil,
                       TwoFactorService twoFactorService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtUtil = jwtUtil;
        this.twoFactorService = twoFactorService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String username = request.username().strip();
        if (userRepository.existsByUsername(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username is already taken");
        }
        User user = userRepository.save(new User(username, passwordEncoder.encode(request.password())));
        return issueTokens(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        } catch (AuthenticationException e) {
            // Same message for unknown user and wrong password — no user enumeration.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
        User user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password"));

        if (user.isTwoFactorEnabled()) {
            if (request.twoFactorCode() == null || request.twoFactorCode().isBlank()) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "2FA_REQUIRED");
            }
            if (!twoFactorService.verifyCode(user.getTwoFactorSecret(), request.twoFactorCode())) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "INVALID_2FA_CODE");
            }
        }
        return issueTokens(user);
    }

    /** Validates the refresh token (signature, expiry AND type=refresh) and rotates both tokens. */
    @Transactional(readOnly = true)
    public AuthResponse refresh(RefreshRequest request) {
        String username = jwtUtil.extractUsernameIfValid(request.refreshToken(), JwtUtil.TYPE_REFRESH);
        if (username == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired refresh token");
        }
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown account"));
        return issueTokens(user);
    }

    /** Step 1 of enrollment: mint a secret, persist it (still disabled), return the QR code. */
    @Transactional
    public TwoFactorSetupResponse setupTwoFactor(String username) {
        User user = requireUser(username);
        if (user.isTwoFactorEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "2FA is already enabled");
        }
        String secret = twoFactorService.generateSecret();
        user.setTwoFactorSecret(secret);
        userRepository.save(user);
        return new TwoFactorSetupResponse(secret, twoFactorService.getQrCodeDataUri(secret, username));
    }

    /** Step 2 of enrollment: prove possession of the authenticator before flipping the flag. */
    @Transactional
    public void verifyAndEnableTwoFactor(String username, String code) {
        User user = requireUser(username);
        if (user.getTwoFactorSecret() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Start 2FA setup first");
        }
        if (user.isTwoFactorEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "2FA is already enabled");
        }
        if (!twoFactorService.verifyCode(user.getTwoFactorSecret(), code)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid verification code");
        }
        user.setTwoFactorEnabled(true);
        userRepository.save(user);
    }

    private User requireUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    private AuthResponse issueTokens(User user) {
        return AuthResponse.bearer(
                jwtUtil.generateAccessToken(user.getUsername()),
                jwtUtil.generateRefreshToken(user.getUsername()),
                jwtUtil.getAccessExpirationSeconds(),
                user.getUsername(),
                user.isTwoFactorEnabled());
    }
}
