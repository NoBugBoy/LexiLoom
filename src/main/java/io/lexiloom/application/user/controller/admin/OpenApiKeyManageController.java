package io.lexiloom.application.user.controller.admin;

import io.lexiloom.application.common.SecurityHolder;
import io.lexiloom.application.enums.OpenApiKeyStatusEnum;
import io.lexiloom.application.user.model.request.CreateOpenApiKeyRequest;
import io.lexiloom.application.user.model.request.UpdateOpenApiKeyRequest;
import io.lexiloom.application.user.model.response.CreateOpenApiKeyResponse;
import io.lexiloom.application.user.model.vo.OpenApiKeyVO;
import io.lexiloom.application.user.service.OpenApiKeyCommandService;
import io.lexiloom.application.user.service.OpenApiKeyQueryService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OpenAPI 凭据管理 REST 控制器
 *
 * <p>支持单用户创建并维护多个 OpenAPI Key，精细化配置 Scopes、允许访问的工作空间白名单及 BYOK 大模型参数。
 */
@Validated
@RestController
@RequestMapping({"/admin/openapi/keys", "/api/user/openapi/keys"})
@RequiredArgsConstructor
public class OpenApiKeyManageController {

  private final OpenApiKeyCommandService openApiKeyCommandService;
  private final OpenApiKeyQueryService openApiKeyQueryService;

  /**
   * 查询当前用户拥有的全部 OpenAPI Key 列表
   */
  @GetMapping
  public List<OpenApiKeyVO> listMyKeys() {
    Long userId = SecurityHolder.getUserId();
    return openApiKeyQueryService.listMyKeys(userId);
  }

  /**
   * 获取指定凭据详情
   */
  @GetMapping("/{id}")
  public OpenApiKeyVO getKeyDetail(@PathVariable("id") Long id) {
    Long userId = SecurityHolder.getUserId();
    return openApiKeyQueryService.getKeyDetail(userId, id);
  }

  /**
   * 创建新的 OpenAPI Key (首次返回明文 Key)
   */
  @PostMapping
  public ResponseEntity<CreateOpenApiKeyResponse> createKey(
      @Valid @RequestBody CreateOpenApiKeyRequest request) {
    Long userId = SecurityHolder.getUserId();
    CreateOpenApiKeyResponse response = openApiKeyCommandService.createKey(userId, request);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  /**
   * 修改 OpenAPI Key 配置 (名称、权限范围、空间白名单、过期时间、BYOK等)
   */
  @PutMapping("/{id}")
  public OpenApiKeyVO updateKey(
      @PathVariable("id") Long id, @Valid @RequestBody UpdateOpenApiKeyRequest request) {
    Long userId = SecurityHolder.getUserId();
    return openApiKeyCommandService.updateKey(userId, id, request);
  }

  /**
   * 重新生成并轮转 Key (原 Key 立即失效，返回全新明文 Key)
   */
  @PostMapping("/{id}/rotate")
  public CreateOpenApiKeyResponse rotateKey(@PathVariable("id") Long id) {
    Long userId = SecurityHolder.getUserId();
    return openApiKeyCommandService.rotateKey(userId, id);
  }

  /**
   * 切换 OpenAPI Key 启用/禁用状态
   */
  @PutMapping("/{id}/status")
  public ResponseEntity<Void> toggleStatus(
      @PathVariable("id") Long id, @RequestParam("status") OpenApiKeyStatusEnum status) {
    Long userId = SecurityHolder.getUserId();
    openApiKeyCommandService.toggleKeyStatus(userId, id, status);
    return ResponseEntity.noContent().build();
  }

  /**
   * 删除指定的 OpenAPI Key
   */
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> deleteKey(@PathVariable("id") Long id) {
    Long userId = SecurityHolder.getUserId();
    openApiKeyCommandService.deleteKey(userId, id);
    return ResponseEntity.noContent().build();
  }
}
