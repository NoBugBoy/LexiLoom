package io.lexiloom.application.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class DesensitizeUtilsTest {

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "\t\n"})
  @DisplayName("空字符串或空白字符脱敏原样返回")
  void testMaskApiKeyBlank(String blankKey) {
    assertThat(DesensitizeUtils.maskApiKey(blankKey)).isEqualTo(blankKey);
  }

  @Test
  @DisplayName("标准 LexiLoom API Key (40 位) 脱敏保留 lx_live_前缀及后4位")
  void testMaskStandardLexiLoomKey() {
    String key = "lx_live_687dc903672049e6aa4ba85c493ec1be";
    String masked = DesensitizeUtils.maskApiKey(key);

    assertThat(masked).isEqualTo("lx_live_687d****c1be");
    assertThat(masked).doesNotContain("672049e6aa4ba85c493e");
  }

  @Test
  @DisplayName("通用长密钥保留前4位与后4位")
  void testMaskGenericLongKey() {
    String key = "sk-ant-api03-abcdef1234567890xyz";
    String masked = DesensitizeUtils.maskApiKey(key);

    assertThat(masked).isEqualTo("sk-a****0xyz");
  }

  @Test
  @DisplayName("超短字符串脱敏使用首尾保留中间星号")
  void testMaskShortKey() {
    String key = "12345678";
    String masked = DesensitizeUtils.maskApiKey(key);

    assertThat(masked).isEqualTo("1******8");
  }

  @Test
  @DisplayName("已脱敏包含 **** 的字符串幂等原样返回")
  void testMaskIdempotent() {
    String alreadyMasked = "lx_live_687d****c1be";
    String result = DesensitizeUtils.maskApiKey(alreadyMasked);

    assertThat(result).isEqualTo(alreadyMasked);
  }
}
