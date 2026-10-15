package io.lexiloom.application.config;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Spring Modulith 事件发布归档表自愈升级组件
 *
 * <p>Spring Modulith 默认实体 {@code DefaultJpaEventPublication} 将 {@code serialized_event} 映射为普通
 * String，导致在 JPA 自动建表时生成为 {@code character varying(255)}。
 * 当知识库文档等大体积领域事件（如包含全文切片、Markdown 摘要等）发生状态机流转时，极易发生长度溢出异常。
 * 本组件在系统启动就绪时自动检测该字段，并自愈升级为 {@code text} 大文本类型。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventPublicationSchemaInitializer implements ApplicationRunner {

  private final JdbcTemplate jdbcTemplate;

  @Override
  public void run(ApplicationArguments args) {
    try {
      upgradeSerializedEventColumn();
    } catch (Exception e) {
      log.warn("【EventPublicationSchemaInitializer】检查或自愈升级 event_publication 表结构异常: {}", e.getMessage());
    }
  }

  private void upgradeSerializedEventColumn() {
    List<String> types =
        jdbcTemplate.queryForList(
            "SELECT data_type FROM information_schema.columns WHERE table_name = 'event_publication' AND column_name = 'serialized_event'",
            String.class);

    if (types.isEmpty()) {
      return;
    }

    String currentType = types.getFirst();
    if (!"text".equalsIgnoreCase(currentType)) {
      log.info(
          "【EventPublicationSchemaInitializer】检测到 event_publication.serialized_event 当前数据类型为 [{}]，正在自愈升级为 text 大文本类型...",
          currentType);
      jdbcTemplate.execute("ALTER TABLE event_publication ALTER COLUMN serialized_event TYPE text");
      log.info("【EventPublicationSchemaInitializer】event_publication.serialized_event 字段类型已成功自愈升级为 text");
    }
  }
}
