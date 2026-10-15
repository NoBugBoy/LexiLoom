package io.lexiloom.application.lookup.model.request;

import io.lexiloom.application.common.BasePageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class GameServerPageRequest extends BasePageRequest {
  private String gameServerName;
}
