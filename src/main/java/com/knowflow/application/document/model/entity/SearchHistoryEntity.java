package com.knowflow.application.document.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
    name = "kf_search_history",
    comment = "搜索历史归档表",
    indexes = {@Index(name = "idx_search_history_user", columnList = "user_id, search_at")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SearchHistoryEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(comment = "主键")
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "work_space_id", nullable = false)
  private Long workspaceId;

  @Column(nullable = false, length = 200)
  private String keyword;

  @Column(name = "search_at", nullable = false)
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
    return entity;
  }
}
