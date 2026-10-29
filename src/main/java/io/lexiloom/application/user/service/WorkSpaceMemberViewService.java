package io.lexiloom.application.user.service;

import io.lexiloom.application.user.model.request.WorkSpaceMemberPageRequest;
import io.lexiloom.application.user.model.response.WorkSpaceMemberResponse;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;

/** 团队成员视图查询服务（user 模块内部，供 Controller 返回 Response 使用） */
public interface WorkSpaceMemberViewService {

  /** 查询指定团队中的成员响应详情 */
  Optional<WorkSpaceMemberResponse> getMemberResponse(Long workSpaceId, Long userId);

  /** 分页查询团队成员 */
  Page<WorkSpaceMemberResponse> pageMembers(Long workSpaceId, WorkSpaceMemberPageRequest request);

  /** 查询团队所有成员 */
  List<WorkSpaceMemberResponse> listMembers(Long workSpaceId);
}
