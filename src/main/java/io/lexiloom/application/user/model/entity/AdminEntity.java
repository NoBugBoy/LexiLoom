package io.lexiloom.application.user.model.entity;

import io.lexiloom.application.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 管理员用户实体 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "ll_admin", comment = "管理员表")
public class AdminEntity extends BaseEntity {

  /** 邮箱账号 */
  @Column(comment = "邮箱账号", nullable = false, length = 100)
  private String email;

  /** 密码哈希 */
  @Column(comment = "密码", nullable = false, length = 200)
  private String password;

  /** 邮箱是否已认证 */
  @Column(name = "email_verified", comment = "邮箱是否已认证")
  private Boolean emailVerified;

  /** 是否开启了 Passkey/WebAuthn */
  @Column(name = "web_authn", comment = "是否开启了passkey")
  private Boolean webAuthn;

  /** WebAuthn 凭证 ID */
  @Column(name = "credential_id", comment = "webAuthn凭证id", length = 255)
  private String credentialId;

  /** WebAuthn 公钥 */
  @Column(name = "public_key", comment = "webAuthn公钥", columnDefinition = "TEXT")
  private String publicKey;

  /** 签名计数器 */
  @Column(name = "signature_count", comment = "计数器", length = 64)
  private String signatureCount;
}
