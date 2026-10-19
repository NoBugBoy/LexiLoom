package io.lexiloom.application.document.spacelink.mapper;

import io.lexiloom.application.document.spacelink.model.entity.WorkSpaceSpaceLinkEntity;
import io.lexiloom.application.document.spacelink.model.response.SpaceLinkResponse;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** SpaceLink 实体与响应模型转换 MapStruct 映射器 */
@Mapper(componentModel = "spring")
public interface WorkSpaceSpaceLinkMapper {

  @Mapping(source = "link.id", target = "id")
  @Mapping(source = "link.sourceWorkspaceId", target = "sourceWorkspaceId")
  @Mapping(source = "sourceName", target = "sourceWorkspaceName")
  @Mapping(source = "link.targetWorkspaceId", target = "targetWorkspaceId")
  @Mapping(source = "targetName", target = "targetWorkspaceName")
  @Mapping(source = "link.status", target = "status")
  @Mapping(source = "link.rejectReason", target = "rejectReason")
  @Mapping(source = "documentIds", target = "documentIds")
  @Mapping(source = "link.createTime", target = "createTime")
  @Mapping(source = "link.updateTime", target = "updateTime")
  SpaceLinkResponse toResponse(
      WorkSpaceSpaceLinkEntity link,
      String sourceName,
      String targetName,
      List<Long> documentIds);
}
