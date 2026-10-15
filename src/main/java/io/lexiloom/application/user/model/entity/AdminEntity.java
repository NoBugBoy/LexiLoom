package io.lexiloom.application.user.model.entity;

import io.lexiloom.application.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "ll_admin")
public class AdminEntity extends BaseEntity {
  @Column(comment = "邮箱账号")
  private String email;

  @Column(comment = "密码")
  private String password;

  @Column(comment = "邮箱是否已认证")
  private Boolean emailVerified;

  @Column(comment = "是否开启了passkey")
  private Boolean webAuthn;

  @Column(comment = "webAuthn凭证id")
  private String credentialId;

  @Column(comment = "webAuthn公钥")
  private String publicKey;

  @Column(comment = "计数器")
  private String signatureCount;
}
