package io.lexiloom.application.user.api;

import io.lexiloom.application.user.api.dto.WorkSpaceDTO;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 团队查询服务（跨模块公开，仅暴露 DTO） */
public interface WorkSpaceQueryService {

  /** 根据团队ID获取团队信息 */
  Optional<WorkSpaceDTO> getById(Long id);

  /** 根据团队code获取团队信息 */
  Optional<WorkSpaceDTO> getByCode(String code);

  /** 查询允许父级关联的直系子工作空间 ID 列表 */
  List<Long> listChildWorkspaceIds(Long parentWorkspaceId);

  /** 批量获取工作空间名称映射 (Key: workspaceId, Value: name) */
  Map<Long, String> getWorkspaceNames(Collection<Long> workspaceIds);

  /** 查询系统中所有工作空间公开列表 (跨模块安全) */
  List<WorkSpaceDTO> listAllWorkspaces();

  /** 查询指定用户加入的所有工作空间公开列表 (跨模块安全) */
  List<WorkSpaceDTO> listMyWorkspaceDTOs(Long userId);
}
