package io.lexiloom.application.lookup.controller.admin;

import static org.hamcrest.Matchers.notNullValue;

import io.lexiloom.application.BaseControllerIntegrationTest;
import io.lexiloom.application.lookup.model.request.SectRequest;
import io.lexiloom.application.lookup.repository.SectRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AdminSectControllerTest extends BaseControllerIntegrationTest {

  @Autowired private SectRepository sectRepository;

  @Test
  @DisplayName("未认证访问管理后台门派接口返回401")
  void testUnauthorized() {
    givenAnonymous().when().get("/admin/sect/page").then().log().all().statusCode(401);
  }

  @Test
  @DisplayName("普通用户访问管理后台门派接口返回403")
  void testForbiddenForRegularUser() {
    givenUser(999L).when().get("/admin/sect/page").then().log().ifValidationFails().statusCode(403);
  }

  @Test
  @DisplayName("管理员成功新增、分页查询、更新并删除门派")
  void testAdminSectLifecycle() {
    String sectName = "TestSect_" + UUID.randomUUID().toString().substring(0, 8);
    SectRequest createRequest = new SectRequest(sectName);

    // 1. 新增门派
    givenAdmin()
        .body(createRequest)
        .when()
        .post("/admin/sect")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(202);

    // 2. 分页查询验证新增存在
    givenAdmin()
        .queryParam("sectName", sectName)
        .when()
        .get("/admin/sect/page")
        .then()
        .statusCode(200)
        .body("content", notNullValue());

    var entity =
        sectRepository.findAll().stream()
            .filter(s -> sectName.equals(s.getSectName()))
            .findFirst()
            .orElseThrow();
    Long sectId = entity.getId();

    // 3. 更新门派
    String updatedName = sectName + "_updated";
    givenAdmin()
        .body(new SectRequest(updatedName))
        .when()
        .put("/admin/sect/{sectId}", sectId)
        .then()
        .statusCode(204);

    // 4. 删除门派
    givenAdmin().when().delete("/admin/sect/{sectId}", sectId).then().statusCode(204);
  }
}
