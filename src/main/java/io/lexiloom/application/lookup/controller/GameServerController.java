package io.lexiloom.application.lookup.controller;

import io.lexiloom.application.lookup.api.GameServerService;
import io.lexiloom.application.lookup.model.response.GameServerOptionResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/server")
@RequiredArgsConstructor
public class GameServerController {
  private final GameServerService gameServerService;

  /**
   * 查询游戏服务器下拉选项。
   *
   * @return 游戏服务器选项列表（ID + 服务器名）
   */
  @GetMapping
  public List<GameServerOptionResponse> options() {
    return gameServerService.options();
  }
}
