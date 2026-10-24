package io.lexiloom.application.document.model.entity;

import io.lexiloom.application.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * 搜索历史归档实体 (不可变审计记录)
 *
 * <p>遵循领域建模不可变快照原则，通过 {@link Immutable} 保证在持久层拒绝 UPDATE 操作。
 */
@Entity
@Immutable
@Table(
    name = "ll_search_history",
    comment = "搜索历史归档表",
    indexes = {@Index(name = "idx_search_history_user", columnList = "user_id, search_at")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SearchHistoryEntity extends BaseEntity {

  /** 搜索用户 ID */
  @Column(name = "user_id", comment = "用户ID", nullable = false)
  private Long userId;

  /** 所属工作空间 ID */
  @Column(name = "work_space_id", comment = "工作空间ID", nullable = false)
  private Long workspaceId;

  /** 搜索关键词 */
  @Column(comment = "搜索关键词", nullable = false, length = 200)
  private String keyword;

  /** 检索发生时间戳 */
  @Column(name = "search_at", comment = "搜索时间", nullable = false)
  private LocalDateTime searchAt;

  /** 静态工厂方法 */
  public static SearchHistoryEntity of(Long userId, Long workspaceId, String keyword) {
    return of(userId, workspaceId, keyword, LocalDateTime.now());
  }

  /** 静态工厂方法 (支持显式时间戳) */
  public static SearchHistoryEntity of(
      Long userId, Long workspaceId, String keyword, LocalDateTime searchAt) {
    Objects.requireNonNull(userId, "用户ID不能为空");
    Objects.requireNonNull(workspaceId, "工作区ID不能为空");
    Objects.requireNonNull(keyword, "搜索关键词不能为空");

    SearchHistoryEntity entity = new SearchHistoryEntity();
    entity.userId = userId;
    entity.workspaceId = workspaceId;
    entity.keyword = keyword.trim();
    entity.searchAt = searchAt != null ? searchAt : LocalDateTime.now();
    entity.setCreateTime(entity.searchAt);
    entity.setUpdateTime(entity.searchAt);
    entity.setCreateBy(userId);
    entity.setUpdateBy(userId);
    return entity;
  }
}
