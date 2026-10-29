package io.lexiloom.application.enums;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("ApplicationEnums 充血模型与容错反序列化测试")
class ApplicationEnumsTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("WorkSpaceAclEnum：领域谓词与公开访问性校验")
  void testWorkSpaceAclEnum() throws Exception {
    assertThat(WorkSpaceAclEnum.PUBLIC.isPublic()).isTrue();
    assertThat(WorkSpaceAclEnum.PUBLIC.isAccessibleToWorkspaceMembers()).isTrue();

    assertThat(WorkSpaceAclEnum.INTERNAL.isInternal()).isTrue();
    assertThat(WorkSpaceAclEnum.INTERNAL.isAccessibleToWorkspaceMembers()).isTrue();

    assertThat(WorkSpaceAclEnum.PRIVATE.isPrivate()).isTrue();
    assertThat(WorkSpaceAclEnum.PRIVATE.isAccessibleToWorkspaceMembers()).isFalse();

    assertThat(WorkSpaceAclEnum.fromValue("public")).isEqualTo(WorkSpaceAclEnum.PUBLIC);
    assertThat(WorkSpaceAclEnum.fromValue("公开")).isEqualTo(WorkSpaceAclEnum.PUBLIC);
    assertThat(WorkSpaceAclEnum.fromValue("团队内部")).isEqualTo(WorkSpaceAclEnum.INTERNAL);
    assertThat(WorkSpaceAclEnum.ofNullable("private")).contains(WorkSpaceAclEnum.PRIVATE);
    assertThat(WorkSpaceAclEnum.ofOrDefault(null, WorkSpaceAclEnum.INTERNAL))
        .isEqualTo(WorkSpaceAclEnum.INTERNAL);

    String json = objectMapper.writeValueAsString(WorkSpaceAclEnum.PUBLIC);
    assertThat(json).isEqualTo("\"PUBLIC\"");
    assertThat(objectMapper.readValue("\"public\"", WorkSpaceAclEnum.class))
        .isEqualTo(WorkSpaceAclEnum.PUBLIC);
  }

  @Test
  @DisplayName("WorkSpaceRoleEnum：角色层级、管理权限与比较方法")
  void testWorkSpaceRoleEnum() throws Exception {
    assertThat(WorkSpaceRoleEnum.OWNER.isOwner()).isTrue();
    assertThat(WorkSpaceRoleEnum.OWNER.isAdminOrAbove()).isTrue();
    assertThat(WorkSpaceRoleEnum.ADMIN.isAdminOrAbove()).isTrue();
    assertThat(WorkSpaceRoleEnum.MEMBER.isAdminOrAbove()).isFalse();
    assertThat(WorkSpaceRoleEnum.MEMBER.canEdit()).isTrue();
    assertThat(WorkSpaceRoleEnum.GUEST.canEdit()).isFalse();

    assertThat(WorkSpaceRoleEnum.OWNER.canManage(WorkSpaceRoleEnum.ADMIN)).isTrue();
    assertThat(WorkSpaceRoleEnum.ADMIN.canManage(WorkSpaceRoleEnum.MEMBER)).isTrue();
    assertThat(WorkSpaceRoleEnum.MEMBER.canManage(WorkSpaceRoleEnum.ADMIN)).isFalse();

    assertThat(WorkSpaceRoleEnum.ADMIN.isAtLeast(WorkSpaceRoleEnum.MEMBER)).isTrue();
    assertThat(WorkSpaceRoleEnum.GUEST.isAtLeast(WorkSpaceRoleEnum.MEMBER)).isFalse();

    assertThat(WorkSpaceRoleEnum.fromValue("owner")).isEqualTo(WorkSpaceRoleEnum.OWNER);
    assertThat(WorkSpaceRoleEnum.fromValue("管理员")).isEqualTo(WorkSpaceRoleEnum.ADMIN);
    assertThat(objectMapper.readValue("\"member\"", WorkSpaceRoleEnum.class))
        .isEqualTo(WorkSpaceRoleEnum.MEMBER);
  }

  @Test
  @DisplayName("SpaceLinkStatusEnum：横向关联审批状态充血谓词与反序列化")
  void testSpaceLinkStatusEnum() throws Exception {
    assertThat(SpaceLinkStatusEnum.APPROVED.isApproved()).isTrue();
    assertThat(SpaceLinkStatusEnum.APPROVED.isTerminated()).isTrue();
    assertThat(SpaceLinkStatusEnum.PENDING.isPending()).isTrue();
    assertThat(SpaceLinkStatusEnum.PENDING.isTerminated()).isFalse();
    assertThat(SpaceLinkStatusEnum.REJECTED.isRejected()).isTrue();

    assertThat(SpaceLinkStatusEnum.fromValue("approved")).isEqualTo(SpaceLinkStatusEnum.APPROVED);
    assertThat(SpaceLinkStatusEnum.fromValue("已同意")).isEqualTo(SpaceLinkStatusEnum.APPROVED);
    assertThat(SpaceLinkStatusEnum.fromValue("驳回")).isEqualTo(SpaceLinkStatusEnum.REJECTED);
  }

  @Test
  @DisplayName("WorkSpaceStatusEnum：空间状态活跃度与容错解析")
  void testWorkSpaceStatusEnum() throws Exception {
    assertThat(WorkSpaceStatusEnum.NORMAL.isActive()).isTrue();
    assertThat(WorkSpaceStatusEnum.FROZEN.isFrozen()).isTrue();
    assertThat(WorkSpaceStatusEnum.FROZEN.isActive()).isFalse();
    assertThat(WorkSpaceStatusEnum.DISBANDED.isDisbanded()).isTrue();

    assertThat(WorkSpaceStatusEnum.fromValue("frozen")).isEqualTo(WorkSpaceStatusEnum.FROZEN);
    assertThat(WorkSpaceStatusEnum.fromValue("解散")).isEqualTo(WorkSpaceStatusEnum.DISBANDED);
  }

  @Test
  @DisplayName("ApprovalStatusEnum & ApprovalTypeEnum：审批流程枚举谓词")
  void testApprovalEnums() throws Exception {
    assertThat(ApprovalStatusEnum.PENDING.isPending()).isTrue();
    assertThat(ApprovalStatusEnum.PENDING.isTerminated()).isFalse();
    assertThat(ApprovalStatusEnum.APPROVED.isApproved()).isTrue();
    assertThat(ApprovalStatusEnum.APPROVED.isTerminated()).isTrue();
    assertThat(ApprovalStatusEnum.CANCELLED.isCancelled()).isTrue();

    assertThat(ApprovalTypeEnum.DOCUMENT_AUDIT.isDocumentAudit()).isTrue();
    assertThat(ApprovalTypeEnum.SPACE_LINK.isSpaceLink()).isTrue();

    assertThat(ApprovalStatusEnum.fromValue("approved")).isEqualTo(ApprovalStatusEnum.APPROVED);
    assertThat(ApprovalTypeEnum.fromValue("space_link")).isEqualTo(ApprovalTypeEnum.SPACE_LINK);
  }

  @Test
  @DisplayName("AdminRoleEnum：管理端角色等级与权限比较")
  void testAdminRoleEnum() {
    assertThat(AdminRoleEnum.SUPER_ADMIN.isSuperAdmin()).isTrue();
    assertThat(AdminRoleEnum.SUPER_ADMIN.isAdminOrAbove()).isTrue();
    assertThat(AdminRoleEnum.SUPER_ADMIN.canManage(AdminRoleEnum.ADMIN)).isTrue();
    assertThat(AdminRoleEnum.ADMIN.canManage(AdminRoleEnum.MEMBER)).isTrue();
    assertThat(AdminRoleEnum.MEMBER.isAdminOrAbove()).isFalse();

    assertThat(AdminRoleEnum.fromValue("admin")).isEqualTo(AdminRoleEnum.ADMIN);
    assertThat(AdminRoleEnum.fromValue("超级管理员")).isEqualTo(AdminRoleEnum.SUPER_ADMIN);
  }

  @Test
  @DisplayName("OpenApiKeyStatusEnum & OpenApiScopeEnum & AiLlmProviderEnum")
  void testOpenApiAndAiEnums() {
    assertThat(OpenApiKeyStatusEnum.ENABLED.isEnabled()).isTrue();
    assertThat(OpenApiKeyStatusEnum.DISABLED.isDisabled()).isTrue();
    assertThat(OpenApiKeyStatusEnum.fromValue("enabled")).isEqualTo(OpenApiKeyStatusEnum.ENABLED);

    assertThat(AiLlmProviderEnum.NONE.isCustomProvider()).isFalse();
    assertThat(AiLlmProviderEnum.DEEPSEEK.isCustomProvider()).isTrue();
    assertThat(AiLlmProviderEnum.fromValue("dashscope")).isEqualTo(AiLlmProviderEnum.QWEN);

    assertThat(OpenApiScopeEnum.fromValue("kb:read")).isEqualTo(OpenApiScopeEnum.KB_READ);
    assertThat(OpenApiScopeEnum.fromValue("chat:completion"))
        .isEqualTo(OpenApiScopeEnum.CHAT_COMPLETION);
    assertThat(OpenApiScopeEnum.contains("kb:read,kb:search", OpenApiScopeEnum.KB_READ)).isTrue();
    assertThat(OpenApiScopeEnum.contains("kb:read", OpenApiScopeEnum.KB_SEARCH)).isFalse();
  }
}
