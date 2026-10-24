package io.lexiloom.application.utils;

import cn.hutool.core.util.StrUtil;

/**
 * 敏感数据安全脱敏工具类
 *
 * <p>遵循工程规范，优先使用 HuTool 工具类 (如 {@link StrUtil}) 实现安全脱敏。
 */
public final class DesensitizeUtils {

  private static final String MASK_PLACEHOLDER = "****";

  private DesensitizeUtils() {}

  /**
   * 对外部调用凭证 / API Key 进行安全脱敏
   *
   * <ul>
   *   <li>若入参为空或已包含掩码 "****"，直接原样返回；
   *   <li>若字符串长度小于等于 8，首尾各保留 1 位，中间以 "*" 屏蔽；
   *   <li>若以 "lx_" 开头且长度充足 (>= 18)，保留完整前缀 (如 "lx_live_b7c2"，即前 12 位) 与末尾 4 位，中间以 "****" 隐藏；
   *   <li>其余通用密钥保留前 4 位与后 4 位，中间以 "****" 隐藏。
   * </ul>
   *
   * @param apiKey 原始 API Key 字符串
   * @return 脱敏后的安全字符串 (例如 "lx_live_687d****c1be")
   */
  public static String maskApiKey(String apiKey) {
    if (StrUtil.isBlank(apiKey)) {
      return apiKey;
    }
    String trimmed = apiKey.trim();
    if (trimmed.contains(MASK_PLACEHOLDER)) {
      return trimmed;
    }

    int len = trimmed.length();
    if (len <= 8) {
      return StrUtil.hide(trimmed, Math.min(1, len), Math.max(1, len - 1));
    }

    int prefixLen = (trimmed.startsWith("lx_") && len >= 18) ? 12 : 4;
    int suffixLen = 4;
    if (len <= prefixLen + suffixLen) {
      prefixLen = 2;
      suffixLen = 2;
    }

    return StrUtil.sub(trimmed, 0, prefixLen)
        + MASK_PLACEHOLDER
        + StrUtil.sub(trimmed, len - suffixLen, len);
  }
}
