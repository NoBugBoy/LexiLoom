package io.lexiloom.application.lookup.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.querydsl.jpa.impl.JPAQueryFactory;
import io.lexiloom.application.exception.BusinessException;
import io.lexiloom.application.lookup.api.dto.SectDTO;
import io.lexiloom.application.lookup.mapper.SectMapper;
import io.lexiloom.application.lookup.model.entity.SectEntity;
import io.lexiloom.application.lookup.model.request.SectPageRequest;
import io.lexiloom.application.lookup.model.request.SectRequest;
import io.lexiloom.application.lookup.model.response.SectPageResponse;
import io.lexiloom.application.lookup.repository.SectRepository;
import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

@ExtendWith(MockitoExtension.class)
class SectServiceImplTest {

  @Mock private SectRepository sectRepository;

  @Mock private SectMapper sectMapper;

  @Mock private JPAQueryFactory queryFactory;

  @InjectMocks private SectServiceImpl service;

  @Test
  @DisplayName("分页查询 - 无条件")
  void pageWithoutCondition() {
    SectPageRequest request = new SectPageRequest();
    SectEntity entity = new SectEntity();
    entity.setId(1L);
    entity.setSectName("Sect 1");

    when(sectRepository.findAll(any(org.springframework.data.domain.Pageable.class)))
        .thenReturn(new PageImpl<>(Collections.singletonList(entity)));

    Page<SectPageResponse> result = service.page(request);

    assertThat(result.getContent()).hasSize(1);
    assertThat(result.getContent().get(0).sectId()).isEqualTo(1L);
    assertThat(result.getContent().get(0).sectName()).isEqualTo("Sect 1");
  }

  @Test
  @DisplayName("分页查询 - 有条件")
  void pageWithCondition() {
    SectPageRequest request = new SectPageRequest();
    request.setSectName("Sect 1");
    when(sectRepository.findAll(
            any(com.querydsl.core.types.Predicate.class),
            any(org.springframework.data.domain.Pageable.class)))
        .thenReturn(new PageImpl<>(Collections.emptyList()));

    Page<SectPageResponse> result = service.page(request);
    assertThat(result).isNotNull();
  }

  @Test
  @DisplayName("删除门派")
  void deleteById() {
    service.deleteById(1L);
    verify(sectRepository).deleteById(1L);
  }

  @Test
  @DisplayName("创建门派 - 名称重复")
  void createDuplicate() {
    SectRequest request = new SectRequest("Sect 1");
    when(sectRepository.existsBySectName("Sect 1")).thenReturn(true);

    assertThatThrownBy(() -> service.createSect(request))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("该门派已经存在");
  }

  @Test
  @DisplayName("创建门派 - 成功")
  void createSuccess() {
    SectRequest request = new SectRequest("Sect 1");
    when(sectRepository.existsBySectName("Sect 1")).thenReturn(false);
    SectEntity entity = new SectEntity();
    when(sectMapper.toSectEntity("Sect 1")).thenReturn(entity);

    service.createSect(request);

    verify(sectRepository).save(entity);
  }

  @Test
  @DisplayName("更新门派 - 数据不存在")
  void updateNotFound() {
    when(sectRepository.findById(1L)).thenReturn(Optional.empty());

    SectRequest updateRequest = new SectRequest("Sect 2");
    assertThatThrownBy(() -> service.update(1L, updateRequest))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("该数据已经被删除");
  }

  @Test
  @DisplayName("更新门派 - 成功")
  void updateSuccess() {
    SectEntity entity = new SectEntity();
    entity.setSectName("Sect 1");
    when(sectRepository.findById(1L)).thenReturn(Optional.of(entity));

    service.update(1L, new SectRequest("Sect 2"));

    assertThat(entity.getSectName()).isEqualTo("Sect 2");
    verify(sectRepository).saveAndFlush(entity);
  }

  @Test
  @DisplayName("查询单个门派 - 存在")
  void getByIdExist() {
    SectEntity entity = new SectEntity();
    entity.setId(1L);
    entity.setSectName("Sect 1");
    when(sectRepository.findById(1L)).thenReturn(Optional.of(entity));

    Optional<SectDTO> result = service.getById(1L);

    assertThat(result).isPresent();
    assertThat(result.get().sectId()).isEqualTo(1L);
    assertThat(result.get().sectName()).isEqualTo("Sect 1");
  }
}
