package io.lexiloom.application.enums;

public enum WorkSpaceStatusEnum {
  /** 正常状态：团队功能完全正常，允许成员读写、上传与协作 */
  NORMAL,
  /** 已冻结 / 禁用：通常由于存储空间超标、违规或管理员手动封禁触发。 业务行为：只读模式，不可新建/修改文档，不可上传新文件 */
  FROZEN,
  /** 已解散：团队已被创建者/管理员正式注销解散。 业务行为：彻底不可用，不在列表展示，所有关联文档被封存或转入归档 */
  DISBANDED;

  /** 辅助业务判断：团队是否可用 */
  public boolean isActive() {
    return this == NORMAL;
  }
}
