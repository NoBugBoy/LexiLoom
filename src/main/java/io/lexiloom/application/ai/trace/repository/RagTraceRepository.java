package io.lexiloom.application.ai.trace.repository;

import io.lexiloom.application.ai.trace.entity.RagTraceEntity;
import io.lexiloom.application.common.BaseRepository;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** RAG 观测主表仓储 */
@Repository
public interface RagTraceRepository extends BaseRepository<RagTraceEntity> {

  Optional<RagTraceEntity> findByTraceId(String traceId);

  boolean existsByTraceId(String traceId);
}
