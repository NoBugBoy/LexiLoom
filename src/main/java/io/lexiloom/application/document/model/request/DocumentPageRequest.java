package io.lexiloom.application.document.model.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.lexiloom.application.common.BasePageRequest;
import io.lexiloom.application.document.api.dto.DocumentStateEnum;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** 文档分页检索请求对象 (继承 BasePageRequest) */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class DocumentPageRequest extends BasePageRequest {

  @NotNull(message = "工作空间ID不能为空")
  private Long workSpaceId;

  private Long categoryId;

  private DocumentStateEnum status;

  private String keyword;

  public DocumentPageRequest(
      Long workSpaceId,
      Long categoryId,
      DocumentStateEnum status,
      String keyword,
      Integer page,
      Integer size) {
    this.workSpaceId = workSpaceId;
    this.categoryId = categoryId;
    this.status = status;
    this.keyword = keyword;
    if (page != null) {
      setPageNum(page >= 0 ? page + 1 : 1);
    }
    if (size != null) {
      setPageSize(size);
    }
  }

  @JsonProperty("page")
  public void setPage(Integer page) {
    if (page != null) {
      setPageNum(page >= 0 ? page + 1 : 1);
    }
  }

  @JsonProperty("size")
  public void setSize(Integer size) {
    if (size != null) {
      setPageSize(size);
    }
  }

  // Record 风格兼容访问器
  public Long workSpaceId() {
    return workSpaceId;
  }

  public Long categoryId() {
    return categoryId;
  }

  public DocumentStateEnum status() {
    return status;
  }

  public String keyword() {
    return keyword;
  }

  @Override
  public Pageable toPageable() {
    return super.toPageable(Sort.by(Sort.Direction.DESC, "updateTime"));
  }
}
