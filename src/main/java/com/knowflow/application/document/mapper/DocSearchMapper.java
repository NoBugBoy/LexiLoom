package com.knowflow.application.document.mapper;

import com.knowflow.application.document.model.entity.DocSourceFileEntity;
import com.knowflow.application.document.model.entity.DocumentEntity;
import com.knowflow.application.document.search.DocSearchDocument;
import java.util.Collections;
import java.util.List;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

/** Elasticsearch 核心文档检索实体 MapStruct 映射器 */
@Mapper
public interface DocSearchMapper {

  @Mapping(source = "doc.id", target = "id")
  @Mapping(source = "doc.workSpaceId", target = "workspaceId")
  @Mapping(source = "doc.title", target = "title")
  @Mapping(source = "doc.summary", target = "summary")
  @Mapping(source = "doc.content", target = "content")
  @Mapping(source = "sourceFile.rawText", target = "rawText")
  @Mapping(source = "tags", target = "tags")
  @Mapping(source = "doc.categoryId", target = "categoryId")
  @Mapping(source = "doc.sourceType", target = "sourceType")
  @Mapping(source = "doc.createBy", target = "authorId")
  @Mapping(source = "doc.status", target = "status")
  @Mapping(source = "doc.updateTime", target = "publishedAt")
  @Mapping(target = "titleSuggest", ignore = true)
  @Mapping(target = "contentVector", ignore = true)
  DocSearchDocument toSearchDoc(
      DocumentEntity doc, DocSourceFileEntity sourceFile, List<String> tags);

  default DocSearchDocument toSearchDoc(DocumentEntity doc, DocSourceFileEntity sourceFile) {
    return toSearchDoc(doc, sourceFile, Collections.emptyList());
  }

  @AfterMapping
  default void afterMapping(DocumentEntity doc, @MappingTarget DocSearchDocument target) {
    if (doc != null) {
      if (doc.getTitle() != null && !doc.getTitle().isBlank()) {
        target.initTitleSuggest(doc.getTitle());
      }
      if (target.getPublishedAt() == null) {
        target.setPublishedAt(
            doc.getCreateTime() != null ? doc.getCreateTime() : java.time.LocalDateTime.now());
      }
    }
  }
}
