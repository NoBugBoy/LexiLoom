package io.lexiloom.application.user.repository;

import io.lexiloom.application.common.BaseRepository;
import io.lexiloom.application.user.model.entity.OpenApiKeyEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

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
}
