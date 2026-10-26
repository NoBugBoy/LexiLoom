package io.lexiloom.application.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "turnstile")
public record TurnstileProperties(boolean enabled, String secretKey, String siteverifyUrl) {}
