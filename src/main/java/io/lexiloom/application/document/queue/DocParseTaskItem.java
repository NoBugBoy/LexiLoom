package io.lexiloom.application.document.queue;

import io.lexiloom.application.document.parser.DocParserEngineEnum;
import java.io.Serializable;

/**
 * DeepDoc 文档解析分布式排队任务载荷
 */
public record DocParseTaskItem(
    Long documentId,
    Long sourceFileId,
    Long workspaceId,
    Long operatorId,
    DocParserEngineEnum engine,
    String fileName,
    String storagePath,
    String mimeType,
    long createTime
) implements Serializable {}
