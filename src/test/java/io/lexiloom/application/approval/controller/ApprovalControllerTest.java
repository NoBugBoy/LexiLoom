package io.lexiloom.application.approval.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.lexiloom.application.approval.api.ApprovalApi;
import io.lexiloom.application.approval.api.dto.ApprovalRecordDTO;
import io.lexiloom.application.common.SecurityHolder;
import io.lexiloom.application.enums.ApprovalStatusEnum;
import io.lexiloom.application.enums.ApprovalTypeEnum;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApprovalController 统一审批流控制器测试")
class ApprovalControllerTest {

  private MockMvc mockMvc;

  @Mock private ApprovalApi approvalApi;

  @InjectMocks private ApprovalController controller;

  private final Long currentUserId = 888L;

  @BeforeEach
  void setUp() {
    SecurityHolder.setAuthentication(currentUserId);
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    mockMvc = MockMvcBuilders.standaloneSetup(controller).setValidator(validator).build();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("GET /api/approvals/pending - 查询待审批列表")
  void testListPending() throws Exception {
    ApprovalRecordDTO dto =
        new ApprovalRecordDTO(
            5001L,
            ApprovalTypeEnum.SPACE_LINK,
            1001L,
            1L,
            2L,
            999L,
            "申请人",
            "申请关联",
            ApprovalStatusEnum.PENDING,
            null,
            null,
            null,
            null,
            "{\"documentIds\":[10]}",
            LocalDateTime.now());

    when(approvalApi.listPendingApprovals(2L)).thenReturn(List.of(dto));

    mockMvc
        .perform(get("/api/approvals/pending").param("targetWorkspaceId", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].id", is(5001)))
        .andExpect(jsonPath("$[0].approvalType", is("SPACE_LINK")));

    verify(approvalApi).listPendingApprovals(2L);
  }

  @Test
  @DisplayName("GET /api/approvals/history - 查询审批流转历史记录")
  void testListHistory() throws Exception {
    ApprovalRecordDTO dto =
        new ApprovalRecordDTO(
            5001L,
            ApprovalTypeEnum.DOCUMENT_AUDIT,
            2001L,
            1L,
            1L,
            999L,
            "申请人",
            "申请发布",
            ApprovalStatusEnum.APPROVED,
            888L,
            "管理员",
            "审核通过",
            LocalDateTime.now(),
            null,
            LocalDateTime.now());

    when(approvalApi.listApprovalHistory(ApprovalTypeEnum.DOCUMENT_AUDIT, 2001L))
        .thenReturn(List.of(dto));

    mockMvc
        .perform(
            get("/api/approvals/history").param("type", "DOCUMENT_AUDIT").param("bizId", "2001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].status", is("APPROVED")))
        .andExpect(jsonPath("$[0].approvalComment", is("审核通过")));

    verify(approvalApi).listApprovalHistory(ApprovalTypeEnum.DOCUMENT_AUDIT, 2001L);
  }

  @Test
  @DisplayName("POST /api/approvals/action - 执行审批通过")
  void testApproveAction() throws Exception {
    String json =
        """
        {
          "approvalType": "SPACE_LINK",
          "bizId": 1001,
          "action": "APPROVE",
          "comment": "经部门总监核验通过"
        }
        """;

    mockMvc
        .perform(
            post("/api/approvals/action").contentType(MediaType.APPLICATION_JSON).content(json))
        .andExpect(status().isOk());

    verify(approvalApi)
        .approve(
            ApprovalTypeEnum.SPACE_LINK,
            1001L,
            currentUserId,
            String.valueOf(currentUserId),
            "经部门总监核验通过");
  }

  @Test
  @DisplayName("POST /api/approvals/action - 执行审批驳回")
  void testRejectAction() throws Exception {
    String json =
        """
        {
          "approvalType": "DOCUMENT_AUDIT",
          "bizId": 2001,
          "action": "REJECT",
          "comment": "内容包含未定稿内部保密条款"
        }
        """;

    mockMvc
        .perform(
            post("/api/approvals/action").contentType(MediaType.APPLICATION_JSON).content(json))
        .andExpect(status().isOk());

    verify(approvalApi)
        .reject(
            ApprovalTypeEnum.DOCUMENT_AUDIT,
            2001L,
            currentUserId,
            String.valueOf(currentUserId),
            "内容包含未定稿内部保密条款");
  }

  @Test
  @DisplayName("POST /api/approvals/action - 执行申请人主动撤回 (CANCEL，支持无 comment 或带 comment)")
  void testCancelAction() throws Exception {
    String json =
        """
        {
          "approvalType": "DOCUMENT_AUDIT",
          "bizId": 2001,
          "action": "CANCEL"
        }
        """;

    mockMvc
        .perform(
            post("/api/approvals/action").contentType(MediaType.APPLICATION_JSON).content(json))
        .andExpect(status().isOk());

    verify(approvalApi).cancel(ApprovalTypeEnum.DOCUMENT_AUDIT, 2001L, currentUserId, null);
  }
}
