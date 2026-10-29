package io.lexiloom.application.common.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.lexiloom.application.common.security.TurnstileProperties;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

@DisplayName("TurnstileVerifier 人机验证器单元测试")
class TurnstileVerifierTest {

  @Test
  @DisplayName("未启用 Turnstile 时直接放行")
  void testDisabled() {
    TurnstileVerifier verifier =
        new TurnstileVerifier(new TurnstileProperties(false, "", "https://example.com"));
    assertThatCode(() -> verifier.verify(null)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("启用但未配置密钥或 Token 为空时抛出异常")
  void testMissingSecretOrToken() {
    TurnstileVerifier noSecretVerifier =
        new TurnstileVerifier(new TurnstileProperties(true, " ", "https://example.com"));
    assertThatThrownBy(() -> noSecretVerifier.verify("tok"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("密钥未配置");

    TurnstileVerifier verifier =
        new TurnstileVerifier(new TurnstileProperties(true, "secret", "https://example.com"));
    assertThatThrownBy(() -> verifier.verify(" "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("请完成人机验证");
  }

  @Test
  @DisplayName("远端校验成功与失败分支")
  @SuppressWarnings("unchecked")
  void testRemoteVerifySuccessAndFailure() {
    RestClient restClient = mock(RestClient.class);
    RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
    RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
    RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

    when(restClient.post()).thenReturn(uriSpec);
    when(uriSpec.uri("https://example.com/siteverify")).thenReturn(bodySpec);
    when(bodySpec.body(any(Map.class))).thenReturn(bodySpec);
    when(bodySpec.retrieve()).thenReturn(responseSpec);

    TurnstileVerifier verifier =
        new TurnstileVerifier(
            new TurnstileProperties(true, "secret", "https://example.com/siteverify"), restClient);

    when(responseSpec.body(any(ParameterizedTypeReference.class)))
        .thenReturn(Map.of("success", true));
    assertThatCode(() -> verifier.verify("valid-token")).doesNotThrowAnyException();

    when(responseSpec.body(any(ParameterizedTypeReference.class)))
        .thenReturn(Map.of("success", false));
    assertThatThrownBy(() -> verifier.verify("invalid-token"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("人机验证失败");
  }
}
