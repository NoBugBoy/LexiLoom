package io.lexiloom.application.user.model.request;

public record AdminLoginRequest(
    String email, String password, String credentialId, String publicKey, String turnstileToken) {}
