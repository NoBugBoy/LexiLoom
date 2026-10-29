package io.lexiloom.application.document.model.enums;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lexiloom.application.document.model.entity.DocumentEntity;
import io.lexiloom.application.enums.WorkSpaceAclEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("DocSourceTypeEnum 充血模型与容错反序列化测试")
class DocSourceTypeEnumTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("领域谓词校验：正确识别物理文件驱动与生成渠道")
  void testDomainPredicates() {
    assertThat(DocSourceTypeEnum.IMPORT.isFileBased()).isTrue();
    assertThat(DocSourceTypeEnum.IMPORT.isAiGenerated()).isFalse();
    assertThat(DocSourceTypeEnum.IMPORT.isManual()).isFalse();

    assertThat(DocSourceTypeEnum.AI_GENERATED.isFileBased()).isFalse();
    assertThat(DocSourceTypeEnum.AI_GENERATED.isAiGenerated()).isTrue();
    assertThat(DocSourceTypeEnum.AI_GENERATED.isManual()).isFalse();

    assertThat(DocSourceTypeEnum.MANUAL.isFileBased()).isFalse();
    assertThat(DocSourceTypeEnum.MANUAL.isAiGenerated()).isFalse();
    assertThat(DocSourceTypeEnum.MANUAL.isManual()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"IMPORT", "import", " Import ", "文件导入"})
  @DisplayName("容错解析：不同大小写与中文别名均解析为 IMPORT")
  void testFromValue_ImportVariants(String input) {
    assertThat(DocSourceTypeEnum.fromValue(input)).isEqualTo(DocSourceTypeEnum.IMPORT);
    assertThat(DocSourceTypeEnum.ofNullable(input)).contains(DocSourceTypeEnum.IMPORT);
  }

  @ParameterizedTest
  @ValueSource(strings = {"AI_GENERATED", "ai_generated", " Aigc ", "AI生成"})
  @DisplayName("容错解析：不同大小写与中文别名均解析为 AI_GENERATED")
  void testFromValue_AiVariants(String input) {
    assertThat(DocSourceTypeEnum.fromValue(input)).isEqualTo(DocSourceTypeEnum.AI_GENERATED);
    assertThat(DocSourceTypeEnum.ofNullable(input)).contains(DocSourceTypeEnum.AI_GENERATED);
  }

  @ParameterizedTest
  @ValueSource(strings = {"MANUAL", "manual", " 手动创建 ", "手工新建"})
  @DisplayName("容错解析：不同大小写与中文别名均解析为 MANUAL")
  void testFromValue_ManualVariants(String input) {
    assertThat(DocSourceTypeEnum.fromValue(input)).isEqualTo(DocSourceTypeEnum.MANUAL);
    assertThat(DocSourceTypeEnum.ofNullable(input)).contains(DocSourceTypeEnum.MANUAL);
  }

  @Test
  @DisplayName("容错与兜底：空字符串或非法值安全返回 null 或默认回退")
  void testFromValue_NullAndDefaults() {
    assertThat(DocSourceTypeEnum.fromValue(null)).isNull();
    assertThat(DocSourceTypeEnum.fromValue("   ")).isNull();
    assertThat(DocSourceTypeEnum.fromValue("UNKNOWN_SOURCE")).isNull();

    assertThat(DocSourceTypeEnum.ofNullable(null)).isEmpty();
    assertThat(DocSourceTypeEnum.ofNullable("")).isEmpty();

    assertThat(DocSourceTypeEnum.ofOrDefault(null, DocSourceTypeEnum.IMPORT))
        .isEqualTo(DocSourceTypeEnum.IMPORT);
    assertThat(DocSourceTypeEnum.ofOrDefault("unknown", DocSourceTypeEnum.MANUAL))
        .isEqualTo(DocSourceTypeEnum.MANUAL);
  }

  @Test
  @DisplayName("Jackson 序列化与反序列化测试")
  void testJacksonSerialization() throws Exception {
    String json = objectMapper.writeValueAsString(DocSourceTypeEnum.IMPORT);
    assertThat(json).isEqualTo("\"IMPORT\"");

    DocSourceTypeEnum deserializedLower =
        objectMapper.readValue("\"import\"", DocSourceTypeEnum.class);
    assertThat(deserializedLower).isEqualTo(DocSourceTypeEnum.IMPORT);

    DocSourceTypeEnum deserializedCn =
        objectMapper.readValue("\"AI生成\"", DocSourceTypeEnum.class);
    assertThat(deserializedCn).isEqualTo(DocSourceTypeEnum.AI_GENERATED);
  }

  @Test
  @DisplayName("聚合根协同测试：createManual 与 isFileBased 行为一致")
  void testDocumentEntityManualCreation() {
    DocumentEntity doc =
        DocumentEntity.createManual(
            1L, 0L, "在线新建文档", "摘要", "# 正文", WorkSpaceAclEnum.PUBLIC);

    assertThat(doc.getSourceType()).isEqualTo(DocSourceTypeEnum.MANUAL);
    assertThat(doc.isFileBased()).isFalse();
    assertThat(doc.getSourceFileId()).isNull();
  }
}
