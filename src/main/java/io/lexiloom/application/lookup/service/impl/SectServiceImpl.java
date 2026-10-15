package io.lexiloom.application.lookup.service.impl;

import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import io.lexiloom.application.exception.BusinessException;
import io.lexiloom.application.lookup.api.SectService;
import io.lexiloom.application.lookup.api.dto.SectDTO;
import io.lexiloom.application.lookup.mapper.SectMapper;
import io.lexiloom.application.lookup.model.entity.QSectEntity;
import io.lexiloom.application.lookup.model.entity.SectEntity;
import io.lexiloom.application.lookup.model.request.SectPageRequest;
import io.lexiloom.application.lookup.model.request.SectRequest;
import io.lexiloom.application.lookup.model.response.SectOptionResponse;
import io.lexiloom.application.lookup.model.response.SectPageResponse;
import io.lexiloom.application.lookup.repository.SectRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class SectServiceImpl implements SectService {
  private final SectRepository sectRepository;
  private final SectMapper sectMapper;
  private final JPAQueryFactory queryFactory;

  @Override
  public Page<SectPageResponse> page(SectPageRequest request) {
    QSectEntity qSect = QSectEntity.sectEntity;

    BooleanExpression condition = null;
    if (StringUtils.hasText(request.getSectName())) {
      condition = qSect.sectName.contains(request.getSectName());
    }

    var sectEntities =
        condition == null
            ? sectRepository.findAll(request.toPageable())
            : sectRepository.findAll(condition, request.toPageable());

    return sectEntities.map(
        it -> new SectPageResponse(it.getId(), it.getSectName(), it.getCreateTime()));
  }

  @Override
  @Transactional(rollbackFor = Throwable.class)
  public void deleteById(Long sectId) {
    sectRepository.deleteById(sectId);
  }

  @Override
  @Transactional(rollbackFor = Throwable.class)
  public void createSect(SectRequest request) {

    if (sectRepository.existsBySectName(request.sectName())) {
      throw BusinessException.badRequest("该门派已经存在");
    }

    sectRepository.save(sectMapper.toSectEntity(request.sectName()));
  }

  @Override
  @Transactional(rollbackFor = Throwable.class)
  public void update(Long sectId, SectRequest request) {
    SectEntity sectEntity =
        sectRepository.findById(sectId).orElseThrow(() -> BusinessException.badRequest("该数据已经被删除"));
    sectEntity.setSectName(request.sectName());

    sectRepository.saveAndFlush(sectEntity);
  }

  @Override
  public List<SectOptionResponse> options() {
    QSectEntity sect = QSectEntity.sectEntity;

    return queryFactory
        .select(Projections.constructor(SectOptionResponse.class, sect.id, sect.sectName))
        .orderBy(sect.createTime.desc())
        .from(sect)
        .fetch();
  }

  @Override
  public Optional<SectDTO> getById(Long sectId) {
    return sectRepository.findById(sectId).map(it -> new SectDTO(it.getId(), it.getSectName()));
  }
}
