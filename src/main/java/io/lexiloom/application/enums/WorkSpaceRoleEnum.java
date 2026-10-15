package io.lexiloom.application.enums;

import lombok.Getter;

@Getter
public enum WorkSpaceRoleEnum {
  /** 空间所有者 (Owner)：团队创建人，拥有解散团队、转让团队、计费配置的终极权力 */
  OWNER(100),
  /** 空间管理员 (Admin)：负责团队内日常管理（添加/踢除成员、审批文档、管理知识库分类） */
  ADMIN(50),
  /** 协作成​​员 (Member)：日常编写文档的主力（新建文档、编辑、发起审阅、评论） */
  MEMBER(20),
  /** 访客/只读人员 (Guest/Viewer)：外部合作者或实习生（仅能查看授权文档，不可编辑和创建） */
  GUEST(10);
  private final int level; // 权限等级，方便做 >= 判断

  WorkSpaceRoleEnum(int level) {
    this.level = level;
  }
}
