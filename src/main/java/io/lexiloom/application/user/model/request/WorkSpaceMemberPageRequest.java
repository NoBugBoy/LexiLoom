package io.lexiloom.application.user.model.request;

import io.lexiloom.application.common.BasePageRequest;
import io.lexiloom.application.enums.WorkSpaceRoleEnum;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class WorkSpaceMemberPageRequest extends BasePageRequest {
  private WorkSpaceRoleEnum role;
}
