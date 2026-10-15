package io.lexiloom.application.lookup.api;

import io.lexiloom.application.lookup.api.dto.ServerDTO;
import io.lexiloom.application.lookup.model.request.GameServerPageRequest;
import io.lexiloom.application.lookup.model.request.GameServerRequest;
import io.lexiloom.application.lookup.model.response.GameServerOptionResponse;
import io.lexiloom.application.lookup.model.response.GameSeverPageResponse;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;

public interface GameServerService {
  /**
   * 分页查询游戏服务器，支持按服务器名称模糊筛选。
   *
   * @param request 分页与筛选参数
   * @return 游戏服务器分页结果
   */
  Page<GameSeverPageResponse> page(GameServerPageRequest request);

  /**
   * 按 ID 逻辑删除游戏服务器。
   *
   * @param gameServerId 游戏服务器 ID
   */
  void deleteById(Long gameServerId);

  /**
   * 创建游戏服务器，同名服务器已存在时抛出业务异常。
   *
   * @param request 游戏服务器创建参数
   */
  void create(GameServerRequest request);

  /**
   * 更新游戏服务器名称，目标服务器不存在时抛出业务异常。
   *
   * @param gameServerId 游戏服务器 ID
   * @param request 游戏服务器更新参数
   */
  void update(Long gameServerId, GameServerRequest request);

  /**
   * 查询游戏服务器下拉选项，按创建时间倒序返回。
   *
   * @return 游戏服务器选项列表
   */
  List<GameServerOptionResponse> options();

  Optional<ServerDTO> getById(@NotNull(message = "区服不能为空") Long serverId);
}
