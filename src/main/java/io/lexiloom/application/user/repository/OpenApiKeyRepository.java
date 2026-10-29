package io.lexiloom.application.user.repository;

import io.lexiloom.application.common.BaseRepository;
import io.lexiloom.application.user.model.entity.OpenApiKeyEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * OpenAPI 凭据持久化仓储
 */
@Repository
public interface OpenApiKeyRepository extends BaseRepository<OpenApiKeyEntity> {

  /** 根据完整真实 API Key 查询凭据 */
  Optional<OpenApiKeyEntity> findByApiKey(String apiKey);

  /** 查询指定用户拥有的所有 OpenAPI Key (按创建时间倒序) */
  List<OpenApiKeyEntity> findByUserIdOrderByCreateTimeDesc(Long userId);

  /** 检查用户下是否存在同名 Key */
  boolean existsByUserIdAndName(Long userId, String name);

  /**
   * 原子更新调用次数与最近使用时间，避免并发丢失更新 (Lost Update)
   */
  @Transactional
  @Modifying
  @Query(
      "UPDATE OpenApiKeyEntity e SET e.totalCalls = COALESCE(e.totalCalls, 0) + 1, e.lastUsedTime = :now WHERE e.apiKey = :apiKey")
  int incrementUsage(@Param("apiKey") String apiKey, @Param("now") LocalDateTime now);
}
