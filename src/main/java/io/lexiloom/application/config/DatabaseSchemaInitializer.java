package io.lexiloom.application.config;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 数据库表结构自愈与迁移组件
 *
 * <p>核心职责： 1. 自动检测并升级 Spring Modulith 的 {@code event_publication.serialized_event} 字段为 {@code text}
 * 大文本类型，防止大体积事件截断报错。 2. 自动初始化/检查 {@code ll_admin_api_key} 管理员全局 API 密钥表及其唯一索引。 3. 检查 {@code
 * ll_ai_rag_trace} Token 审计列自愈。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseSchemaInitializer implements ApplicationRunner {

  private final JdbcTemplate jdbcTemplate;

  @Override
  public void run(ApplicationArguments args) {
    upgradeSerializedEventColumn();
    upgradeRagTraceColumns();
    upgradeDocumentEmbeddingTokenColumn();
  }

  /** 为文档表补齐向量化 Token 累计列，兼容已有数据库。 */
  private void upgradeDocumentEmbeddingTokenColumn() {
    try {
      List<String> tables =
          jdbcTemplate.queryForList(
              "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND"
                  + " table_name = 'll_document'",
              String.class);
      if (!tables.isEmpty()) {
        jdbcTemplate.execute(
            "ALTER TABLE ll_document ADD COLUMN IF NOT EXISTS embedding_tokens INT NOT NULL DEFAULT 0");
      }
    } catch (Exception e) {
      log.warn("【DatabaseSchemaInitializer】检查或升级 ll_document.embedding_tokens 异常: {}", e.getMessage());
    }
  }

  /** 确保 ll_ai_rag_trace 表自愈对齐真实 Token 消耗审计字段 */
  private void upgradeRagTraceColumns() {
    try {
      List<String> tables =
          jdbcTemplate.queryForList(
              "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND"
                  + " table_name = 'll_ai_rag_trace'",
              String.class);
      if (tables.isEmpty()) {
        return;
      }
      jdbcTemplate.execute(
          "ALTER TABLE ll_ai_rag_trace ADD COLUMN IF NOT EXISTS prompt_tokens INT DEFAULT 0");
      jdbcTemplate.execute(
          "ALTER TABLE ll_ai_rag_trace ADD COLUMN IF NOT EXISTS completion_tokens INT DEFAULT 0");
      jdbcTemplate.execute(
          "ALTER TABLE ll_ai_rag_trace ADD COLUMN IF NOT EXISTS embedding_tokens INT DEFAULT 0");
      jdbcTemplate.execute(
          "ALTER TABLE ll_ai_rag_trace ADD COLUMN IF NOT EXISTS rerank_tokens INT DEFAULT 0");
      jdbcTemplate.execute(
          "ALTER TABLE ll_ai_rag_trace ADD COLUMN IF NOT EXISTS total_tokens INT DEFAULT 0");
      jdbcTemplate.execute(
          "ALTER TABLE ll_ai_rag_trace ADD COLUMN IF NOT EXISTS call_source VARCHAR(32) DEFAULT 'WEB_CHAT'");
      jdbcTemplate.execute(
          "ALTER TABLE ll_ai_rag_trace ADD COLUMN IF NOT EXISTS api_key VARCHAR(128)");
      log.info("【DatabaseSchemaInitializer】ll_ai_rag_trace 表 Token 审计、调用来源及 API Key 列自愈检查完成");
    } catch (Exception e) {
      log.warn("【DatabaseSchemaInitializer】检查或升级 ll_ai_rag_trace 表结构异常: {}", e.getMessage());
    }
  }

  /** 自动升级 event_publication.serialized_event 为 text 大文本类型 */
  private void upgradeSerializedEventColumn() {
    try {
      List<String> types =
          jdbcTemplate.queryForList(
              "SELECT data_type FROM information_schema.columns WHERE table_name ="
                  + " 'event_publication' AND column_name = 'serialized_event'",
              String.class);

      if (types.isEmpty()) {
        return;
      }

      String currentType = types.getFirst();
      if (!"text".equalsIgnoreCase(currentType)) {
        log.info(
            "【DatabaseSchemaInitializer】检测到 event_publication.serialized_event 当前数据类型为 [{}]，正在自愈升级为"
                + " text 大文本类型...",
            currentType);
        jdbcTemplate.execute(
            "ALTER TABLE event_publication ALTER COLUMN serialized_event TYPE text");
        log.info("【DatabaseSchemaInitializer】event_publication.serialized_event 字段类型已成功自愈升级为 text");
      }
    } catch (Exception e) {
      log.warn("【DatabaseSchemaInitializer】检查或自愈升级 event_publication 表结构异常: {}", e.getMessage());
    }
  }
}
