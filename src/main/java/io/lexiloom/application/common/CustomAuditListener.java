package io.lexiloom.application.common;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 通用 JPA 实体自动审计监听器
 *
 * @author yujian
 */
public class CustomAuditListener {

  @PrePersist
  public void touchForCreate(Object target) {
    if (target instanceof BaseEntity entity) {
      LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
      if (entity.getCreateTime() == null) {
        entity.setCreateTime(now);
      }
      entity.setUpdateTime(now);
      SecurityHolder.getLoginUser()
          .ifPresent(
              user -> {
                if (entity.getCreateBy() == null) {
                  entity.setCreateBy(user.userId());
                }
                entity.setUpdateBy(user.userId());
              });
    }
  }

  @PreUpdate
  public void touchForUpdate(Object target) {
    if (target instanceof BaseEntity entity) {
      entity.setUpdateTime(LocalDateTime.now(ZoneId.systemDefault()));
      SecurityHolder.getLoginUser().ifPresent(user -> entity.setUpdateBy(user.userId()));
    }
  }
}
