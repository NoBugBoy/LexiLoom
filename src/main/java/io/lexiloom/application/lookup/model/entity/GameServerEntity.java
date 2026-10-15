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
@Table(name = "ll_game_server")
public class GameServerEntity extends BaseEntity {
  private String serverName;
}
