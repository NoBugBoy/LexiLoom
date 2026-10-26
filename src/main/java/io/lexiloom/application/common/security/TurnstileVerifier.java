package io.lexiloom.application.common.security;

import io.lexiloom.application.config.TurnstileProperties;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
@Slf4j
public class TurnstileVerifier {
  private static final ParameterizedTypeReference<Map<String, Object>> RESPONSE_TYPE =
      new ParameterizedTypeReference<>() {};

  private final TurnstileProperties properties;
  private final RestClient restClient = RestClient.create();

  public void verify(String token) {
    if (!properties.enabled()) {
      return;
    }
    if (properties.secretKey() == null || properties.secretKey().isBlank()) {
      log.error("Turnstile is enabled but its secret key is not configured");
      throw new IllegalStateException("Turnstile 服务端密钥未配置");
    }
    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException("请完成人机验证");
    }

    try {
      Map<String, Object> response =
          restClient
              .post()
              .uri(properties.siteverifyUrl())
              .body(Map.of("secret", properties.secretKey(), "response", token))
              .retrieve()
              .body(RESPONSE_TYPE);
      if (response == null || !Boolean.TRUE.equals(response.get("success"))) {
        throw new IllegalArgumentException("人机验证失败，请重试");
      }
    } catch (IllegalArgumentException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      log.warn("Turnstile Siteverify request failed", exception);
      throw new IllegalArgumentException("人机验证暂不可用，请稍后重试");
    }
  }
}
