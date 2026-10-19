package io.lexiloom.application.overview.controller;

import io.lexiloom.application.common.SecurityHolder;
import io.lexiloom.application.overview.model.response.WorkSpaceOverviewResponse;
import io.lexiloom.application.overview.service.WorkSpaceOverviewQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 团队工作空间数据总览仪表盘 API 控制器
 */
@RestController
@RequestMapping("/workspace")
@RequiredArgsConstructor
public class WorkSpaceOverviewController {

  private final WorkSpaceOverviewQueryService workSpaceOverviewQueryService;

  /** 获取团队/工作空间数据总览大屏指标 */
  @GetMapping("/{id}/overview")
  public WorkSpaceOverviewResponse getOverview(@PathVariable("id") Long id) {
    return workSpaceOverviewQueryService.getOverview(id, SecurityHolder.getUserId());
  }
}
