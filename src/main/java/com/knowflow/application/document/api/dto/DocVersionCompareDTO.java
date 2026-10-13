package com.knowflow.application.document.api.dto;

/**
 * 文档版本内容比对数据传输对象 (不可变 record) 遵循现代 Web 架构最佳实践：Diff 运算与高亮渲染交由前端（如 Monaco Diff Editor /
 * react-diff-viewer）完成， 后端仅提供纯净的原文本对比契约，大幅释放服务器 CPU 负载并提升前端交互灵活性。
 */
public record DocVersionCompareDTO(
    Long documentId,
    Integer oldVersionNumber,
    String oldVersionTag,
    String oldContent,
    Integer newVersionNumber,
    String newVersionTag,
    String newContent) {}
