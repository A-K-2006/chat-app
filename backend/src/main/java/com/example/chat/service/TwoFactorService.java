package com.example.chat.service;

import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.exceptions.QrGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import dev.samstevens.totp.util.Utils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * TOTP two-factor authentication (RFC 6238).
 * SHA1 / 6 digits / 30s period — the parameters every authenticator app expects.
 */
@Service
public class TwoFactorService {

    public static final String ISSUER = "ChatSphere";

    private final SecretGenerator secretGenerator = new DefaultSecretGenerator();
    private final CodeGenerator codeGenerator = new DefaultCodeGenerator();
    private final TimeProvider timeProvider = new SystemTimeProvider();

    public String generateSecret() {
        return secretGenerator.generate();
    }

    /** Builds the otpauth:// QR code as a PNG data URI the frontend can render directly. */
    public String getQrCodeDataUri(String secret, String username) {
        QrData data = new QrData.Builder()
                .label(username)
                .secret(secret)
                .issuer(ISSUER)
                .algorithm(HashingAlgorithm.SHA1)
                .digits(6)
                .period(30)
                .build();
        try {
            QrGenerator generator = new ZxingPngQrGenerator();
            byte[] png = generator.generate(data);
            return Utils.getDataUriForImage(png, generator.getImageMimeType());
        } catch (QrGenerationException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not generate QR code");
        }
    }

    /** Allows one 30s period of clock drift in either direction. */
    public boolean verifyCode(String secret, String code) {
        if (secret == null || code == null) {
            return false;
        }
        CodeVerifier verifier = new DefaultCodeVerifier(codeGenerator, timeProvider);
        ((DefaultCodeVerifier) verifier).setAllowedTimePeriodDiscrepancy(1);
        return verifier.isValidCode(secret, code.strip());
    }

    /** Generates the current valid code — used by tests to simulate an authenticator app. */
    public String currentCode(String secret) {
        try {
            return codeGenerator.generate(secret, Math.floorDiv(timeProvider.getTime(), 30));
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate TOTP code", e);
        }
    }
}
