package io.lexiloom.application.ai.trace.repository;

import io.lexiloom.application.ai.trace.entity.RagTraceDetailEntity;
import io.lexiloom.application.common.BaseRepository;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** RAG 观测明细表仓储 */
@Repository
public interface RagTraceDetailRepository extends BaseRepository<RagTraceDetailEntity> {

  Optional<RagTraceDetailEntity> findByTraceId(String traceId);
}
