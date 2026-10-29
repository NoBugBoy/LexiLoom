package io.lexiloom.application.common.security;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@Slf4j
public class TurnstileVerifier {
  private static final ParameterizedTypeReference<Map<String, Object>> RESPONSE_TYPE =
      new ParameterizedTypeReference<>() {};
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

  private final TurnstileProperties properties;
  private final RestClient restClient;

  @Autowired
  public TurnstileVerifier(TurnstileProperties properties) {
    this(properties, createDefaultRestClient());
  }

  TurnstileVerifier(TurnstileProperties properties, RestClient restClient) {
    this.properties = properties;
    this.restClient = restClient;
  }

  private static RestClient createDefaultRestClient() {
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(READ_TIMEOUT);
    return RestClient.builder().requestFactory(requestFactory).build();
  }

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
