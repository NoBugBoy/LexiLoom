package io.lexiloom.application.user.api;

import io.lexiloom.application.user.api.dto.WorkSpaceDTO;
import io.lexiloom.application.user.model.request.WorkSpacePageRequest;
import io.lexiloom.application.user.model.response.WorkSpaceResponse;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;

/** 团队查询服务（跨模块公开） */
public interface WorkSpaceQueryService {

  /** 根据团队ID获取团队信息 */
  Optional<WorkSpaceDTO> getById(Long id);

  /** 根据团队code获取团队信息 */
  Optional<WorkSpaceDTO> getByCode(String code);

  /** 分页查询团队列表 */
  Page<WorkSpaceResponse> page(WorkSpacePageRequest request);

  /** 查询指定用户加入的所有团队 */
  List<WorkSpaceResponse> listMyWorkSpaces(Long userId);

  /** 查询允许父级关联的直系子工作空间 ID 列表 */
  List<Long> listChildWorkspaceIds(Long parentWorkspaceId);

  /** 批量获取工作空间名称映射 (Key: workspaceId, Value: name) */
  java.util.Map<Long, String> getWorkspaceNames(java.util.Collection<Long> workspaceIds);
}
