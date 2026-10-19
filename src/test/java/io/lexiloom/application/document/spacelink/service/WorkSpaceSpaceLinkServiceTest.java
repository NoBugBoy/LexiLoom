package io.lexiloom.application.document.spacelink.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lexiloom.application.common.SecurityHolder;
import io.lexiloom.application.document.model.entity.DocumentEntity;
import io.lexiloom.application.document.repository.DocumentRepository;
import io.lexiloom.application.document.spacelink.mapper.WorkSpaceSpaceLinkMapper;
import io.lexiloom.application.document.spacelink.model.entity.WorkSpaceSpaceLinkEntity;
import io.lexiloom.application.document.spacelink.model.response.AvailableSourcesResponse;
import io.lexiloom.application.document.spacelink.model.response.SpaceLinkResponse;
import io.lexiloom.application.document.spacelink.repository.WorkSpaceSpaceLinkDocRepository;
import io.lexiloom.application.document.spacelink.repository.WorkSpaceSpaceLinkRepository;
import io.lexiloom.application.document.spacelink.service.impl.WorkSpaceSpaceLinkServiceImpl;
import io.lexiloom.application.document.statemachine.DocumentStateEnum;
import io.lexiloom.application.enums.ApprovalTypeEnum;
import io.lexiloom.application.enums.SpaceLinkStatusEnum;
import io.lexiloom.application.enums.WorkSpaceAclEnum;
import io.lexiloom.application.enums.WorkSpaceRoleEnum;
import io.lexiloom.application.enums.WorkSpaceStatusEnum;
import io.lexiloom.application.exception.BusinessException;
import io.lexiloom.application.user.api.WorkSpaceMemberQueryService;
import io.lexiloom.application.user.api.WorkSpaceQueryService;
import io.lexiloom.application.user.api.dto.WorkSpaceDTO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkSpaceSpaceLinkService 横向关联与可用源业务测试")
class WorkSpaceSpaceLinkServiceTest {

  @Mock private WorkSpaceQueryService workSpaceQueryService;
  @Mock private WorkSpaceMemberQueryService workSpaceMemberQueryService;
  @Mock private WorkSpaceSpaceLinkRepository workSpaceSpaceLinkRepository;
  @Mock private WorkSpaceSpaceLinkDocRepository workSpaceSpaceLinkDocRepository;
  @Mock private DocumentRepository documentRepository;
  @Mock private io.lexiloom.application.approval.api.ApprovalApi approvalApi;
  @Mock private WorkSpaceSpaceLinkMapper workSpaceSpaceLinkMapper;

  @InjectMocks private WorkSpaceSpaceLinkServiceImpl spaceLinkService;

  private final Long userId = 9999L;

