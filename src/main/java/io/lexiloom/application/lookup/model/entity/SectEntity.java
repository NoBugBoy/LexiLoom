package io.lexiloom.application.lookup.model.entity;

import io.lexiloom.application.common.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "ll_sect")
public class SectEntity extends BaseEntity {
  private String sectName;
}
