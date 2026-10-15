package io.lexiloom.application.common;

import lombok.Data;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@Data
public class BasePageRequest {
  private Integer pageNum = 1;
  private Integer pageSize = 10;

  public Pageable toPageable() {
    return toPageable(Sort.by(Sort.Direction.DESC, "createTime"));
  }

  public Pageable toPageable(Sort sort) {
    // 1. 防御性处理：页码最小为 1
    int validPageNum = (pageNum == null || pageNum < 1) ? 1 : pageNum;

    // 2. 防拖库处理：每页最大限制 100 条（按业务调整）
    int validPageSize = (pageSize == null || pageSize < 1) ? 10 : Math.min(pageSize, 100);

    // 3. JPA 内部是从 0 开始计数的，这里做 validPageNum - 1
    return PageRequest.of(validPageNum - 1, validPageSize, sort != null ? sort : Sort.unsorted());
  }
}
