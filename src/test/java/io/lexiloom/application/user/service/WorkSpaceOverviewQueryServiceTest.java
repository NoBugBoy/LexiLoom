package io.lexiloom.application.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lexiloom.application.ai.config.AiProperties;
import io.lexiloom.application.ai.repository.DocChunkRepository;
import io.lexiloom.application.document.api.DocumentQueryApi;
import io.lexiloom.application.document.api.dto.WorkspaceDocStatsDTO;
import io.lexiloom.application.enums.WorkSpaceAclEnum;
import io.lexiloom.application.enums.WorkSpaceRoleEnum;
import io.lexiloom.application.exception.BusinessException;
import io.lexiloom.application.user.api.WorkSpaceMemberQueryService;
import io.lexiloom.application.user.model.entity.UserEntity;
import io.lexiloom.application.user.model.entity.WorkSpaceEntity;
import io.lexiloom.application.user.model.response.WorkSpaceOverviewResponse;
import io.lexiloom.application.user.repository.UserRepository;
import io.lexiloom.application.user.repository.WorkSpaceMemberRepository;
import io.lexiloom.application.user.repository.WorkSpaceRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class WorkSpaceOverviewQueryServiceTest {

  @Mock private WorkSpaceRepository workSpaceRepository;
  @Mock private WorkSpaceMemberRepository workSpaceMemberRepository;
  @Mock private WorkSpaceMemberQueryService workSpaceMemberQueryService;
  @Mock private UserRepository userRepository;
  @Mock private DocumentQueryApi documentQueryApi;
  @Mock private DocChunkRepository docChunkRepository;
  @Mock private AiProperties aiProperties;
  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ValueOperations<String, String> valueOperations;
  @Mock private ObjectMapper objectMapper;

  private WorkSpaceOverviewQueryService overviewQueryService;

  @BeforeEach
  void setUp() {
    overviewQueryService =
        new WorkSpaceOverviewQueryService(
            workSpaceRepository,
            workSpaceMemberRepository,
            workSpaceMemberQueryService,
            userRepository,
            documentQueryApi,
            docChunkRepository,
            aiProperties,
            redisTemplate,
            objectMapper);
  }

  @Test
  @DisplayName("空间 ID 为空时抛出 BadRequest")
  void testGetOverview_NullWorkspaceId_ThrowsException() {
    assertThatThrownBy(() -> overviewQueryService.getOverview(null, 1L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("团队空间 ID 不能为空");
  }

  @Test
  @DisplayName("空间不存在时抛出 BadRequest")
  void testGetOverview_WorkspaceNotFound_ThrowsException() {
    when(workSpaceRepository.findById(999L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> overviewQueryService.getOverview(999L, 1L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("团队不存在: 999");
  }

  @Test
  @DisplayName("私有空间且非成员访问时抛出 Forbidden")
  void testGetOverview_PrivateWorkspace_NonMember_ThrowsForbidden() {
    WorkSpaceEntity ws =
        WorkSpaceEntity.create(
            "机密空间", "secret-ws", "内部机密", null, 100L, 50, WorkSpaceAclEnum.PRIVATE);
    ReflectionTestUtils.setField(ws, "id", 10L);

    when(workSpaceRepository.findById(10L)).thenReturn(Optional.of(ws));
    when(workSpaceMemberQueryService.isMember(10L, 200L)).thenReturn(false);

    assertThatThrownBy(() -> overviewQueryService.getOverview(10L, 200L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("您不是该团队成员，无权访问数据总览");
  }

  @Test
  @DisplayName("Redis 缓存命中时直接反序列化返回，不打数据库")
  void testGetOverview_CacheHit_ReturnsCachedResponse() throws Exception {
    WorkSpaceEntity ws =
        WorkSpaceEntity.create("测试空间", "test-ws", "测试描述", null, 1L, 100, WorkSpaceAclEnum.PUBLIC);
    ReflectionTestUtils.setField(ws, "id", 1L);

    when(workSpaceRepository.findById(1L)).thenReturn(Optional.of(ws));
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get("ll:ws:overview:1")).thenReturn("{\"meta\":{}}");

    WorkSpaceOverviewResponse mockResponse =
        new WorkSpaceOverviewResponse(
            null, null, null, null, null, List.of(), List.of(), List.of(), List.of(), List.of());
    when(objectMapper.readValue("{\"meta\":{}}", WorkSpaceOverviewResponse.class))
        .thenReturn(mockResponse);

    WorkSpaceOverviewResponse result = overviewQueryService.getOverview(1L, null);

    assertThat(result).isNotNull();
    verify(documentQueryApi, never()).getWorkspaceDocStats(anyLong());
  }

  @Test
  @DisplayName("缓存未命中时聚合多模块数据，填充用户信息并写入 Redis 缓存")
  void testGetOverview_CacheMiss_AggregatesAndCaches() throws Exception {
    Long wsId = 1L;
    Long ownerId = 100L;
    Long authorId = 101L;

    WorkSpaceEntity ws =
        WorkSpaceEntity.create(
            "开源协作组",
            "open-team",
            "简介",
            "http://avatar.png",
            ownerId,
            60,
            WorkSpaceAclEnum.INTERNAL);
    ReflectionTestUtils.setField(ws, "id", wsId);

    when(workSpaceRepository.findById(wsId)).thenReturn(Optional.of(ws));
    when(workSpaceMemberQueryService.isMember(wsId, ownerId)).thenReturn(true);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get("ll:ws:overview:1")).thenReturn(null);

    // Mock DocumentQueryApi stats
    WorkspaceDocStatsDTO.HotDocumentDTO hotDoc =
        new WorkspaceDocStatsDTO.HotDocumentDTO(
            10L, "架构指南", "简介", 520, 3500, authorId, LocalDateTime.now());
    WorkspaceDocStatsDTO.TopAuthorDTO topAuthor =
        new WorkspaceDocStatsDTO.TopAuthorDTO(authorId, 5L, 12000L);
    WorkspaceDocStatsDTO.RecentDocumentDTO recentDoc =
        new WorkspaceDocStatsDTO.RecentDocumentDTO(
            11L,
            "发布文档",
            2,
            "v1.1",
            io.lexiloom.application.document.statemachine.DocumentStateEnum.APPROVED,
            authorId,
            LocalDateTime.now());
    WorkspaceDocStatsDTO.CategoryDistributionDTO catDist =
        new WorkspaceDocStatsDTO.CategoryDistributionDTO(1L, "技术规范", 3L);
    WorkspaceDocStatsDTO.FileTypeDistributionDTO fileDist =
        new WorkspaceDocStatsDTO.FileTypeDistributionDTO("pdf", 10L);

    WorkspaceDocStatsDTO docStats =
        new WorkspaceDocStatsDTO(
            15L,
            12L,
            2L,
            1L,
            45000L,
            3000,
            10L,
            10485760L,
            "10.0 MB",
            100.0,
            4L,
            880L,
            30L,
            15L,
            List.of(hotDoc),
            List.of(recentDoc),
            List.of(topAuthor),
            List.of(catDist),
            List.of(fileDist));
    when(documentQueryApi.getWorkspaceDocStats(wsId)).thenReturn(docStats);

    // Mock Members
    when(workSpaceMemberRepository.countByWorkSpaceId(wsId)).thenReturn(12L);
    when(workSpaceMemberRepository.countByWorkSpaceIdAndRole(wsId, WorkSpaceRoleEnum.OWNER))
        .thenReturn(1L);
    when(workSpaceMemberRepository.countByWorkSpaceIdAndRole(wsId, WorkSpaceRoleEnum.ADMIN))
        .thenReturn(2L);
    when(workSpaceMemberRepository.countByWorkSpaceIdAndRole(wsId, WorkSpaceRoleEnum.MEMBER))
        .thenReturn(8L);
    when(workSpaceMemberRepository.countByWorkSpaceIdAndRole(wsId, WorkSpaceRoleEnum.GUEST))
        .thenReturn(1L);

    // Mock AI DocChunks
    when(docChunkRepository.countByWorkspaceId("1")).thenReturn(240L);
    when(aiProperties.isEnabled()).thenReturn(true);

    // Mock Users
    UserEntity authorUser = new UserEntity();
    ReflectionTestUtils.setField(authorUser, "id", authorId);
    authorUser.setUserName("张工");
    authorUser.setAvatarUrl("http://avatar.com/zhang.jpg");
    when(userRepository.findAllById(any())).thenReturn(List.of(authorUser));

    when(objectMapper.writeValueAsString(any())).thenReturn("{\"cached\":true}");

    WorkSpaceOverviewResponse response = overviewQueryService.getOverview(wsId, ownerId);

    assertThat(response).isNotNull();
    assertThat(response.workspace().name()).isEqualTo("开源协作组");
    assertThat(response.workspace().code()).isEqualTo("open-team");
    assertThat(response.assets().totalDocuments()).isEqualTo(15L);
    assertThat(response.assets().formattedStorage()).isEqualTo("10.0 MB");
    assertThat(response.ai().totalChunks()).isEqualTo(240L);
    assertThat(response.ai().aiEnabled()).isTrue();
    assertThat(response.members().currentMembers()).isEqualTo(12L);
    assertThat(response.members().maxMembers()).isEqualTo(60);
    assertThat(response.members().usagePercentage()).isEqualTo(20.0);
    assertThat(response.members().adminCount()).isEqualTo(2L);
    assertThat(response.interaction().totalReadCount()).isEqualTo(15L);
    assertThat(response.interaction().totalComments()).isEqualTo(880L);

    assertThat(response.hotDocuments()).hasSize(1);
    assertThat(response.hotDocuments().get(0).authorName()).isEqualTo("张工");
    assertThat(response.hotDocuments().get(0).authorAvatar())
        .isEqualTo("http://avatar.com/zhang.jpg");

    assertThat(response.topContributors()).hasSize(1);
    assertThat(response.topContributors().get(0).userName()).isEqualTo("张工");

    assertThat(response.recentDocuments()).hasSize(1);
    assertThat(response.recentDocuments().get(0).authorName()).isEqualTo("张工");

    // Verify cache saved with 180s TTL
    verify(valueOperations)
        .set(eq("ll:ws:overview:1"), eq("{\"cached\":true}"), eq(180L), eq(TimeUnit.SECONDS));
  }
}
