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

/**
 * 前台用户实体 (充血领域模型)
 *
 * @author yujian
 */
@Entity
@Table(name = "ll_user", comment = "用户表")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserEntity extends BaseEntity {

  /** 用户 openId / 账号标识 */
  @Column(name = "open_id", comment = "用户openId", length = 64)
  private String openId;

  /** 用户 unionId */
  @Column(name = "union_id", comment = "用户unionId", length = 64)
  private String unionId;

  /** 用户头像 URL */
  @Column(name = "avatar_url", comment = "用户头像", length = 500)
  private String avatarUrl;

  /** 用户昵称 */
  @Column(name = "user_name", comment = "用户昵称", length = 100)
  private String userName;

  /** 用户邮箱地址 */
  @Column(comment = "邮箱", length = 100)
  private String email;

  /** 邮箱是否已认证 */
  @Column(name = "email_verified", comment = "邮箱是否已认证")
  private Boolean emailVerified;

  /** 手机号 */
  @Column(name = "phone_number", comment = "手机号", length = 32)
  private String phoneNumber;

  /** 手机号国际区号编码 (如 +86) */
  @Column(name = "country_code", comment = "手机号国家编码", length = 10)
  private String countryCode;

  /** 手机号是否已认证 */
  @Column(name = "phone_verified", comment = "手机号是否已认证")
  private Boolean phoneVerified;

  /**
   * 静态工厂创建基础用户
   */
  public static UserEntity create(String openId, String unionId) {
    return create(openId, unionId, null, null, null, null, null, null, null);
  }

  /**
   * 静态工厂创建完整注册用户
   */
  public static UserEntity create(
      String openId,
      String unionId,
      String phoneNumber,
      String countryCode,
      Boolean phoneVerified,
      String email,
      Boolean emailVerified,
      String userName,
      String avatarUrl) {
    if (StrUtil.isBlank(openId)) {
      throw BusinessException.badRequest("openId不能为空");
    }
    UserEntity user = new UserEntity();
    user.openId = openId.trim();
    user.unionId = StrUtil.trimToNull(unionId);
    user.phoneNumber = StrUtil.trimToNull(phoneNumber);
    user.countryCode = StrUtil.trimToNull(countryCode);
    user.phoneVerified = phoneVerified != null ? phoneVerified : Boolean.FALSE;
    user.email = StrUtil.trimToNull(email);
    user.emailVerified = emailVerified != null ? emailVerified : Boolean.FALSE;
    user.userName = StrUtil.trimToNull(userName);
    user.avatarUrl = StrUtil.trimToNull(avatarUrl);
    return user;
  }

  /**
   * 更新个人基本资料 (昵称与头像)
   */
  public void updateProfile(String nickName, String avatarUrl) {
    if (nickName != null) {
      this.userName = nickName.trim();
    }
    if (avatarUrl != null) {
      this.avatarUrl = avatarUrl.trim();
    }
  }

  /**
   * 绑定或更新邮箱
   */
  public void bindEmail(String email, Boolean verified) {
    this.email = StrUtil.trimToNull(email);
    this.emailVerified = verified != null ? verified : Boolean.FALSE;
  }

  /**
   * 绑定或更新手机号
   */
  public void bindPhone(String phoneNumber, String countryCode, Boolean verified) {
    this.phoneNumber = StrUtil.trimToNull(phoneNumber);
    this.countryCode = StrUtil.trimToNull(countryCode);
    this.phoneVerified = verified != null ? verified : Boolean.FALSE;
  }
}
