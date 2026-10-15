package io.lexiloom.application.lookup.controller.admin;

import io.lexiloom.application.lookup.api.SectService;
import io.lexiloom.application.lookup.model.request.SectPageRequest;
import io.lexiloom.application.lookup.model.request.SectRequest;
import io.lexiloom.application.lookup.model.response.SectPageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/sect")
@RequiredArgsConstructor
public class AdminSectController {
  private final SectService sectService;

  /**
   * 分页查询门派，支持按门派名称模糊筛选。
   *
   * @param request 分页与筛选参数
   * @return 门派分页结果
   */
  @GetMapping("/page")
  public Page<SectPageResponse> page(@Validated SectPageRequest request) {
    return sectService.page(request);
  }

  /**
   * 删除指定门派（逻辑删除，数据库保留记录）。
   *
   * @param sectId 门派 ID
   * @return 204 No Content
   */
  @DeleteMapping("/{sectId}")
  public ResponseEntity<Void> delete(@PathVariable Long sectId) {
    sectService.deleteById(sectId);
    return ResponseEntity.noContent().build();
  }

  /**
   * 新增门派，门派名重复时返回业务错误。
   *
   * @param request 门派创建参数
   * @return 202 Accepted
   */
  @PostMapping
  public ResponseEntity<Void> add(@Validated @RequestBody SectRequest request) {
    sectService.createSect(request);
    return ResponseEntity.status(HttpStatus.ACCEPTED).build();
  }

  /**
   * 更新门派名称，目标门派不存在时返回业务错误。
   *
   * @param sectId 门派 ID
   * @param request 门派更新参数
   * @return 204 No Content
   */
  @PutMapping("/{sectId}")
  public ResponseEntity<Void> update(
      @PathVariable Long sectId, @Validated @RequestBody SectRequest request) {
    sectService.update(sectId, request);
    return ResponseEntity.noContent().build();
  }
}
