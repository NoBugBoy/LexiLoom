package io.lexiloom.application.user.model.request;

import io.lexiloom.application.common.BasePageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class AdminPageRequest extends BasePageRequest {
  private String email;
}
