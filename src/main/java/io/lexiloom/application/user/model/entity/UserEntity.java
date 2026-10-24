package io.lexiloom.application.user.model.entity;

import io.lexiloom.application.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 前台用户实体
 *
 * @author yujian
 */
@Entity
@Table(name = "ll_user", comment = "用户表")
@Getter
@Setter
@NoArgsConstructor
public class UserEntity extends BaseEntity {

  /** 微信小程序 openId */
  @Column(name = "open_id", comment = "小程序openId", length = 64)
  private String openId;

  /** 微信开放平台 unionId */
  @Column(name = "union_id", comment = "小程序unionId", length = 64)
  private String unionId;

  /** 微信用户头像 URL */
  @Column(name = "avatar_url", comment = "wx头像", length = 500)
  private String avatarUrl;

  /** 微信用户昵称 */
  @Column(name = "user_name", comment = "wx昵称", length = 100)
  private String userName;

  /** 用户邮箱地址 */
  @Column(comment = "邮箱", length = 100)
  private String email;

  /** 邮箱是否已认证 */
  @Column(name = "email_verified", comment = "邮箱是否已认证")
  private Boolean emailVerified;

  /** 手机号 */
  @Column(name = "phone_number", comment = "wx手机号", length = 32)
  private String phoneNumber;

  /** 手机号国际区号编码 (如 +86) */
  @Column(name = "country_code", comment = "手机号国家编码", length = 10)
  private String countryCode;

  /** 手机号是否已认证 */
  @Column(name = "phone_verified", comment = "手机号是否已认证")
  private Boolean phoneVerified;
}
