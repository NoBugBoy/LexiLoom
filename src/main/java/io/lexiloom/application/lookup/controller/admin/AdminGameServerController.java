package io.lexiloom.application.lookup.controller.admin;

import io.lexiloom.application.lookup.api.GameServerService;
import io.lexiloom.application.lookup.model.request.GameServerPageRequest;
import io.lexiloom.application.lookup.model.request.GameServerRequest;
import io.lexiloom.application.lookup.model.response.GameSeverPageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/server")
@RequiredArgsConstructor
public class AdminGameServerController {
  private final GameServerService gameServerService;

  /**
   * 分页查询游戏服务器，支持按服务器名称模糊筛选。
   *
   * @param request 分页与筛选参数
   * @return 游戏服务器分页结果
   */
  @GetMapping("/page")
  public Page<GameSeverPageResponse> page(@Validated GameServerPageRequest request) {
    return gameServerService.page(request);
  }

  /**
   * 删除指定游戏服务器（逻辑删除，数据库保留记录）。
   *
   * @param gameServerId 游戏服务器 ID
   * @return 204 No Content
   */
  @DeleteMapping("/{gameServerId}")
  public ResponseEntity<Void> delete(@PathVariable Long gameServerId) {
    gameServerService.deleteById(gameServerId);
    return ResponseEntity.noContent().build();
  }

  /**
   * 新增游戏服务器，服务器名重复时返回业务错误。
   *
   * @param request 游戏服务器创建参数
   * @return 202 Accepted
   */
  @PostMapping
  public ResponseEntity<Void> add(@Validated @RequestBody GameServerRequest request) {
    gameServerService.create(request);
    return ResponseEntity.status(HttpStatus.ACCEPTED).build();
  }

  /**
   * 更新游戏服务器名称，目标服务器不存在时返回业务错误。
   *
   * @param gameServerId 游戏服务器 ID
   * @param request 游戏服务器更新参数
   * @return 204 No Content
   */
  @PutMapping("/{gameServerId}")
  public ResponseEntity<Void> update(
      @PathVariable Long gameServerId, @Validated @RequestBody GameServerRequest request) {
    gameServerService.update(gameServerId, request);
    return ResponseEntity.noContent().build();
  }
}
