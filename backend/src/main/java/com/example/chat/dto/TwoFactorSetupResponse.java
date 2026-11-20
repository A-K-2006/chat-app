package com.example.chat.dto;

/** 2FA setup response: the Base32 secret plus a scannable otpauth:// QR code as a data URI. */
public record TwoFactorSetupResponse(String secret, String qrCodeDataUri) {
}
