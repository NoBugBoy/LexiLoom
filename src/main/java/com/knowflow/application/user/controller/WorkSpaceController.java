package com.knowflow.application.user.controller;

import com.knowflow.application.common.SecurityHolder;
import com.knowflow.application.exception.BusinessException;
import com.knowflow.application.user.api.WorkSpaceQueryService;
import com.knowflow.application.user.mapper.WorkSpaceMapper;
import com.knowflow.application.user.model.request.WorkSpaceCreateRequest;
import com.knowflow.application.user.model.request.WorkSpacePageRequest;
import com.knowflow.application.user.model.request.WorkSpaceUpdateRequest;
import com.knowflow.application.user.model.response.WorkSpaceResponse;
import com.knowflow.application.user.service.WorkSpaceCommandService;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 团队控制器 */
@RestController
@RequestMapping("/workspace")
@RequiredArgsConstructor
public class WorkSpaceController {

  private final WorkSpaceCommandService workSpaceCommandService;
  private final WorkSpaceQueryService workSpaceQueryService;
  private final WorkSpaceMapper workSpaceMapper;

  public record UpdateMaxMembersRequest(@NotNull(message = "配额人数上限不能为空") Integer maxMembers) {}

  public record TransferOwnerRequest(@NotNull(message = "目标新所有者ID不能为空") Long newOwnerId) {}

  /** 创建团队 */
  @PostMapping
  public Long create(@Validated @RequestBody WorkSpaceCreateRequest request) {
    return workSpaceCommandService.create(request);
  }

  /** 获取团队详情 */
  @GetMapping("/{id}")
  public WorkSpaceResponse getById(@PathVariable("id") Long id) {
    return workSpaceQueryService
        .getById(id)
        .map(workSpaceMapper::toResponse)
        .orElseThrow(() -> BusinessException.badRequest("团队不存在: " + id));
  }

  /** 根据英文唯一标识(code)获取团队详情 */
  @GetMapping("/code/{code}")
  public WorkSpaceResponse getByCode(@PathVariable("code") String code) {
    return workSpaceQueryService
        .getByCode(code)
        .map(workSpaceMapper::toResponse)
        .orElseThrow(() -> BusinessException.badRequest("团队不存在: " + code));
  }

  /** 分页查询团队列表 */
  @GetMapping("/page")
  public Page<WorkSpaceResponse> page(WorkSpacePageRequest request) {
    return workSpaceQueryService.page(request);
  }

  /** 获取当前登录用户参与的所有团队 */
  @GetMapping("/my")
  public List<WorkSpaceResponse> listMy() {
    return workSpaceQueryService.listMyWorkSpaces(SecurityHolder.getUserId());
  }

  /** 修改团队基本信息 */
  @PutMapping("/{id}")
  public void update(
      @PathVariable("id") Long id, @Validated @RequestBody WorkSpaceUpdateRequest request) {
    workSpaceCommandService.update(id, request);
  }

  /** 调整团队成员上限 */
  @PutMapping("/{id}/max-members")
  public void updateMaxMembers(
      @PathVariable("id") Long id, @Validated @RequestBody UpdateMaxMembersRequest request) {
    workSpaceCommandService.updateMaxMembers(id, request.maxMembers());
  }

  /** 冻结团队 */
  @PutMapping("/{id}/freeze")
  public void freeze(@PathVariable("id") Long id) {
    workSpaceCommandService.freeze(id);
  }

  /** 激活/恢复团队 */
  @PutMapping("/{id}/activate")
  public void activate(@PathVariable("id") Long id) {
    workSpaceCommandService.activate(id);
  }

  /** 解散团队 */
  @PutMapping("/{id}/disband")
  public void disband(@PathVariable("id") Long id) {
    workSpaceCommandService.disband(id);
  }

  /** 转让团队所有权 */
  @PutMapping("/{id}/transfer-owner")
  public void transferOwner(
      @PathVariable("id") Long id, @Validated @RequestBody TransferOwnerRequest request) {
    workSpaceCommandService.transferOwnership(id, request.newOwnerId());
  }

  /** 删除团队 */
  @DeleteMapping("/{id}")
  public void delete(@PathVariable("id") Long id) {
    workSpaceCommandService.delete(id);
  }
}
