package io.lexiloom.application.approval.mapper;

import io.lexiloom.application.approval.api.dto.ApprovalRecordDTO;
import io.lexiloom.application.approval.model.entity.ApprovalRecordEntity;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * 审批实体与 DTO 转换映射器
 */
@Mapper(componentModel = "spring")
public interface ApprovalMapper {

  ApprovalRecordDTO toDTO(ApprovalRecordEntity entity);

  List<ApprovalRecordDTO> toDTOList(List<ApprovalRecordEntity> entities);
}
