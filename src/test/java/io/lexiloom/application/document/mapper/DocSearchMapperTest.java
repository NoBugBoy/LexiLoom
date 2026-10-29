package io.lexiloom.application.document.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.lexiloom.application.document.model.entity.DocSourceFileEntity;
import io.lexiloom.application.document.model.entity.DocumentEntity;
import io.lexiloom.application.document.parser.DocParserEngineEnum;
import io.lexiloom.application.document.search.DocSearchDocument;
import io.lexiloom.application.document.api.dto.DocumentStateEnum;
import io.lexiloom.application.enums.WorkSpaceAclEnum;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

@DisplayName("DocSearchMapper MapStruct 映射器测试")
class DocSearchMapperTest {

  private final DocSearchMapper mapper = Mappers.getMapper(DocSearchMapper.class);

  @Test
  @DisplayName("完整实体映射为 DocSearchDocument")
  void testToSearchDocComplete() {
    DocumentEntity doc =
        DocumentEntity.createFromImport(
            10L, 20L, "设计规范文档", "这是摘要", "# 详细正文", WorkSpaceAclEnum.INTERNAL);
    doc.setId(1001L);
    doc.transitionTo(DocumentStateEnum.APPROVED);
    doc.setCreateBy(88L);
    LocalDateTime updateTime = LocalDateTime.of(2026, 10, 1, 10, 0, 0);
    doc.setUpdateTime(updateTime);

    DocSourceFileEntity sourceFile =
        DocSourceFileEntity.create(
            10L,
            1001L,
            "manual.docx",
            2048L,
            "docx",
            "application/docx",
            "hash999",
            "storage/manual.docx",
            "http://rustfs/manual.docx");
    sourceFile.markParseSuccess("提取的纯文本内容", DocParserEngineEnum.DEEPDOC);

    List<String> tags = List.of("文档", "工程规范");

    DocSearchDocument result = mapper.toSearchDoc(doc, sourceFile, tags);

    assertThat(result).isNotNull();
    assertThat(result.getId()).isEqualTo("1001");
    assertThat(result.getWorkspaceId()).isEqualTo("10");
    assertThat(result.getTitle()).isEqualTo("设计规范文档");
    assertThat(result.getSummary()).isEqualTo("这是摘要");
    assertThat(result.getContent()).isEqualTo("# 详细正文");
    assertThat(result.getRawText()).isEqualTo("提取的纯文本内容");
    assertThat(result.getTags()).containsExactly("文档", "工程规范");
    assertThat(result.getCategoryId()).isEqualTo("20");
    assertThat(result.getSourceType()).isEqualTo("IMPORT");
    assertThat(result.getAuthorId()).isEqualTo("88");
    assertThat(result.getStatus()).isEqualTo("APPROVED");
    assertThat(result.getPublishedAt()).isEqualTo(updateTime);
    assertThat(result.getContentVector()).isNull();

    // 校验 afterMapping 自动填充 titleSuggest
    assertThat(result.getTitleSuggest()).isNotNull();
    assertThat(result.getTitleSuggest().getInput()).containsExactly("设计规范文档");
  }

  @Test
  @DisplayName("无源文件及无 updateTime 时应平滑降级映射")
  void testToSearchDocWithNullSourceFileAndNullUpdateTime() {
    DocumentEntity doc =
        DocumentEntity.createFromImport(11L, 0L, "测试无附件文档", null, "纯正文", WorkSpaceAclEnum.PUBLIC);
    doc.setId(1002L);
    LocalDateTime createTime = LocalDateTime.of(2026, 10, 1, 9, 30, 0);
    doc.setCreateTime(createTime);
    doc.setUpdateTime(null);

    DocSearchDocument result = mapper.toSearchDoc(doc, null);

    assertThat(result).isNotNull();
    assertThat(result.getId()).isEqualTo("1002");
    assertThat(result.getWorkspaceId()).isEqualTo("11");
    assertThat(result.getRawText()).isNull();
    assertThat(result.getTags()).isEmpty();
    assertThat(result.getPublishedAt()).isEqualTo(createTime);
    assertThat(result.getTitleSuggest()).isNotNull();
    assertThat(result.getTitleSuggest().getInput()).containsExactly("测试无附件文档");
  }
}
