package com.knowflow.application.document.model.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowflow.application.document.parser.DocParseStatusEnum;
import com.knowflow.application.document.parser.DocParserEngineEnum;
import com.knowflow.application.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DocSourceFileEntityTest {

  @Test
  @DisplayName("创建来源文件实体与解析状态流转")
  void testCreateAndStateTransition() {
    DocSourceFileEntity file =
        DocSourceFileEntity.create(
            1L,
            null,
            "技术架构.docx",
            102400L,
            "docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "files/1/hash.docx",
            "http://rustfs/knowflow/files/1/hash.docx");

    assertThat(file.getWorkSpaceId()).isEqualTo(1L);
    assertThat(file.getDocumentId()).isNull();
    assertThat(file.getOriginalFileName()).isEqualTo("技术架构.docx");
    assertThat(file.getFileExt()).isEqualTo("docx");
    assertThat(file.getParseStatus()).isEqualTo(DocParseStatusEnum.ASYNC_PROCESSING);

    // 绑定主文档
    file.bindDocument(100L);
    assertThat(file.getDocumentId()).isEqualTo(100L);

    // 标记解析成功 (含 parseMetadata)
    String metadataJson = "{\"chunks\":[{\"chunk_id\":\"c1\"}]}";
    file.markParseSuccess("# 提取内容", DocParserEngineEnum.DEEPDOC, metadataJson);
    assertThat(file.getParseStatus()).isEqualTo(DocParseStatusEnum.SUCCESS);
    assertThat(file.getRawText()).isEqualTo("# 提取内容");
    assertThat(file.getParseEngine()).isEqualTo(DocParserEngineEnum.DEEPDOC);
    assertThat(file.getParseMetadata()).isEqualTo(metadataJson);
  }

  @Test
  @DisplayName("创建来源文件必填字段校验")
  void testValidateRequiredFields() {
    assertThatThrownBy(
            () ->
                DocSourceFileEntity.create(
                    1L, null, "", 100L, "pdf", "application/pdf", "hash", "path", "url"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("原始文件名不能为空");

    assertThatThrownBy(
            () ->
                DocSourceFileEntity.create(
                    1L, null, "file.pdf", 100L, "pdf", "application/pdf", "", "path", "url"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("文件哈希不能为空");
  }
}
