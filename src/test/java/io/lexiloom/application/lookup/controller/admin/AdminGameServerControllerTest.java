package io.lexiloom.application.lookup.controller.admin;

import static org.hamcrest.Matchers.notNullValue;

import io.lexiloom.application.BaseControllerIntegrationTest;
import io.lexiloom.application.lookup.model.request.GameServerRequest;
import io.lexiloom.application.lookup.repository.GameServerRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AdminGameServerControllerTest extends BaseControllerIntegrationTest {

  @Autowired private GameServerRepository gameServerRepository;

  @Test
  @DisplayName("未认证访问管理后台服务器接口返回401")
  void testUnauthorized() {
    givenAnonymous().when().get("/admin/server/page").then().statusCode(401);
  }

  @Test
  @DisplayName("普通用户访问管理后台服务器接口返回403")
  void testForbiddenForRegularUser() {
    givenUser(999L).when().get("/admin/server/page").then().statusCode(403);
  }

  @Test
  @DisplayName("管理员成功新增、分页查询、更新并删除游戏服务器")
  void testAdminGameServerLifecycle() {
    String serverName = "TestServer_" + UUID.randomUUID().toString().substring(0, 8);
    GameServerRequest createRequest = new GameServerRequest(serverName);

    // 1. 新增游戏服务器
    givenAdmin().body(createRequest).when().post("/admin/server").then().statusCode(202);

    // 2. 分页查询验证新增存在
    givenAdmin()
        .queryParam("gameServerName", serverName)
        .when()
        .get("/admin/server/page")
        .then()
        .statusCode(200)
        .body("content", notNullValue());

    var entity =
        gameServerRepository.findAll().stream()
            .filter(s -> serverName.equals(s.getServerName()))
            .findFirst()
            .orElseThrow();
    Long serverId = entity.getId();

    // 3. 更新游戏服务器
    String updatedName = serverName + "_updated";
    givenAdmin()
        .body(new GameServerRequest(updatedName))
        .when()
        .put("/admin/server/{gameServerId}", serverId)
        .then()
        .statusCode(204);

    // 4. 删除游戏服务器
    givenAdmin().when().delete("/admin/server/{gameServerId}", serverId).then().statusCode(204);
  }
}
