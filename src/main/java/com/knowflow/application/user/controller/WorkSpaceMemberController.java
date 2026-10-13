package com.knowflow.application.user.controller;

import com.knowflow.application.exception.BusinessException;
import com.knowflow.application.user.api.WorkSpaceMemberQueryService;
import com.knowflow.application.user.mapper.WorkSpaceMemberMapper;
import com.knowflow.application.user.model.request.WorkSpaceMemberAddRequest;
import com.knowflow.application.user.model.request.WorkSpaceMemberPageRequest;
import com.knowflow.application.user.model.request.WorkSpaceMemberRoleUpdateRequest;
import com.knowflow.application.user.model.response.WorkSpaceMemberResponse;
import com.knowflow.application.user.service.WorkSpaceMemberCommandService;
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

/** 团队成员控制器 */
@RestController
@RequestMapping("/workspace/{workSpaceId}/members")
@RequiredArgsConstructor
public class WorkSpaceMemberController {

  private final WorkSpaceMemberCommandService workSpaceMemberCommandService;
  private final WorkSpaceMemberQueryService workSpaceMemberQueryService;
  private final WorkSpaceMemberMapper workSpaceMemberMapper;

  /** 添加团队成员 */
  @PostMapping
  public Long addMember(
      @PathVariable("workSpaceId") Long workSpaceId,
      @Validated @RequestBody WorkSpaceMemberAddRequest request) {
    return workSpaceMemberCommandService.addMember(workSpaceId, request);
  }

  /** 查询团队成员列表 */
  @GetMapping
  public List<WorkSpaceMemberResponse> listMembers(@PathVariable("workSpaceId") Long workSpaceId) {
    return workSpaceMemberQueryService.listMembers(workSpaceId);
  }

  /** 分页查询团队成员 */
  @GetMapping("/page")
  public Page<WorkSpaceMemberResponse> pageMembers(
      @PathVariable("workSpaceId") Long workSpaceId, WorkSpaceMemberPageRequest request) {
    return workSpaceMemberQueryService.pageMembers(workSpaceId, request);
  }

  /** 获取指定成员详情 */
  @GetMapping("/{userId}")
  public WorkSpaceMemberResponse getMember(
      @PathVariable("workSpaceId") Long workSpaceId, @PathVariable("userId") Long userId) {
    return workSpaceMemberQueryService
        .getMember(workSpaceId, userId)
        .map(workSpaceMemberMapper::toResponse)
        .orElseThrow(() -> BusinessException.badRequest("团队成员不存在"));
  }

  /** 修改成员角色 */
  @PutMapping("/{userId}/role")
  public void updateRole(
      @PathVariable("workSpaceId") Long workSpaceId,
      @PathVariable("userId") Long userId,
      @Validated @RequestBody WorkSpaceMemberRoleUpdateRequest request) {
    workSpaceMemberCommandService.updateMemberRole(workSpaceId, userId, request.role());
  }

  /** 移除团队成员 */
  @DeleteMapping("/{userId}")
  public void removeMember(
      @PathVariable("workSpaceId") Long workSpaceId, @PathVariable("userId") Long userId) {
    workSpaceMemberCommandService.removeMember(workSpaceId, userId);
  }

  /** 当前用户主动退出团队 */
  @PostMapping("/leave")
  public void leave(@PathVariable("workSpaceId") Long workSpaceId) {
    workSpaceMemberCommandService.leaveWorkSpace(workSpaceId);
  }
}
