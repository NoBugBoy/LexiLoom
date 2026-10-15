package io.lexiloom.application.user.model.entity;

import io.lexiloom.application.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * @author yujian
 */
@Entity
@Table(name = "ll_user", comment = "用户表")
@Getter
@Setter
@NoArgsConstructor
public class UserEntity extends BaseEntity {
  @Column(comment = "小程序openId")
  private String openId;

  @Column(comment = "小程序unionId")
  private String unionId;

  @Column(comment = "wx头像")
  private String avatarUrl;

  @Column(comment = "wx昵称")
  private String userName;

  @Column(comment = "邮箱")
  private String email;

  @Column(comment = "邮箱是否已认证")
  private Boolean emailVerified;

  @Column(comment = "wx手机号")
  private String phoneNumber;

  @Column(comment = "手机号国家编码")
  private String countryCode;

  @Column(comment = "手机号是否已认证")
  private Boolean phoneVerified;
}
