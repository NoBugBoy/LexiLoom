package io.lexiloom.application.lookup.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.querydsl.jpa.impl.JPAQueryFactory;
import io.lexiloom.application.exception.BusinessException;
import io.lexiloom.application.lookup.api.dto.ServerDTO;
import io.lexiloom.application.lookup.mapper.GameServerMapper;
import io.lexiloom.application.lookup.model.entity.GameServerEntity;
import io.lexiloom.application.lookup.model.request.GameServerPageRequest;
import io.lexiloom.application.lookup.model.request.GameServerRequest;
import io.lexiloom.application.lookup.model.response.GameSeverPageResponse;
import io.lexiloom.application.lookup.repository.GameServerRepository;
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
class GameServerServiceImplTest {

  @Mock private GameServerRepository repository;

  @Mock private GameServerMapper gameServerMapper;

  @Mock private JPAQueryFactory queryFactory;

  @InjectMocks private GameServerServiceImpl service;

  @Test
  @DisplayName("分页查询 - 无条件")
  void pageWithoutCondition() {
    GameServerPageRequest request = new GameServerPageRequest();
    GameServerEntity entity = new GameServerEntity();
    entity.setId(1L);
    entity.setServerName("Server 1");

    when(repository.findAll(any(org.springframework.data.domain.Pageable.class)))
        .thenReturn(new PageImpl<>(Collections.singletonList(entity)));

    Page<GameSeverPageResponse> result = service.page(request);

    assertThat(result.getContent()).hasSize(1);
    assertThat(result.getContent().get(0).serverId()).isEqualTo(1L);
    assertThat(result.getContent().get(0).serverName()).isEqualTo("Server 1");
  }

  @Test
  @DisplayName("分页查询 - 有条件")
  void pageWithCondition() {
    GameServerPageRequest request = new GameServerPageRequest();
    request.setGameServerName("Server 1");
    when(repository.findAll(
            any(com.querydsl.core.types.Predicate.class),
            any(org.springframework.data.domain.Pageable.class)))
        .thenReturn(new PageImpl<>(Collections.emptyList()));

    Page<GameSeverPageResponse> result = service.page(request);
    assertThat(result).isNotNull();
  }

  @Test
  @DisplayName("创建服务器 - 名称重复")
  void createDuplicate() {
    GameServerRequest request = new GameServerRequest("Server 1");
    when(repository.existsByServerName("Server 1")).thenReturn(true);

    assertThatThrownBy(() -> service.create(request))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("该服务器名称已经存在");
  }

  @Test
  @DisplayName("创建服务器 - 成功")
  void createSuccess() {
    GameServerRequest request = new GameServerRequest("Server 1");
    when(repository.existsByServerName("Server 1")).thenReturn(false);
    GameServerEntity entity = new GameServerEntity();
    when(gameServerMapper.toEntity("Server 1")).thenReturn(entity);

    service.create(request);

    verify(repository).save(entity);
  }

  @Test
  @DisplayName("删除服务器")
  void deleteById() {
    service.deleteById(1L);
    verify(repository).deleteById(1L);
  }

  @Test
  @DisplayName("更新服务器 - 数据不存在")
  void updateNotFound() {
    when(repository.findById(1L)).thenReturn(Optional.empty());

    GameServerRequest updateRequest = new GameServerRequest("Server 2");
    assertThatThrownBy(() -> service.update(1L, updateRequest))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("该数据已经被删除");
  }

  @Test
  @DisplayName("更新服务器 - 成功")
  void updateSuccess() {
    GameServerEntity entity = new GameServerEntity();
    entity.setServerName("Server 1");
    when(repository.findById(1L)).thenReturn(Optional.of(entity));

    service.update(1L, new GameServerRequest("Server 2"));

    assertThat(entity.getServerName()).isEqualTo("Server 2");
  }

  @Test
  @DisplayName("查询单个服务器 - 存在")
  void getByIdExist() {
    GameServerEntity entity = new GameServerEntity();
    entity.setId(1L);
    entity.setServerName("Server 1");
    when(repository.findById(1L)).thenReturn(Optional.of(entity));

    Optional<ServerDTO> result = service.getById(1L);

    assertThat(result).isPresent();
    assertThat(result.get().serverId()).isEqualTo(1L);
    assertThat(result.get().serverName()).isEqualTo("Server 1");
  }
}
