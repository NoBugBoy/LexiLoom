package io.lexiloom.application.document.spacelink.model.response;

import java.util.Collections;
import java.util.List;

/**
 * 工作区可用检索源响应 (不可变 record)
 *
 * <p>包含当前主空间、允许自动向下聚合的直系子空间，以及已审批生效的横向关联空间。
 */
public record AvailableSourcesResponse(
    WorkspaceSourceItem primary,
    List<WorkspaceSourceItem> children,
    List<SpaceLinkSourceItem> explicitLinks) {

  public AvailableSourcesResponse {
    children = children == null ? Collections.emptyList() : List.copyOf(children);
    explicitLinks = explicitLinks == null ? Collections.emptyList() : List.copyOf(explicitLinks);
  }

  public record WorkspaceSourceItem(Long workspaceId, String workspaceName) {}

  public record SpaceLinkSourceItem(Long workspaceId, String workspaceName, String status) {}
}
