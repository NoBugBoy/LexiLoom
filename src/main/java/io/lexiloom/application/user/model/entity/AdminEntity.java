package io.lexiloom.application.user.model.entity;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.common.BaseEntity;
import io.lexiloom.application.exception.BusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 管理员用户实体 (充血领域模型) */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
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

  /**
   * 静态工厂创建管理员
   */
  public static AdminEntity create(String email, String password, Boolean emailVerified) {
    if (StrUtil.isBlank(email)) {
      throw BusinessException.badRequest("邮箱不能为空");
    }
    if (StrUtil.isBlank(password)) {
      throw BusinessException.badRequest("密码不能为空");
    }
    AdminEntity admin = new AdminEntity();
    admin.email = email.trim().toLowerCase();
    admin.password = password;
    admin.emailVerified = emailVerified != null ? emailVerified : Boolean.FALSE;
    admin.webAuthn = Boolean.FALSE;
    return admin;
  }

  /**
   * 修改管理员密码
   */
  public void changePassword(String newEncodedPassword) {
    if (StrUtil.isBlank(newEncodedPassword)) {
      throw BusinessException.badRequest("新密码不能为空");
    }
    this.password = newEncodedPassword;
  }

  /**
   * 绑定 Passkey / WebAuthn 凭证
   */
  public void bindPasskey(String credentialId, String publicKey, String signatureCount) {
    this.webAuthn = Boolean.TRUE;
    this.credentialId = credentialId;
    this.publicKey = publicKey;
    this.signatureCount = signatureCount;
  }

  /**
   * 认证邮箱
   */
  public void verifyEmail() {
    this.emailVerified = Boolean.TRUE;
  }
}
