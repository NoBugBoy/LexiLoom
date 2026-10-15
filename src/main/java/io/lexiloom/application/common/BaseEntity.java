package io.lexiloom.application.common;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

/**
 * @author yujian
 */
@MappedSuperclass
@NoArgsConstructor
@Getter
@Setter
@EntityListeners(CustomAuditListener.class)
@SQLDelete(sql = "UPDATE {h-table} SET del_flag = id WHERE id = ?")
@SQLRestriction("del_flag = 0")
public class BaseEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(comment = "主键")
  private Long id;

  @Column(name = "create_time", comment = "创建时间", updatable = false)
  private LocalDateTime createTime;

  @Column(name = "update_time", comment = "更新时间")
  private LocalDateTime updateTime;

  @Column(name = "create_by", comment = "创建人", updatable = false)
  private Long createBy;

  @Column(name = "update_by", comment = "最后操作人")
  private Long updateBy;

  @Column(name = "del_flag", comment = "逻辑删除")
  private Long delFlag = 0L;
}
