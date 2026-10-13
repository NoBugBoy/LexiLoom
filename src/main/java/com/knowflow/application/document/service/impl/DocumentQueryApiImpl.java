package com.knowflow.application.document.service.impl;

import com.knowflow.application.document.api.DocumentQueryApi;
import com.knowflow.application.document.api.dto.DocSourceFileDTO;
import com.knowflow.application.document.api.dto.DocumentDTO;
import com.knowflow.application.document.mapper.DocSourceFileMapper;
import com.knowflow.application.document.mapper.DocumentMapper;
import com.knowflow.application.document.model.entity.DocTagEntity;
import com.knowflow.application.document.model.entity.DocTagRelationEntity;
import com.knowflow.application.document.repository.DocSourceFileRepository;
import com.knowflow.application.document.repository.DocTagRelationRepository;
import com.knowflow.application.document.repository.DocTagRepository;
import com.knowflow.application.document.repository.DocumentRepository;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 文档模块跨模块查询服务实现 (DocumentQueryApiImpl) */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DocumentQueryApiImpl implements DocumentQueryApi {

  private final DocumentRepository documentRepository;
  private final DocSourceFileRepository docSourceFileRepository;
  private final DocTagRelationRepository docTagRelationRepository;
  private final DocTagRepository docTagRepository;
  private final DocumentMapper documentMapper;
  private final DocSourceFileMapper docSourceFileMapper;

  @Override
  public Optional<DocumentDTO> findDocumentById(Long documentId) {
    if (documentId == null) {
      return Optional.empty();
    }
    return documentRepository.findById(documentId).map(documentMapper::toDTO);
  }

  @Override
  public Optional<DocSourceFileDTO> findSourceFileById(Long sourceFileId) {
    if (sourceFileId == null) {
      return Optional.empty();
    }
    return docSourceFileRepository.findById(sourceFileId).map(docSourceFileMapper::toDTO);
  }

  @Override
  public Optional<DocSourceFileDTO> findSourceFileByDocumentId(Long documentId) {
    if (documentId == null) {
      return Optional.empty();
    }
    return docSourceFileRepository.findByDocumentId(documentId).map(docSourceFileMapper::toDTO);
  }

  @Override
  public List<String> findTagNamesByDocumentId(Long documentId) {
    if (documentId == null) {
      return Collections.emptyList();
    }
    try {
      List<DocTagRelationEntity> relations = docTagRelationRepository.findByDocumentId(documentId);
      if (relations == null || relations.isEmpty()) {
        return Collections.emptyList();
      }
      List<Long> tagIds = relations.stream().map(DocTagRelationEntity::getTagId).toList();
      return docTagRepository.findAllById(tagIds).stream().map(DocTagEntity::getName).toList();
    } catch (Exception e) {
      log.warn("查询文档标签失败: documentId={}, error={}", documentId, e.getMessage());
      return Collections.emptyList();
    }
  }
}
