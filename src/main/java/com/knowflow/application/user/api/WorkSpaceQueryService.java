package com.knowflow.application.user.api;

import com.knowflow.application.user.api.dto.WorkSpaceDTO;
import com.knowflow.application.user.model.request.WorkSpacePageRequest;
import com.knowflow.application.user.model.response.WorkSpaceResponse;
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
}
