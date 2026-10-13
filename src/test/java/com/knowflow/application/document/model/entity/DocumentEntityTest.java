package com.knowflow.application.document.model.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowflow.application.document.model.enums.DocSourceTypeEnum;
import com.knowflow.application.document.statemachine.DocumentStateEnum;
import com.knowflow.application.enums.WorkSpaceAclEnum;
import com.knowflow.application.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DocumentEntityTest {

  @Test
  @DisplayName("从文件导入创建文档 - 默认状态为待审核且版本为 1")
  void testCreateFromImportDocument() {
    DocumentEntity doc =
        DocumentEntity.createFromImport(
            1L, 10L, "设计规范", "摘要描述", "# 正文内容", 100L, WorkSpaceAclEnum.INTERNAL);

    assertThat(doc.getWorkSpaceId()).isEqualTo(1L);
    assertThat(doc.getCategoryId()).isEqualTo(10L);
    assertThat(doc.getTitle()).isEqualTo("设计规范");
    assertThat(doc.getContent()).isEqualTo("# 正文内容");
    assertThat(doc.getStatus()).isEqualTo(DocumentStateEnum.PENDING_APPROVAL);
    assertThat(doc.getSourceType()).isEqualTo(DocSourceTypeEnum.IMPORT);
    assertThat(doc.getSourceFileId()).isEqualTo(100L);
    assertThat(doc.getCurrentVersion()).isEqualTo(1);
    assertThat(doc.getCurrentVersionTag()).isEqualTo("v1.0");
    assertThat(doc.getVersionCount()).isEqualTo(1);
    assertThat(doc.getPublishedVersion()).isNull();
    assertThat(doc.getReadCount()).isEqualTo(0);
    assertThat(doc.getLikeCount()).isEqualTo(0);
  }

  @Test
  @DisplayName("创建文档缺少标题抛出业务异常")
  void testCreateDocumentWithoutTitleThrows() {
    assertThatThrownBy(() -> DocumentEntity.createFromImport(1L, 0L, "", null, null, null))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("文档标题不能为空");
  }

  @Test
  @DisplayName("发布版本与升级新版本 - 同一文档最多只有一个版本处于审批通过状态")
  void testPublishAndUpgradeVersionSinglePublishedRule() {
    DocumentEntity doc =
        DocumentEntity.createFromImport(1L, 0L, "架构说明", null, "正文", WorkSpaceAclEnum.INTERNAL);

    // 初始导入为版本 1，未审批状态
    assertThat(doc.getCurrentVersion()).isEqualTo(1);
    assertThat(doc.getPublishedVersion()).isNull();

    // 审批通过发布版本 1
    int versionNum1 = doc.publish("v1.0");
    assertThat(versionNum1).isEqualTo(1);
    assertThat(doc.getCurrentVersion()).isEqualTo(1);
    assertThat(doc.getCurrentVersionTag()).isEqualTo("v1.0");
    assertThat(doc.getPublishedVersion()).isEqualTo(1);
    assertThat(doc.getStatus()).isEqualTo(DocumentStateEnum.APPROVED);

    // 升级导入新版本 2：版本号递增，回归待审核未审批状态，旧版本审批状态失效
    doc.upgradeToNewVersion(2, "v2.0", "架构说明v2", "升级摘要", "新正文", 200L, "file.docx");
    assertThat(doc.getCurrentVersion()).isEqualTo(2);
    assertThat(doc.getCurrentVersionTag()).isEqualTo("v2.0");
    assertThat(doc.getStatus()).isEqualTo(DocumentStateEnum.PENDING_APPROVAL);
    assertThat(doc.getPublishedVersion()).isNull();
    assertThat(doc.getVersionCount()).isEqualTo(2);

    // 审批通过发布版本 2
    doc.publish();
    assertThat(doc.getStatus()).isEqualTo(DocumentStateEnum.APPROVED);
    assertThat(doc.getPublishedVersion()).isEqualTo(2);
  }

  @Test
  @DisplayName("发布新版本 - 版本序号递增并置为已发布")
  void testPublishNewVersion() {
    DocumentEntity doc =
        DocumentEntity.createFromImport(1L, 0L, "架构说明", null, "正文", WorkSpaceAclEnum.INTERNAL);

    int versionNum1 = doc.publishNewVersion("v1.0");
    assertThat(versionNum1).isEqualTo(1);
    assertThat(doc.getCurrentVersion()).isEqualTo(1);
    assertThat(doc.getCurrentVersionTag()).isEqualTo("v1.0");
    assertThat(doc.getStatus()).isEqualTo(DocumentStateEnum.APPROVED);

    int versionNum2 = doc.publishNewVersion(null);
    assertThat(versionNum2).isEqualTo(2);
    assertThat(doc.getCurrentVersionTag()).isEqualTo("v2.0");
  }

  @Test
  @DisplayName("内容更新与编辑状态守卫")
  void testUpdateContentAndGuard() {
    DocumentEntity doc = DocumentEntity.createFromImport(1L, 0L, "旧标题", null, "旧内容", null);

    // 临时切到 REJECTED 状态允许编辑
    doc.transitionTo(DocumentStateEnum.REJECTED);
    doc.updateContent("新标题", "新摘要", "新内容", 3);
    assertThat(doc.getTitle()).isEqualTo("新标题");
    assertThat(doc.getSummary()).isEqualTo("新摘要");
    assertThat(doc.getContent()).isEqualTo("新内容");
    assertThat(doc.getWordCount()).isEqualTo(3);

    // 状态流转为待审核
    doc.transitionTo(DocumentStateEnum.PENDING_APPROVAL);
    assertThatThrownBy(() -> doc.updateContent("改动", null, null, null))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不可编辑");
  }

  @Test
  @DisplayName("版本回滚 - 还原正文并退回待审核状态")
  void testRollbackToVersion() {
    DocumentEntity doc = DocumentEntity.createFromImport(1L, 0L, "初始", null, "初始内容", null);
    doc.publishNewVersion("v1.0");

    doc.rollbackToVersion(1, "初始", "初始内容");
    assertThat(doc.getStatus()).isEqualTo(DocumentStateEnum.PENDING_APPROVAL);
    assertThat(doc.getContent()).isEqualTo("初始内容");
  }
}