  @BeforeEach
  void setUp() {
    SecurityHolder.setAuthentication(userId);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  private WorkSpaceDTO createWorkspaceDTO(Long id, String name, WorkSpaceStatusEnum status) {
    return new WorkSpaceDTO(
        id,
        name,
        "desc",
        "code-" + id,
        null,
        userId,
        50,
        status,
        WorkSpaceAclEnum.PUBLIC,
        null,
        true,
        LocalDateTime.now());
  }

  @Test
  @DisplayName("申请 SpaceLink：不能关联当前工作区自身")
  void testApplySelfFails() {
    when(workSpaceMemberQueryService.hasRole(1L, userId, WorkSpaceRoleEnum.ADMIN)).thenReturn(true);

    assertThatThrownBy(() -> spaceLinkService.apply(1L, 1L, List.of(101L), "申请关联"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不能关联当前工作区自身");
  }

  @Test
  @DisplayName("申请 SpaceLink：非管理员用户拒绝申请")
  void testApplyForbiddenForNonAdmin() {
    when(workSpaceMemberQueryService.hasRole(1L, userId, WorkSpaceRoleEnum.ADMIN)).thenReturn(false);

    assertThatThrownBy(() -> spaceLinkService.apply(1L, 2L, List.of(101L), "申请关联"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("仅团队所有者或管理员有权执行此操作");
  }

  @Test
  @DisplayName("申请 SpaceLink：目标工作区不存在时抛出异常")
  void testApplyTargetNotFound() {
    when(workSpaceMemberQueryService.hasRole(1L, userId, WorkSpaceRoleEnum.ADMIN)).thenReturn(true);
    when(workSpaceQueryService.getById(2L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> spaceLinkService.apply(1L, 2L, List.of(101L), "申请关联"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("目标工作空间不存在");
  }

  @Test
  @DisplayName("申请 SpaceLink：目标工作区已解散不可申请")
  void testApplyTargetDisbanded() {
    when(workSpaceMemberQueryService.hasRole(1L, userId, WorkSpaceRoleEnum.ADMIN)).thenReturn(true);
    WorkSpaceDTO target = createWorkspaceDTO(2L, "目标空间", WorkSpaceStatusEnum.DISBANDED);
    when(workSpaceQueryService.getById(2L)).thenReturn(Optional.of(target));

    assertThatThrownBy(() -> spaceLinkService.apply(1L, 2L, List.of(101L), "申请关联"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("已被禁用或归档");
  }

  @Test
  @DisplayName("申请 SpaceLink：正常发起首次申请 (PENDING)")
  void testApplySuccess() {
    when(workSpaceMemberQueryService.hasRole(1L, userId, WorkSpaceRoleEnum.ADMIN)).thenReturn(true);

    DocumentEntity doc =
        DocumentEntity.createFromImport(2L, 1L, "公开文档", "摘要", "内容", WorkSpaceAclEnum.PUBLIC);
    ReflectionTestUtils.setField(doc, "id", 101L);
    when(documentRepository.findLinkableDocuments(eq(2L), eq(DocumentStateEnum.APPROVED), any(), any()))
        .thenReturn(List.of(doc));

    WorkSpaceDTO target = createWorkspaceDTO(2L, "目标空间", WorkSpaceStatusEnum.NORMAL);
    when(workSpaceQueryService.getById(2L)).thenReturn(Optional.of(target));
    when(workSpaceSpaceLinkRepository.findBySourceWorkspaceIdAndTargetWorkspaceId(1L, 2L))
        .thenReturn(Optional.empty());

    when(workSpaceSpaceLinkRepository.save(any(WorkSpaceSpaceLinkEntity.class)))
        .thenAnswer(
            invocation -> {
              WorkSpaceSpaceLinkEntity entity = invocation.getArgument(0);
              ReflectionTestUtils.setField(entity, "id", 1001L);
              return entity;
            });

    Long linkId = spaceLinkService.apply(1L, 2L, List.of(101L), "申请关联");

    assertThat(linkId).isEqualTo(1001L);
    verify(workSpaceSpaceLinkRepository).save(any(WorkSpaceSpaceLinkEntity.class));
    verify(workSpaceSpaceLinkDocRepository).saveAll(any());
    verify(approvalApi)
        .submitSpaceLinkApproval(
            eq(1001L),
            eq(1L),
            eq(2L),
            eq(userId),
            any(),
            eq("申请关联"),
            eq(List.of(101L)));
  }

  @Test
  @DisplayName("申请 SpaceLink：已被 REJECTED 的记录重新申请 (reapply)")
  void testReapplyAfterRejected() {
    when(workSpaceMemberQueryService.hasRole(1L, userId, WorkSpaceRoleEnum.ADMIN)).thenReturn(true);

    DocumentEntity doc =
        DocumentEntity.createFromImport(2L, 1L, "公开文档", "摘要", "内容", WorkSpaceAclEnum.PUBLIC);
    ReflectionTestUtils.setField(doc, "id", 101L);
    when(documentRepository.findLinkableDocuments(eq(2L), eq(DocumentStateEnum.APPROVED), any(), any()))
        .thenReturn(List.of(doc));

    WorkSpaceDTO target = createWorkspaceDTO(2L, "目标空间", WorkSpaceStatusEnum.NORMAL);
    when(workSpaceQueryService.getById(2L)).thenReturn(Optional.of(target));

    WorkSpaceSpaceLinkEntity existing = WorkSpaceSpaceLinkEntity.create(1L, 2L);
    existing.reject("之前拒绝");
    ReflectionTestUtils.setField(existing, "id", 1002L);

    when(workSpaceSpaceLinkRepository.findBySourceWorkspaceIdAndTargetWorkspaceId(1L, 2L))
        .thenReturn(Optional.of(existing));
    when(workSpaceSpaceLinkRepository.save(existing)).thenReturn(existing);

    Long linkId = spaceLinkService.apply(1L, 2L, List.of(101L), "重新申请");

    assertThat(linkId).isEqualTo(1002L);
    assertThat(existing.getStatus()).isEqualTo(SpaceLinkStatusEnum.PENDING);
    assertThat(existing.getRejectReason()).isNull();
    verify(workSpaceSpaceLinkDocRepository).deleteByLinkId(1002L);
    verify(workSpaceSpaceLinkDocRepository).saveAll(any());
  }

  @Test
  @DisplayName("审批 SpaceLink：目标管理员审批通过 (APPROVE)")
  void testApproveSuccess() {
    when(workSpaceMemberQueryService.hasRole(2L, userId, WorkSpaceRoleEnum.ADMIN)).thenReturn(true);

    WorkSpaceSpaceLinkEntity link = WorkSpaceSpaceLinkEntity.create(1L, 2L);
    ReflectionTestUtils.setField(link, "id", 1003L);
    when(workSpaceSpaceLinkRepository.findById(1003L)).thenReturn(Optional.of(link));

    spaceLinkService.approve(2L, 1003L, "同意关联");

    assertThat(link.getStatus()).isEqualTo(SpaceLinkStatusEnum.APPROVED);
    verify(workSpaceSpaceLinkRepository).save(link);
    verify(approvalApi)
        .approve(
            eq(ApprovalTypeEnum.SPACE_LINK),
            eq(1003L),
            eq(userId),
            any(),
            eq("同意关联"));
  }

  @Test
  @DisplayName("审批 SpaceLink：越权审批其他空间的关联记录拒绝")
  void testApproveOtherTargetFails() {
    when(workSpaceMemberQueryService.hasRole(3L, userId, WorkSpaceRoleEnum.ADMIN)).thenReturn(true);

    WorkSpaceSpaceLinkEntity link = WorkSpaceSpaceLinkEntity.create(1L, 2L);
    ReflectionTestUtils.setField(link, "id", 1004L);
    when(workSpaceSpaceLinkRepository.findById(1004L)).thenReturn(Optional.of(link));

    assertThatThrownBy(() -> spaceLinkService.approve(3L, 1004L, "同意"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("无权审批其他工作区的关联申请");
  }

  @Test
  @DisplayName("审批 SpaceLink：目标管理员驳回 (REJECT)")
  void testRejectSuccess() {
    when(workSpaceMemberQueryService.hasRole(2L, userId, WorkSpaceRoleEnum.ADMIN)).thenReturn(true);

    WorkSpaceSpaceLinkEntity link = WorkSpaceSpaceLinkEntity.create(1L, 2L);
    ReflectionTestUtils.setField(link, "id", 1005L);
    when(workSpaceSpaceLinkRepository.findById(1005L)).thenReturn(Optional.of(link));

    spaceLinkService.reject(2L, 1005L, "暂无合作计划");

    assertThat(link.getStatus()).isEqualTo(SpaceLinkStatusEnum.REJECTED);
    assertThat(link.getRejectReason()).isEqualTo("暂无合作计划");
    verify(workSpaceSpaceLinkRepository).save(link);
    verify(approvalApi)
        .reject(
            eq(ApprovalTypeEnum.SPACE_LINK),
            eq(1005L),
            eq(userId),
            any(),
            eq("暂无合作计划"));
  }

  @Test
  @DisplayName("查询可用检索源拓扑：组装主空间、直系子空间与审批通过关联")
  void testGetAvailableSourcesSuccess() {
    WorkSpaceDTO primary = createWorkspaceDTO(1L, "A部门", WorkSpaceStatusEnum.NORMAL);
    when(workSpaceQueryService.getById(1L)).thenReturn(Optional.of(primary));
    when(workSpaceQueryService.listChildWorkspaceIds(1L)).thenReturn(List.of(2L));

    WorkSpaceSpaceLinkEntity link = WorkSpaceSpaceLinkEntity.create(1L, 3L);
    link.approve();
    when(workSpaceSpaceLinkRepository.findBySourceWorkspaceIdAndStatus(1L, SpaceLinkStatusEnum.APPROVED))
        .thenReturn(List.of(link));
    when(workSpaceQueryService.getWorkspaceNames(List.of(2L)))
        .thenReturn(Map.of(2L, "B项目"));
    when(workSpaceQueryService.getWorkspaceNames(List.of(3L)))
        .thenReturn(Map.of(3L, "C项目"));

    AvailableSourcesResponse response = spaceLinkService.getAvailableSources(1L);

    assertThat(response.primary().workspaceId()).isEqualTo(1L);
    assertThat(response.primary().workspaceName()).isEqualTo("A部门");

    assertThat(response.children()).hasSize(1);
    assertThat(response.children().getFirst().workspaceId()).isEqualTo(2L);
    assertThat(response.children().getFirst().workspaceName()).isEqualTo("B项目");

    assertThat(response.explicitLinks()).hasSize(1);
    assertThat(response.explicitLinks().getFirst().workspaceId()).isEqualTo(3L);
    assertThat(response.explicitLinks().getFirst().workspaceName()).isEqualTo("C项目");
    assertThat(response.explicitLinks().getFirst().status()).isEqualTo("APPROVED");
  }

  @Test
  @DisplayName("查询我发起的 SpaceLink 与待我审批的 SpaceLink 列表")
  void testListLinks() {
    WorkSpaceSpaceLinkEntity link = WorkSpaceSpaceLinkEntity.create(1L, 2L);
    ReflectionTestUtils.setField(link, "id", 1006L);

    when(workSpaceSpaceLinkRepository.findBySourceWorkspaceId(1L)).thenReturn(List.of(link));
    when(workSpaceQueryService.getWorkspaceNames(any()))
        .thenReturn(Map.of(1L, "源空间", 2L, "目标空间"));
    when(workSpaceSpaceLinkDocRepository.findByLinkIdIn(any())).thenReturn(List.of());

    SpaceLinkResponse mockResponse =
        new SpaceLinkResponse(
            1006L,
            1L,
            "源空间",
            2L,
            "目标空间",
            SpaceLinkStatusEnum.PENDING,
            null,
            List.of(),
            LocalDateTime.now(),
            LocalDateTime.now());
    when(workSpaceSpaceLinkMapper.toResponse(eq(link), eq("源空间"), eq("目标空间"), any()))
        .thenReturn(mockResponse);

    List<SpaceLinkResponse> applied = spaceLinkService.listMyAppliedLinks(1L);
    assertThat(applied).hasSize(1);
    assertThat(applied.getFirst().id()).isEqualTo(1006L);
  }
}
