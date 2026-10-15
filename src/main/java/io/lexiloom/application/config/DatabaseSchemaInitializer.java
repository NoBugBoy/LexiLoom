package io.lexiloom.application.config;

import java.util.List;
import java.util.Map;
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
 * 大文本类型，防止大体积事件截断报错。 2. 自动迁移/清洗历史表前缀更名后残留的旧约束（如 {@code kf_*_pkey} 重命名为 {@code ll_*_pkey}，清理过时的枚举
 * Check 约束 {@code kf_*_check}）， 防止历史遗留的静态 Check 约束在枚举演进或数据维护时引发 PostgreSQL 23514 错误。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseSchemaInitializer implements ApplicationRunner {

  private final JdbcTemplate jdbcTemplate;

  @Override
  public void run(ApplicationArguments args) {
    upgradeSerializedEventColumn();
    migrateLegacyConstraints();
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

  /** 自动迁移或清理带有旧前缀 kf_ 的主键约束与历史 Check 约束 */
  private void migrateLegacyConstraints() {
    try {
      List<Map<String, Object>> rows =
          jdbcTemplate.queryForList(
              "SELECT table_name, constraint_name, constraint_type "
                  + "FROM information_schema.table_constraints "
                  + "WHERE table_schema = 'public' AND constraint_name LIKE 'kf_%'");

      if (rows.isEmpty()) {
        return;
      }

      for (Map<String, Object> row : rows) {
        String tableName = String.valueOf(row.get("table_name"));
        String constraintName = String.valueOf(row.get("constraint_name"));
        String constraintType = String.valueOf(row.get("constraint_type"));

        // 仅处理合法的 SQL 标识符，防止注入
        if (!tableName.matches("^[a-zA-Z0-9_]+$") || !constraintName.matches("^[a-zA-Z0-9_]+$")) {
          continue;
        }

        if ("PRIMARY KEY".equalsIgnoreCase(constraintType)) {
          String newName = constraintName.replaceFirst("^kf_", "ll_");
          jdbcTemplate.execute(
              String.format(
                  "ALTER TABLE %s RENAME CONSTRAINT %s TO %s", tableName, constraintName, newName));
          log.info("【DatabaseSchemaInitializer】已成功将主键约束重命名: {} -> {}", constraintName, newName);
        } else if ("CHECK".equalsIgnoreCase(constraintType)) {
          jdbcTemplate.execute(
              String.format(
                  "ALTER TABLE %s DROP CONSTRAINT IF EXISTS %s", tableName, constraintName));
          log.info(
              "【DatabaseSchemaInitializer】已移除历史遗留 Check 约束: {} (表: {})", constraintName, tableName);
        }
      }
    } catch (Exception e) {
      log.warn("【DatabaseSchemaInitializer】检查或迁移历史表约束异常: {}", e.getMessage());
    }
  }
}
