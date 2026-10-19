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
    migrateSpaceLinkTable();
    initHistoricalDocumentApprovals();
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

  /** 自动检测并对齐 ll_workspace_space_link 表结构，补齐 BaseEntity 审计与软删除字段并清理旧字段 */
  private void migrateSpaceLinkTable() {
    try {
      List<String> tables =
          jdbcTemplate.queryForList(
              "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND"
                  + " table_name = 'll_workspace_space_link'",
              String.class);
      if (tables.isEmpty()) {
        return;
      }

      // 1. 确保 BaseEntity 所需的审计与软删除字段存在
      jdbcTemplate.execute(
          "ALTER TABLE ll_workspace_space_link ADD COLUMN IF NOT EXISTS create_by BIGINT");
      jdbcTemplate.execute(
          "ALTER TABLE ll_workspace_space_link ADD COLUMN IF NOT EXISTS create_time TIMESTAMP"
              + " WITHOUT TIME ZONE");
      jdbcTemplate.execute(
          "ALTER TABLE ll_workspace_space_link ADD COLUMN IF NOT EXISTS update_by BIGINT");
      jdbcTemplate.execute(
          "ALTER TABLE ll_workspace_space_link ADD COLUMN IF NOT EXISTS update_time TIMESTAMP"
              + " WITHOUT TIME ZONE");
      jdbcTemplate.execute(
          "ALTER TABLE ll_workspace_space_link ADD COLUMN IF NOT EXISTS del_flag BIGINT NOT NULL"
              + " DEFAULT 0");

      // 2. 如果存在旧的 created_at / updated_at 字段，同步数据并清理
      List<String> columns =
          jdbcTemplate.queryForList(
              "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND"
                  + " table_name = 'll_workspace_space_link'",
              String.class);

      if (columns.contains("created_at")) {
        jdbcTemplate.execute(
            "UPDATE ll_workspace_space_link SET create_time = created_at WHERE create_time IS"
                + " NULL");
        jdbcTemplate.execute(
            "ALTER TABLE ll_workspace_space_link DROP COLUMN IF EXISTS created_at");
      }
      if (columns.contains("updated_at")) {
        jdbcTemplate.execute(
            "UPDATE ll_workspace_space_link SET update_time = updated_at WHERE update_time IS"
                + " NULL");
        jdbcTemplate.execute(
            "ALTER TABLE ll_workspace_space_link DROP COLUMN IF EXISTS updated_at");
      }

      // 3. 清理已收敛至 ll_approval_record 的历史遗留字段（或解除非空约束）
      if (columns.contains("applicant_id")) {
        jdbcTemplate.execute(
            "ALTER TABLE ll_workspace_space_link DROP COLUMN IF EXISTS applicant_id");
      }
      if (columns.contains("approver_id")) {
        jdbcTemplate.execute(
            "ALTER TABLE ll_workspace_space_link DROP COLUMN IF EXISTS approver_id");
      }
      if (columns.contains("remark")) {
        jdbcTemplate.execute("ALTER TABLE ll_workspace_space_link DROP COLUMN IF EXISTS remark");
      }

      log.info("【DatabaseSchemaInitializer】ll_workspace_space_link 表结构自愈检查与迁移完成");
    } catch (Exception e) {
      log.warn(
          "【DatabaseSchemaInitializer】检查或迁移 ll_workspace_space_link 表结构异常: {}", e.getMessage());
    }
  }

  /** 自动将历史已发布的存量文档补充同步至统一审批流记录表 (ll_approval_record) */
  private void initHistoricalDocumentApprovals() {
    try {
      List<String> tables =
          jdbcTemplate.queryForList(
              "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND"
                  + " table_name IN ('ll_document', 'll_approval_record')",
              String.class);
      if (tables.size() < 2) {
        return;
      }

      int inserted =
          jdbcTemplate.update(
              "INSERT INTO ll_approval_record ("
                  + "approval_type, biz_id, workspace_id, target_workspace_id, "
                  + "applicant_id, applicant_name, apply_reason, status, "
                  + "approver_id, approver_name, approval_comment, approval_time, "
                  + "extra_data, create_time, update_time, create_by, update_by, del_flag) "
                  + "SELECT "
                  + "'DOCUMENT_AUDIT' AS approval_type, "
                  + "d.id AS biz_id, "
                  + "d.work_space_id AS workspace_id, "
                  + "d.work_space_id AS target_workspace_id, "
                  + "COALESCE(d.create_by, 1) AS applicant_id, "
                  + "'admin@knowflow.com' AS applicant_name, "
                  + "'历史文档数据迁移' AS apply_reason, "
                  + "'APPROVED' AS status, "
                  + "1 AS approver_id, "
                  + "'admin@knowflow.com' AS approver_name, "
                  + "'历史文档默认审批通过' AS approval_comment, "
                  + "COALESCE(d.update_time, d.create_time, CURRENT_TIMESTAMP) AS approval_time, "
                  + "NULL AS extra_data, "
                  + "COALESCE(d.create_time, CURRENT_TIMESTAMP) AS create_time, "
                  + "COALESCE(d.update_time, d.create_time, CURRENT_TIMESTAMP) AS update_time, "
                  + "COALESCE(d.create_by, 1) AS create_by, "
                  + "1 AS update_by, "
                  + "0 AS del_flag "
                  + "FROM ll_document d "
                  + "WHERE d.del_flag = 0 "
                  + "  AND d.status = 'APPROVED' "
                  + "  AND NOT EXISTS ("
                  + "      SELECT 1 FROM ll_approval_record a "
                  + "      WHERE a.approval_type = 'DOCUMENT_AUDIT' AND a.biz_id = d.id"
                  + "  )");

      if (inserted > 0) {
        log.info(
            "【DatabaseSchemaInitializer】已自动将 {} 篇历史已发布文档同步至审批记录表 (ll_approval_record)", inserted);
      }
    } catch (Exception e) {
      log.warn("【DatabaseSchemaInitializer】同步历史文档审批记录异常: {}", e.getMessage());
    }
  }
}
