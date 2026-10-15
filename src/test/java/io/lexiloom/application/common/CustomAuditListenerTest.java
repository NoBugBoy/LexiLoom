package io.lexiloom.application.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

class CustomAuditListenerTest {

  private final CustomAuditListener listener = new CustomAuditListener();

  static class DummyEntity extends BaseEntity {
    private Long id;

    @Override
    public Long getId() {
      return id;
    }

    @Override
    public void setId(Long id) {
      this.id = id;
    }
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("touchForCreate - 带有登录上下文")
  void touchForCreateWithAuth() {
    LoginUserAuthentication auth = new LoginUserAuthentication(100L, List.of("ADMIN"));
    SecurityContextHolder.getContext().setAuthentication(auth);

    DummyEntity entity = new DummyEntity();
    listener.touchForCreate(entity);

    assertThat(entity.getCreateTime()).isNotNull();
    assertThat(entity.getUpdateTime()).isNotNull();
    assertThat(entity.getCreateBy()).isEqualTo(100L);
    assertThat(entity.getUpdateBy()).isEqualTo(100L);
  }

  @Test
  @DisplayName("touchForCreate - 无登录上下文")
  void touchForCreateWithoutAuth() {
    DummyEntity entity = new DummyEntity();
    listener.touchForCreate(entity);

    assertThat(entity.getCreateTime()).isNotNull();
    assertThat(entity.getUpdateTime()).isNotNull();
    assertThat(entity.getCreateBy()).isNull();
    assertThat(entity.getUpdateBy()).isNull();
  }

  @Test
  @DisplayName("touchForCreate - 非 BaseEntity 对象")
  void touchForCreateNonBaseEntity() {
    assertThatCode(() -> listener.touchForCreate(new Object())).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("touchForUpdate - 带有登录上下文")
  void touchForUpdateWithAuth() {
    LoginUserAuthentication auth = new LoginUserAuthentication(200L, List.of("ADMIN"));
    SecurityContextHolder.getContext().setAuthentication(auth);

    DummyEntity entity = new DummyEntity();
    LocalDateTime oldTime = LocalDateTime.now().minusDays(1);
    entity.setCreateTime(oldTime);
    entity.setUpdateTime(oldTime);

    listener.touchForUpdate(entity);

    assertThat(entity.getUpdateTime()).isAfter(oldTime);
    assertThat(entity.getUpdateBy()).isEqualTo(200L);
  }

  @Test
  @DisplayName("touchForUpdate - 无登录上下文")
  void touchForUpdateWithoutAuth() {
    DummyEntity entity = new DummyEntity();
    listener.touchForUpdate(entity);

    assertThat(entity.getUpdateTime()).isNotNull();
    assertThat(entity.getUpdateBy()).isNull();
  }

  @Test
  @DisplayName("touchForUpdate - 非 BaseEntity 对象")
  void touchForUpdateNonBaseEntity() {
    assertThatCode(() -> listener.touchForUpdate(new Object())).doesNotThrowAnyException();
  }
}
