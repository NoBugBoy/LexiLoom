package io.lexiloom.application.lookup.model.entity;

import io.lexiloom.application.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 门派配置实体 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "ll_sect", comment = "门派字典表")
public class SectEntity extends BaseEntity {

  /** 门派名称 */
  @Column(name = "sect_name", comment = "门派名称", nullable = false, length = 100)
  private String sectName;
}
