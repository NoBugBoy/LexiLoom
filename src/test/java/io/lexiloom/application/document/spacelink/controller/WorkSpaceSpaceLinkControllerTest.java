package io.lexiloom.application.document.spacelink.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.lexiloom.application.document.api.dto.LinkableDocumentDTO;
import io.lexiloom.application.document.spacelink.model.response.AvailableSourcesResponse;
import io.lexiloom.application.document.spacelink.model.response.AvailableSourcesResponse.SpaceLinkSourceItem;
import io.lexiloom.application.document.spacelink.model.response.AvailableSourcesResponse.WorkspaceSourceItem;
import io.lexiloom.application.document.spacelink.model.response.SpaceLinkResponse;
import io.lexiloom.application.document.spacelink.service.WorkSpaceSpaceLinkService;
import io.lexiloom.application.enums.SpaceLinkStatusEnum;
import io.lexiloom.application.enums.WorkSpaceAclEnum;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkSpaceSpaceLinkController 单元测试")
class WorkSpaceSpaceLinkControllerTest {

  private MockMvc mockMvc;

  @Mock private WorkSpaceSpaceLinkService workSpaceSpaceLinkService;

  @InjectMocks private WorkSpaceSpaceLinkController controller;

  @BeforeEach
  void setUp() {
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    mockMvc = MockMvcBuilders.standaloneSetup(controller).setValidator(validator).build();
  }

  @Test
  @DisplayName("POST /workspace/{sourceWorkspaceId}/space-links - 申请 SpaceLink 关联")
  void testApplySpaceLink() throws Exception {
    when(workSpaceSpaceLinkService.apply(1L, 2L, List.of(101L), "申请知识共享")).thenReturn(888L);

    String json = "{\"targetWorkspaceId\": 2, \"documentIds\": [101], \"applyReason\": \"申请知识共享\"}";

    mockMvc
        .perform(
            post("/workspace/1/space-links")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", is(888)));

    verify(workSpaceSpaceLinkService).apply(1L, 2L, List.of(101L), "申请知识共享");
  }

  @Test
  @DisplayName("POST /workspace/{targetWorkspaceId}/space-links/{linkId}/approval - 审批通过")
  void testApproveSpaceLink() throws Exception {
    String json = "{\"action\": \"APPROVE\", \"comment\": \"同意关联\"}";

    mockMvc
        .perform(
            post("/workspace/2/space-links/888/approval")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
        .andExpect(status().isOk());

    verify(workSpaceSpaceLinkService).approve(2L, 888L, "同意关联");
  }

  @Test
  @DisplayName("POST /workspace/{targetWorkspaceId}/space-links/{linkId}/approval - 审批驳回")
  void testRejectSpaceLink() throws Exception {
    String json = "{\"action\": \"REJECT\", \"comment\": \"安全边界不符\"}";

    mockMvc
        .perform(
            post("/workspace/2/space-links/888/approval")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
        .andExpect(status().isOk());

    verify(workSpaceSpaceLinkService).reject(2L, 888L, "安全边界不符");
  }

  @Test
  @DisplayName("GET /workspace/{workspaceId}/available-sources - 查询可用检索源拓扑")
  void testGetAvailableSources() throws Exception {
    AvailableSourcesResponse mockResponse =
        new AvailableSourcesResponse(
            new WorkspaceSourceItem(1L, "A部门"),
            List.of(new WorkspaceSourceItem(2L, "B项目")),
            List.of(new SpaceLinkSourceItem(3L, "C项目", "APPROVED")));

    when(workSpaceSpaceLinkService.getAvailableSources(1L)).thenReturn(mockResponse);

    mockMvc
        .perform(get("/workspace/1/available-sources"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.primary.workspaceId", is(1)))
        .andExpect(jsonPath("$.primary.workspaceName", is("A部门")))
        .andExpect(jsonPath("$.children", hasSize(1)))
        .andExpect(jsonPath("$.children[0].workspaceId", is(2)))
        .andExpect(jsonPath("$.explicitLinks", hasSize(1)))
        .andExpect(jsonPath("$.explicitLinks[0].workspaceId", is(3)));

    verify(workSpaceSpaceLinkService).getAvailableSources(1L);
  }

  @Test
  @DisplayName("GET /workspace/{workspaceId}/space-links/my-applications - 查询发起的关联列表")
  void testListAppliedLinks() throws Exception {
    SpaceLinkResponse item =
        new SpaceLinkResponse(
            888L,
            1L,
            "A部门",
            2L,
            "B项目",
            SpaceLinkStatusEnum.APPROVED,
            null,
            List.of(101L),
            LocalDateTime.now(),
            LocalDateTime.now());
    when(workSpaceSpaceLinkService.listMyAppliedLinks(1L)).thenReturn(List.of(item));

    mockMvc
        .perform(get("/workspace/1/space-links/my-applications"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].id", is(888)));

    verify(workSpaceSpaceLinkService).listMyAppliedLinks(1L);
  }

  @Test
  @DisplayName("GET /workspace/{sourceWorkspaceId}/space-links/linkable-documents - 查询可关联目标文档列表")
  void testListLinkableDocuments() throws Exception {
    LinkableDocumentDTO docItem =
        new LinkableDocumentDTO(
            101L,
            "技术架构规范",
            1L,
            WorkSpaceAclEnum.PUBLIC,
            LocalDateTime.now());
    when(workSpaceSpaceLinkService.listLinkableDocuments(1L, 2L, "技术"))
        .thenReturn(List.of(docItem));

    mockMvc
        .perform(
            get("/workspace/1/space-links/linkable-documents")
                .param("targetWorkspaceId", "2")
                .param("keyword", "技术"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].documentId", is(101)))
        .andExpect(jsonPath("$[0].title", is("技术架构规范")));

    verify(workSpaceSpaceLinkService).listLinkableDocuments(1L, 2L, "技术");
  }
}
