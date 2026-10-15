package io.lexiloom.application.user.model.request;

import io.lexiloom.application.common.BasePageRequest;
import io.lexiloom.application.enums.WorkSpaceAclEnum;
import io.lexiloom.application.enums.WorkSpaceStatusEnum;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class WorkSpacePageRequest extends BasePageRequest {
  private String keyword;
  private WorkSpaceStatusEnum status;
  private WorkSpaceAclEnum visibility;
}
