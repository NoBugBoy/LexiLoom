package io.lexiloom.application.user.service;

import io.lexiloom.application.user.model.request.WorkSpacePageRequest;
import io.lexiloom.application.user.model.response.WorkSpaceResponse;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;

/** 团队视图查询服务（user 模块内部，供 Controller 返回 Response 使用） */
public interface WorkSpaceViewService {

  /** 根据团队ID获取团队响应详情 */
  Optional<WorkSpaceResponse> getResponseById(Long id);

  /** 根据团队code获取团队响应详情 */
  Optional<WorkSpaceResponse> getResponseByCode(String code);

  /** 分页查询团队列表 */
  Page<WorkSpaceResponse> page(WorkSpacePageRequest request);

  /** 查询指定用户加入的所有团队 */
  List<WorkSpaceResponse> listMyWorkSpaces(Long userId);
}
