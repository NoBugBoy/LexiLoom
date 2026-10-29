package io.lexiloom.application.common;

import lombok.Data;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@Data
public class BasePageRequest {
  private static final int DEFAULT_PAGE_NUM = 1;
  private static final int DEFAULT_PAGE_SIZE = 10;
  private static final int MAX_PAGE_SIZE = 100;

  private Integer pageNum = DEFAULT_PAGE_NUM;
  private Integer pageSize = DEFAULT_PAGE_SIZE;

  public Pageable toPageable() {
    return toPageable(Sort.by(Sort.Direction.DESC, "createTime"));
  }

  public Pageable toPageable(Sort sort) {
    // 1. 防御性处理：页码最小为 1
    int validPageNum = pageNum == null ? DEFAULT_PAGE_NUM : Math.max(DEFAULT_PAGE_NUM, pageNum);

    // 2. 防拖库处理：每页限制在 [1, 100] 区间
    int validPageSize =
        (pageSize == null || pageSize < 1)
            ? DEFAULT_PAGE_SIZE
            : Math.clamp(pageSize, 1, MAX_PAGE_SIZE);

    // 3. JPA 内部是从 0 开始计数的，这里做 validPageNum - 1
    return PageRequest.of(validPageNum - 1, validPageSize, sort != null ? sort : Sort.unsorted());
  }
}
