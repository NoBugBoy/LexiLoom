package io.lexiloom.application.user.model.response;

import cn.hutool.core.date.DatePattern;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public record AdminItemResponse(
    Long id,
    String email,
    List<String> roles,
    @JsonFormat(pattern = DatePattern.NORM_DATETIME_PATTERN) LocalDateTime createTime) {
  /** 供 QueryDSL Projections.constructor 调用的重载构造器 */
  public AdminItemResponse(Long id, String email, String rolesStr, LocalDateTime createTime) {
    this(
        id,
        email,
        // 切割字符串转为 List，并做判空保护
        (rolesStr != null && !rolesStr.isBlank())
            ? Arrays.stream(rolesStr.split(",")).map(String::trim).toList()
            : Collections.emptyList(),
        createTime);
  }
}
