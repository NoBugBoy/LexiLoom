package io.lexiloom.application.utils;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public class RsaKeyUtils {
  private RsaKeyUtils() {}

  /**
   * 将 RSA 公钥字符串实例化为 PublicKey 对象
   *
   * @param publicKeyStr 可以是纯 Base64，也可以带 -----BEGIN PUBLIC KEY-----
   */
  public static PublicKey getPublicKey(String publicKeyStr) {
    try {
      // 1. 清洗字符串：去掉 PEM 头尾标识、所有换行符和空格
      String cleanKey =
          publicKeyStr
              .replace("-----BEGIN PUBLIC KEY-----", "")
              .replace("-----END PUBLIC KEY-----", "")
              .replaceAll("\\s+", ""); // 去掉所有空白字符（\r, \n, 空格, tab）
      // 2. Base64 解码为字节数组
      byte[] keyBytes = Base64.getDecoder().decode(cleanKey);
      // 3. 使用标准 X509 规范构建 KeySpec
      X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
      // 4. 通过 RSA KeyFactory 实例化为 PublicKey 对象
      KeyFactory keyFactory = KeyFactory.getInstance("RSA");
      return keyFactory.generatePublic(spec);
    } catch (Exception e) {
      throw new IllegalArgumentException("RSA 公钥实例化失败，请检查公钥格式是否正确", e);
    }
  }

  /**
   * 将 RSA 私钥字符串实例化为 PrivateKey 对象
   *
   * @param privateKeyStr 可以是纯 Base64，也可以带 -----BEGIN PRIVATE KEY-----
   */
  public static PrivateKey getPrivateKey(String privateKeyStr) {
    try {
      // 1. 清洗字符串：去掉 PEM 头尾标识、所有换行符和空格
      String cleanKey =
          privateKeyStr
              .replace("-----BEGIN PRIVATE KEY-----", "")
              .replace("-----END PRIVATE KEY-----", "")
              .replace("-----BEGIN RSA PRIVATE KEY-----", "")
              .replace("-----END RSA PRIVATE KEY-----", "")
              .replaceAll("\\s+", ""); // 去掉所有空白字符（\r, \n, 空格, tab）

      // 2. Base64 解码为字节数组
      byte[] keyBytes = Base64.getDecoder().decode(cleanKey);

      // 3. 👈 核心区别：私钥使用 PKCS8 规范构建 KeySpec
      PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);

      // 4. 通过 RSA KeyFactory 实例化为 PrivateKey 对象
      KeyFactory keyFactory = KeyFactory.getInstance("RSA");
      return keyFactory.generatePrivate(spec);

    } catch (Exception e) {
      throw new IllegalArgumentException("RSA 私钥实例化失败，请检查私钥格式是否正确", e);
    }
  }
}
