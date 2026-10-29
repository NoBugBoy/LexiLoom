package io.lexiloom.application.document.acl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.lexiloom.application.document.model.entity.DocumentEntity;
import io.lexiloom.application.document.api.dto.DocumentStateEnum;
import io.lexiloom.application.enums.WorkSpaceAclEnum;
import io.lexiloom.application.enums.WorkSpaceRoleEnum;
import io.lexiloom.application.exception.BusinessException;
import io.lexiloom.application.user.api.WorkSpaceMemberQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class DocAccessControlServiceTest {

  @Mock private WorkSpaceMemberQueryService workSpaceMemberQueryService;

  private DocAccessControlService aclService;

  @BeforeEach
  void setUp() {
    aclService = new DocAccessControlService(workSpaceMemberQueryService);
  }

  @Test
  @DisplayName("PUBLIC 级别：任何已登录用户均可查阅")
  void testPublicDocReadAllowed() {
    DocumentEntity doc =
        DocumentEntity.createFromImport(1L, 0L, "公开文档", "摘要", "内容", WorkSpaceAclEnum.PUBLIC);
    ReflectionTestUtils.setField(doc, "id", 10L);

    assertThat(aclService.canRead(doc, 999L)).isTrue();
  }

  @Test
  @DisplayName("INTERNAL 级别：工作区成员可读写，非工作区成员拦截拒绝")
  void testInternalDocAccess() {
    DocumentEntity doc =
        DocumentEntity.createFromImport(1L, 0L, "内部文档", "摘要", "内容", WorkSpaceAclEnum.INTERNAL);
    ReflectionTestUtils.setField(doc, "id", 11L);
    doc.transitionTo(DocumentStateEnum.APPROVED);

    // 成员 100: isMember=true, hasRole(MEMBER)=true
    when(workSpaceMemberQueryService.isMember(1L, 100L)).thenReturn(true);
    when(workSpaceMemberQueryService.hasRole(1L, 100L, WorkSpaceRoleEnum.MEMBER)).thenReturn(true);
    // 非成员 999: isMember=false
    when(workSpaceMemberQueryService.isMember(1L, 999L)).thenReturn(false);

    assertThat(aclService.canRead(doc, 100L)).isTrue();
    assertThat(aclService.canWrite(doc, 100L)).isTrue();

    assertThat(aclService.canRead(doc, 999L)).isFalse();
    assertThat(aclService.canWrite(doc, 999L)).isFalse();
    assertThatThrownBy(() -> aclService.assertCanRead(doc, 999L))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("PRIVATE 级别：仅创建者或团队管理员/Owner有权查看与编辑")
  void testPrivateDocAccess() {
    DocumentEntity doc =
        DocumentEntity.createFromImport(1L, 0L, "私密文档", "摘要", "内容", WorkSpaceAclEnum.PRIVATE);
    ReflectionTestUtils.setField(doc, "id", 12L);
    ReflectionTestUtils.setField(doc, "createBy", 200L);
    doc.transitionTo(DocumentStateEnum.APPROVED);

    // 1. 创建者本人(200) -> canRead 因 userId.equals(createBy) 直接返回 true，不查成员
    assertThat(aclService.canRead(doc, 200L)).isTrue();
    // canWrite: 先 isMember，再因 userId==createBy 返回 true
    when(workSpaceMemberQueryService.isMember(1L, 200L)).thenReturn(true);
    assertThat(aclService.canWrite(doc, 200L)).isTrue();

    // 2. 普通成员(非创建者 300) -> 拒绝
    when(workSpaceMemberQueryService.isMember(1L, 300L)).thenReturn(true);
    when(workSpaceMemberQueryService.hasRole(1L, 300L, WorkSpaceRoleEnum.ADMIN)).thenReturn(false);
    assertThat(aclService.canRead(doc, 300L)).isFalse();
    assertThat(aclService.canWrite(doc, 300L)).isFalse();

    // 3. 管理员(400) -> 允许
    when(workSpaceMemberQueryService.isMember(1L, 400L)).thenReturn(true);
    when(workSpaceMemberQueryService.hasRole(1L, 400L, WorkSpaceRoleEnum.ADMIN)).thenReturn(true);
    assertThat(aclService.canRead(doc, 400L)).isTrue();
    assertThat(aclService.canWrite(doc, 400L)).isTrue();
  }
}
