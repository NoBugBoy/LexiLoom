package io.lexiloom.application.user.model.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lexiloom.application.enums.AiLlmProviderEnum;
import io.lexiloom.application.enums.OpenApiKeyStatusEnum;
import io.lexiloom.application.enums.OpenApiScopeEnum;
import io.lexiloom.application.exception.BusinessException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OpenApiKeyEntityTest {

  @Test
  @DisplayName("创建实体 - 正常初始化与充血校验")
  void create_success() {
    OpenApiKeyEntity entity =
        OpenApiKeyEntity.create(
            100L,
            "OA-Test",
            "lx_live_123456",
            "lx_live_12****56",
            "kb:read,kb:search",
            "1,2,3",
            LocalDateTime.now().plusDays(30),
            AiLlmProviderEnum.QWEN,
            "sk-dashscope-test");

    assertThat(entity.getUserId()).isEqualTo(100L);
    assertThat(entity.getName()).isEqualTo("OA-Test");
    assertThat(entity.getApiKey()).isEqualTo("lx_live_123456");
    assertThat(entity.getStatus()).isEqualTo(OpenApiKeyStatusEnum.ENABLED);
    assertThat(entity.isUsable()).isTrue();
    assertThat(entity.hasScope(OpenApiScopeEnum.KB_READ)).isTrue();
    assertThat(entity.hasScope(OpenApiScopeEnum.KB_SEARCH)).isTrue();
    assertThat(entity.hasScope(OpenApiScopeEnum.DOC_READ)).isFalse();
    assertThat(entity.isWorkspaceAllowed(1L)).isTrue();
    assertThat(entity.isWorkspaceAllowed(2L)).isTrue();
    assertThat(entity.isWorkspaceAllowed(99L)).isFalse();
    assertThat(entity.getLlmProvider()).isEqualTo(AiLlmProviderEnum.QWEN);
  }

  @Test
  @DisplayName("创建实体 - 缺少必填参数抛出异常")
  void create_invalidParams_throwsException() {
    assertThatThrownBy(
            () ->
                OpenApiKeyEntity.create(
                    null, "Name", "lx_live_1", "masked", null, null, null, null, null))
        .isInstanceOf(BusinessException.class);

    assertThatThrownBy(
            () ->
                OpenApiKeyEntity.create(
                    1L, "", "lx_live_1", "masked", null, null, null, null, null))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("轮转密钥 - 更新 key 与 maskedKey")
  void rotateKey_success() {
    OpenApiKeyEntity entity =
        OpenApiKeyEntity.create(
            100L, "OA", "lx_live_old", "lx_live_old_masked", null, null, null, null, null);

    entity.rotateKey("lx_live_new", "lx_live_new_masked");

    assertThat(entity.getApiKey()).isEqualTo("lx_live_new");
    assertThat(entity.getMaskedKey()).isEqualTo("lx_live_new_masked");
  }

  @Test
  @DisplayName("状态切换与过期检测")
  void statusAndExpiration() {
    OpenApiKeyEntity entity =
        OpenApiKeyEntity.create(
            100L,
            "OA",
            "lx_live_1",
            "masked",
            null,
            null,
            LocalDateTime.now().minusMinutes(1),
            null,
            null);

    // 已过期
    assertThat(entity.isUsable()).isFalse();

    // 禁用
    OpenApiKeyEntity activeEntity =
        OpenApiKeyEntity.create(
            100L, "OA", "lx_live_2", "masked", null, null, null, null, null);
    assertThat(activeEntity.isUsable()).isTrue();
    activeEntity.disable();
    assertThat(activeEntity.getStatus()).isEqualTo(OpenApiKeyStatusEnum.DISABLED);
    assertThat(activeEntity.isUsable()).isFalse();
    activeEntity.enable();
    assertThat(activeEntity.isUsable()).isTrue();
  }

  @Test
  @DisplayName("记录调用使用情况")
  void recordUsage() {
    OpenApiKeyEntity entity =
        OpenApiKeyEntity.create(
            100L, "OA", "lx_live_1", "masked", null, null, null, null, null);

    assertThat(entity.getTotalCalls()).isZero();
    assertThat(entity.getLastUsedTime()).isNull();

    entity.recordUsage();

    assertThat(entity.getTotalCalls()).isEqualTo(1L);
    assertThat(entity.getLastUsedTime()).isNotNull();
  }
}
