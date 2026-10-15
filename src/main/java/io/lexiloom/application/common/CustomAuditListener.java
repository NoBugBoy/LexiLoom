package io.lexiloom.application.common;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

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
      entity.setCreateTime(now);
      entity.setUpdateTime(now);
      Authentication auth = SecurityContextHolder.getContext().getAuthentication();
      if (auth instanceof LoginUserAuthentication user && auth.isAuthenticated()) {
        entity.setCreateBy(user.userId());
        entity.setUpdateBy(user.userId());
      }
    }
  }

  @PreUpdate
  public void touchForUpdate(Object target) {
    if (target instanceof BaseEntity entity) {
      entity.setUpdateTime(LocalDateTime.now(ZoneId.systemDefault()));
      Authentication auth = SecurityContextHolder.getContext().getAuthentication();
      if (auth instanceof LoginUserAuthentication user && auth.isAuthenticated()) {
        entity.setUpdateBy(user.userId());
      }
    }
  }
}
