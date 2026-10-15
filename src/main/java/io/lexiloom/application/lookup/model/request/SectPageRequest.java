package io.lexiloom.application.lookup.model.request;

import io.lexiloom.application.common.BasePageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class SectPageRequest extends BasePageRequest {
  private String sectName;
}
