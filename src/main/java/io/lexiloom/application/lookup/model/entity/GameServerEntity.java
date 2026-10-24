package io.lexiloom.application.lookup.model.entity;

import io.lexiloom.application.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 游戏服务器配置实体 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "ll_game_server", comment = "游戏服务器字典表")
public class GameServerEntity extends BaseEntity {

  /** 游戏服务器名称 */
  @Column(name = "server_name", comment = "游戏服务器名称", nullable = false, length = 100)
  private String serverName;
}
