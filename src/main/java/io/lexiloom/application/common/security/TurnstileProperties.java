package io.lexiloom.application.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "turnstile")
public record TurnstileProperties(boolean enabled, String secretKey, String siteverifyUrl) {

  private static final String DEFAULT_SITEVERIFY_URL =
      "https://challenges.cloudflare.com/turnstile/v0/siteverify";

  public TurnstileProperties {
    secretKey = secretKey != null ? secretKey.trim() : "";
    siteverifyUrl =
        StringUtils.hasText(siteverifyUrl) ? siteverifyUrl.trim() : DEFAULT_SITEVERIFY_URL;
  }

  @Override
  public String toString() {
    return "TurnstileProperties[enabled="
        + enabled
        + ", secretKey=******"
        + ", siteverifyUrl="
        + siteverifyUrl
        + "]";
  }
}
