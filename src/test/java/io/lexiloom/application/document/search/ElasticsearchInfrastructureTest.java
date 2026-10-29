package io.lexiloom.application.document.search;

import static org.assertj.core.api.Assertions.assertThat;

import io.lexiloom.application.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

@DisplayName("Elasticsearch 基础设施与配置测试")
class ElasticsearchInfrastructureTest {

  @Test
  @DisplayName("ElasticsearchProperties 默认值与 Setter 行为校验")
  void testElasticsearchPropertiesDefaultsAndMutators() {
    var properties = new ElasticsearchProperties();

    // 默认值校验
    assertThat(properties.getIndexName()).isEqualTo("lexiloom_doc_index");
    assertThat(properties.isAutoCreateIndex()).isTrue();

    // 修改值校验
    properties.setIndexName("custom_doc_index");
    properties.setAutoCreateIndex(false);

    assertThat(properties.getIndexName()).isEqualTo("custom_doc_index");
    assertThat(properties.isAutoCreateIndex()).isFalse();
  }

  @Test
  @DisplayName("ErrorCode Document 枚举中 ES 相关错误码定义校验")
  void testElasticsearchErrorCodes() {
    ErrorCode.Document indexFailed = ErrorCode.Document.ES_INDEX_FAILED;
    assertThat(indexFailed.getCode()).isEqualTo("ES_INDEX_FAILED");
    assertThat(indexFailed.getMessage()).isEqualTo("ES 索引写入失败");
    assertThat(indexFailed.getHttpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

    ErrorCode.Document searchFailed = ErrorCode.Document.ES_SEARCH_FAILED;
    assertThat(searchFailed.getCode()).isEqualTo("ES_SEARCH_FAILED");
    assertThat(searchFailed.getMessage()).isEqualTo("ES 检索执行失败");
    assertThat(searchFailed.getHttpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
  }
}
