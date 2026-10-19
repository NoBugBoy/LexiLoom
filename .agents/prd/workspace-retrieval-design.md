# PRD：工作区层级检索、细粒度文档授权与统一审批系统

> **文档编号**：PRD-SEARCH-001  
> **版本**：v1.3  
> **状态**：✅ 已确认  
> **创建日期**：2026-10-19  
> **变更记录**：
> - v1.1 - 重构层级检索方向：由"子向上继承"改为"父向下聚合"，移除 `ancestorDepthLimit`
> - v1.2 - 权重改为纯前端运行时传递，不持久化；移除 `workspace_child_weight` 表和 SpaceLink 中的 weight 字段；新增统一 Rerank 步骤；
> - v1.3 - 跨 Space 关联改为**细粒度选择授权（筛选 + 选择 + 审批）**，允许申请并展示 target space 的 PUBLIC 与 INTERNAL 文档名称列表，审批通过后仅精准检索被勾选文档，避免全量 PUBLIC 检索污染准确度；抽离独立**通用审批单模型（`ll_approval_record`）**，支持文档发布与空间关联审批，强制记录审批描述（为什么同意/为什么驳回）。

---

## 一、背景与问题定义

### 1.1 现状痛点

当前知识库工作区为**扁平结构**，缺乏层级关联能力，导致以下问题：

| # | 问题 | 影响 |
|---|------|------|
| P1 | 工作区无层级，跨项目文档难以关联检索 | 工程师在 B 项目中无法关联 C 项目的相关文档 |
| P2 | 公开文档无边界控制 | 检索范围无节制蔓延，即便设置 PUBLIC 也可能对外部项目无用，反而污染准确度并增加大模型幻觉概率 |
| P3 | 召回数量固定，权重不可调节 | 关联空间贡献度无法量化，强弱相关空间同等对待 |
| P4 | 审批流单薄无审计历史 | 审批仅停留在状态字段，缺乏统一审批单、申请理由和详细审批意见描述（为什么同意/为什么驳回） |

### 1.2 目标场景

```
集团（公开规范文档）
└── 技术中台部（内部）
    └── A部门（部门规范，大部分公开）
        ├── B项目（车端业务，私有+内部）
        └── C项目（云端业务，私有+内部）
```

---

## 二、核心设计原则

### 2.1 方向：父空间向下聚合，子空间不向上穿透

> [!IMPORTANT]
> **检索方向只向下，从不向上。**  
> 子空间只能检索自身文档；父空间自动关联其**直系子空间**，可向下聚合。

| 检索发起位置 | 可检索范围 |
|------------|-----------|
| B项目空间 | **仅 B 自身**（无子空间） |
| C项目空间 | **仅 C 自身**（无子空间） |
| A部门空间 | A 自身 + B项目（PUBLIC）+ C项目（PUBLIC）|
| 技术中台部空间 | 技术中台部自身 + A部门（PUBLIC）|
| 集团空间 | 集团自身 + 技术中台部（PUBLIC）+ 其他部门（PUBLIC）|

> [!NOTE]
> 技术中台部**只能**检索到 A 部门的文档（直系子），**不能穿透**到 B、C 项目。  
> 每一层仅关联**直系**子空间，不递归传导，防止深层数据泄露。

### 2.2 横向关联铁律：细粒度文档选择授权（筛选 + 选择 + 审批）

> [!IMPORTANT]
> **横向 SpaceLink 不再无脑透传所有 PUBLIC 文档！**  
> 1. 发起关联申请时，前端可获取目标空间的 `PUBLIC` 与 `INTERNAL` 文档列表（**严格仅展示文档 ID、标题、分类、可见性、更新时间等基本概要信息，绝不透传正文内容与向量切片**）。  
> 2. 发起方必须明确勾选本次申请需要检索的具体文档清单（`documentIds`）。  
> 3. 目标空间管理员审核时，核对申请关联的文档列表，并**强制填写审批描述（为什么同意 / 为什么驳回）**。  
> 4. 审批通过后，跨空间检索仅召回**被授权且已审批通过的具体文档**（包含被勾选的 PUBLIC 与 INTERNAL 文档），未勾选的文档一律无法检索！彻底杜绝非必要文档污染检索准确度。

### 2.3 审批流中枢：独立通用审批记录表

> [!IMPORTANT]
> 抽离独立审批实体 `ll_approval_record`，统一管理系统中的审批单据：
> 1. **文档发布审批（`DOCUMENT_AUDIT`）**：文档上传解析后由待审核流转至已发布，必须记录审批人与同意/驳回意见。
> 2. **空间关联与文档授权审批（`SPACE_LINK`）**：A 空间申请关联 B 空间的指定文档，记录申请理由、授权文档清单，并由 B 空间管理员审批，附带详细描述。

### 2.4 权重：纯运行时，不持久化

> [!NOTE]
> **权重不存储在数据库中**，由前端在每次检索请求时实时传递。  
> 未传递权重时默认为：主空间 100%，其他关联源 0%（即仅检索当前空间）。  
> 权重配置属于用户/会话级行为，服务端无状态处理。

---

## 三、数据模型

### 3.1 工作区与关联模型

```
Workspace
├── id: Long
├── name: String
├── visibility: PRIVATE | INTERNAL | PUBLIC
├── parentId: Long?                    # 父工作区（null = 顶级）
└── childAssocEnabled: Boolean         # 是否允许父空间聚合此子空间（子空间可关闭）
```

```
SpaceLink（横向显式关联主表）
├── id: Long
├── sourceWorkspaceId: Long            # 发起方（获得检索权限方）
├── targetWorkspaceId: Long            # 目标方（被检索方，需审批）
├── status: PENDING | APPROVED | REJECTED
├── rejectReason: String?              # 驳回原因说明
├── createdAt: Timestamp
└── updatedAt: Timestamp
```

```
SpaceLinkDocument（横向关联授权文档明细表 ll_workspace_space_link_doc）
├── id: Long
├── linkId: Long                       # 关联主表 ID
├── targetWorkspaceId: Long            # 目标空间 ID
├── documentId: Long                   # 被授权的目标文档 ID
└── createdAt: Timestamp
```

```
ApprovalRecord（统一审批记录表 ll_approval_record）
├── id: Long
├── approvalType: DOCUMENT_AUDIT | SPACE_LINK
├── bizId: Long                        # 业务实体 ID (documentId 或 spaceLinkId)
├── workspaceId: Long                  # 申请方空间 ID
├── targetWorkspaceId: Long?           # 目标空间 ID (空间关联时使用)
├── applicantId: Long                  # 申请人用户 ID
├── applicantName: String?             # 申请人名称
├── applyReason: String?               # 申请理由
├── status: PENDING | APPROVED | REJECTED | CANCELLED
├── approverId: Long?                  # 实际审批人 ID
├── approverName: String?              # 实际审批人名称
├── approvalComment: String?           # 审批意见（为什么同意 / 为什么驳回，强制要求）
├── approvalTime: Timestamp?           # 审批时间
├── extraData: Text?                   # 扩展数据 (JSON 格式：如申请文档列表、版本号等)
├── createdAt: Timestamp
└── updatedAt: Timestamp
```

### 3.2 检索源分类与文档透传范围

| 类型 | 来源 | 授权机制 | 可检索范围 |
|------|------|---------|----------|
| **PRIMARY** | 当前工作区自身 | 自动 | PRIVATE + INTERNAL + PUBLIC（基于用户 ACL） |
| **CHILD_AUTO** | 直系子空间（父空间自动发现） | 子空间开启 `childAssocEnabled` | **仅 PUBLIC** |
| **EXPLICIT** | 审批通过的 SpaceLink 目标空间 | 筛选 + 勾选文档 + 审批通过 | **仅审批通过授权的文档（PUBLIC + INTERNAL 均可被选）** |

---

## 四、权重分配规则

### 4.1 权重来源

权重**完全由前端在检索请求中传递**，服务端不存储、不预设任何默认权重配置。

```
前端传递权重示例：
  PRIMARY(A)  = 80
  CHILD(B)    = 10
  CHILD(C)    = 10
  ─────────────────
  合计         = 100（服务端自动归一化，无需前端严格保证）

未传递权重时（默认行为）：
  PRIMARY = 100，所有关联源 = 0
  → 等价于仅检索当前空间，不触发多源聚合
```

### 4.2 加权召回分配算法

```
// 1. 召回数动态区间约束：配置文件控制动态上限 (默认50) 与保底值 (默认20)
// 配置项：lexiloom.search.max-total-recall (默认50), min-total-recall (默认20)
rawRecall = 请求传递数量 (若未传或 < min-total-recall 则默认为 min-total-recall 20)
totalRecall = min(rawRecall, max-total-recall 50)

// 2. 按权重比例计算各空间初排配额
quota(source_i) = floor(totalRecall × weight_i / Σweights)

// 3. 余量自适应补偿：关联源实际召回不足配额时，差额回补给 PRIMARY 主空间
```

#### 示例计算

```
A部门空间检索，关联 B项目 + C项目
前端传递权重：A=80, B=10, C=10，totalRecall=20 (若前端传 5 则保底提升为 20)

原始分配：A=16, B=2, C=2

若 B 只有 1 条匹配：
  → A 得到补偿 +1 → A=17, B=1, C=2，总计=20 ✓
```

### 4.3 权重与条数约束（服务端校验）

| 约束 | 规则 |
|------|------|
| 权重归一化 | Σweights 非 100 时自动按比例折算，不报错 |
| 关联源权重下限 | 无限制（可以设为 0，等价于不检索该源） |
| totalRecall 下限 | 请求传值 `< 20` 或未传时，自动提升并保底为 20 |
| totalRecall 上限 | 超过配置文件 `lexiloom.search.max-total-recall`（默认 50）时截断，返回 `warning` 字段提示 |
| 非法权重值 | weight < 0 时忽略或降为 0 |
| RAG 切片解耦 | 该文档检索 totalRecall 仅作用于文档列表搜索，**不影响** AI 对话问答中切片级的 `vector-top-k` 和 `lexical-top-k` |

---

## 五、检索流程

```
用户在【A部门空间】发起检索 "消息协议规范"
前端传递：weights={A:80, B:10, C:10}, totalRecall=20
          │
          ▼
Step 1. 解析检索源
   ├── PRIMARY: A部门（自身，全可见）
   ├── CHILD_AUTO: B项目（直系子，childAssocEnabled=true）
   └── CHILD_AUTO: C项目（直系子，childAssocEnabled=true）

Step 2. 权重分配 & quota 计算
   A=16, B=2, C=2

Step 3. 并发检索各源（PUBLIC 过滤已在索引层完成）

Step 4. 余量补偿（某源不足时补给 PRIMARY）

Step 5. 聚合所有源的候选文档（最多 totalRecall 条原始候选）

Step 6. 统一 Rerank（对聚合结果整体重排，消除各源评分尺度差异）

Step 7. 返回 top-N 最终结果，附带 source 归属信息
```

```
用户在【B项目空间】发起检索（无任何关联源，或前端未传 weights）
          │
          ▼
Step 1. 仅 PRIMARY: B 自身
Step 2. quota = totalRecall（全量给 B）
Step 3. 检索 → Rerank → 返回
```

```
用户在【B项目空间】（已有审批通过的 SpaceLink → C）发起检索
前端传递：weights={B:80, C:20}, totalRecall=20
          │
          ▼
Step 1. PRIMARY: B + EXPLICIT: C（status=APPROVED ✓）
Step 2. quota: B=16, C=4
Step 3. 并发检索（C 侧自动过滤只返回 PUBLIC 文档）
Step 4. 余量补偿
Step 5. 聚合 → 统一 Rerank → 返回
```

---

## 六、API 设计

### 6.1 配置子空间是否允许被父聚合（子空间 Admin）

```http
PATCH /api/workspaces/{workspaceId}/child-assoc
Content-Type: application/json

{
  "childAssocEnabled": true   // false = 拒绝被任何父空间聚合
}
```

### 6.2 申请 SpaceLink（源空间 Admin）

```http
POST /api/workspaces/{sourceWorkspaceId}/space-links
Content-Type: application/json

{
  "targetWorkspaceId": "uuid-C"
}
```

> 提交后 SpaceLink `status = PENDING`，目标空间 Admin 收到站内通知。

### 6.3 审批 SpaceLink（目标空间 Admin）

```http
PATCH /api/workspaces/{targetWorkspaceId}/space-links/{linkId}/approval
Content-Type: application/json

{
  "action": "APPROVE" | "REJECT",
  "reason": "string?"    // 驳回时建议填写
}
```

**状态流转**：
```
PENDING ──APPROVE──→ APPROVED（立即生效，纳入检索）
PENDING ──REJECT───→ REJECTED（源空间 Admin 可重新申请）
APPROVED ──(目标方撤销)──→ REJECTED
```

### 6.4 查询当前空间可用的关联源（前端用于构建权重选择器）

```http
GET /api/workspaces/{workspaceId}/available-sources
```

```json
{
  "primary": {
    "workspaceId": "uuid-A",
    "workspaceName": "A部门"
  },
  "children": [
    { "workspaceId": "uuid-B", "workspaceName": "B项目" },
    { "workspaceId": "uuid-C", "workspaceName": "C项目" }
  ],
  "explicitLinks": [
    { "workspaceId": "uuid-X", "workspaceName": "X项目", "status": "APPROVED" }
  ]
}
```

> 前端根据此接口渲染权重滑块，用户调整后随检索请求实时传递。

### 6.5 检索请求

```http
POST /api/search
Content-Type: application/json

{
  "workspaceId": "uuid-A",
  "query": "消息协议规范",
  "totalRecall": 20,
  "weights": {              // 前端实时传递，服务端不持久化
    "uuid-A": 80,
    "uuid-B": 10,
    "uuid-C": 10
  }
}
```

### 6.6 检索结果

```json
{
  "totalHits": 19,
  "reranked": true,
  "sources": [
    {
      "workspaceId": "uuid-A",
      "workspaceName": "A部门",
      "sourceType": "PRIMARY",
      "allocatedQuota": 16,
      "actualHits": 16
    },
    {
      "workspaceId": "uuid-B",
      "workspaceName": "B项目",
      "sourceType": "CHILD_AUTO",
      "allocatedQuota": 2,
      "actualHits": 2
    },
    {
      "workspaceId": "uuid-C",
      "workspaceName": "C项目",
      "sourceType": "CHILD_AUTO",
      "allocatedQuota": 2,
      "actualHits": 1,
      "compensatedTo": "uuid-A"
    }
  ],
  "results": [
    {
      "documentId": "doc-001",
      "workspaceId": "uuid-A",
      "title": "消息协议 v2 规范",
      "score": 0.94,
      "rerankScore": 0.91,
      "snippet": "..."
    }
  ]
}
```

> [!NOTE]
> Rerank 后结果统一排列在顶层 `results` 数组，不再按 source 分组，但每条文档保留 `workspaceId` 溯源。

---

## 七、数据库设计

```sql
-- 工作区表（层级字段）
ALTER TABLE ll_workspace
  ADD COLUMN parent_id           BIGINT REFERENCES ll_workspace(id),
  ADD COLUMN child_assoc_enabled BOOLEAN NOT NULL DEFAULT TRUE;

-- 显式横向关联主表
CREATE TABLE ll_workspace_space_link (
    id                    BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_workspace_id   BIGINT NOT NULL,
    target_workspace_id   BIGINT NOT NULL,
    status                VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                            CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    reject_reason         VARCHAR(500),
    create_time           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE (source_workspace_id, target_workspace_id)
);

-- 空间关联授权文档明细表（记录本次关联具体选中的文档）
CREATE TABLE ll_workspace_space_link_doc (
    id                    BIGINT PRIMARY KEY AUTO_INCREMENT,
    link_id               BIGINT NOT NULL REFERENCES ll_workspace_space_link(id),
    target_workspace_id   BIGINT NOT NULL,
    document_id           BIGINT NOT NULL,
    create_time           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_space_link_doc (link_id, document_id)
);

-- 独立通用审批记录表
CREATE TABLE ll_approval_record (
    id                    BIGINT PRIMARY KEY AUTO_INCREMENT,
    approval_type         VARCHAR(32) NOT NULL, -- 'DOCUMENT_AUDIT', 'SPACE_LINK'
    biz_id                BIGINT NOT NULL,      -- document_id 或 space_link_id
    workspace_id          BIGINT NOT NULL,
    target_workspace_id   BIGINT,
    applicant_id          BIGINT NOT NULL,
    applicant_name        VARCHAR(100),
    apply_reason          VARCHAR(500),
    status                VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    approver_id           BIGINT,
    approver_name         VARCHAR(100),
    approval_comment      VARCHAR(500),         -- 为什么同意 / 为什么驳回 (强制要求)
    approval_time         DATETIME,
    extra_data            TEXT,                 -- JSON 扩展元数据 (如勾选的文档ID列表)
    create_time           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_approval_status (target_workspace_id, status, approval_type),
    INDEX idx_approval_biz (biz_id, approval_type)
);
```

---

## 八、权限矩阵

| 操作 | 所需权限 |
|------|---------|
| 检索主空间全部文档（PRIVATE/INTERNAL/PUBLIC） | 空间成员（遵循 ACL） |
| 查询目标空间可关联文档列表（仅名称） | 源空间 Admin |
| 发起 SpaceLink 申请并勾选文档 | 源空间 Admin |
| 审批 / 驳回 SpaceLink 申请（必填审批描述） | 目标空间 Admin |
| 审批文档发布（必填审批描述） | 空间 Admin |
| 跨空间检索被授权文档（PUBLIC + INTERNAL） | 源空间成员（经审批授权生效） |

---

## 九、边界条件与异常处理

| 场景 | 处理策略 |
|------|---------|
| 申请关联未勾选任何文档 | 校验失败，抛出业务异常：“请至少选择一个关联文档” |
| 目标空间文档状态未通过审批（如草稿或待审） | 不出现在可选择列表中，不可被申请关联 |
| 审批通过后目标文档被删除或归档 | 检索时文档不存在或状态非 APPROVED，检索自动忽略该文档 |
| 审批通过但关联空间没有授权文档 | 跳过该空间召回，配额返还主空间自适应补偿 |
| 审批驳回未填写原因 | 校验失败，抛出业务异常：“审批意见/原因不能为空” |

---

## 十、决策记录（ADR）

### ADR-001：检索方向改为父→子，移除子→父继承
**决策**：检索方向仅为向下（父聚合直系子），子空间无法向上检索。

### ADR-002：父空间仅关联直系子空间，不递归传导
**决策**：技术中台部关联 A 部门（直系子），不传导到 B、C 项目。

### ADR-003：权重不持久化，纯前端运行时传递
**决策**：权重由前端每次检索时传递，服务端无状态；未传时默认 PRIMARY=100%。

### ADR-004：细粒度文档选择授权取代全量 PUBLIC 粗放检索
**决策**：横向 SpaceLink 申请时展示目标空间的 PUBLIC 和 INTERNAL 文档列表（仅名称与概要），发起方必须勾选具体需要的文档清单。审批人审批通过后，跨空间检索仅召回这批明确授权的文档。  
**理由**：PUBLIC 文档全量开放不仅无必要，反而严重污染相关度与准确度。精准筛选授权才能保证召回文档的高相关性。

### ADR-005：抽离通用独立审批记录模型与必填审批描述
**决策**：设计统一审批实体 `ll_approval_record`，管理文档发布审批与跨空间关联文档授权审批；审批操作必须附带 `approval_comment`，说明“为什么同意”或“为什么驳回”。  
**理由**：解决审批单薄无历史记录与无法审计的问题，清晰归档审批决断依据。


**决策**：各 source 独立检索后，聚合结果统一送入 Rerank 模型重排，最终结果扁平返回。  
**理由**：各 source 向量评分尺度不一致，直接拼接会导致某一 source 的文档因分数虚高霸榜；统一 Rerank 以查询语义为锚点，确保跨源结果的相关性可比。

---

## 十二、决策汇总（所有 Open Questions 已关闭）

| # | 问题 | 决策 |
|---|------|------|
| Q1 | SpaceLink 是否需要审批？ | ✅ 需要，且严格单向 |
| Q2 | 子空间数 = 1 的默认权重？ | ✅ 权重不持久化，前端传递，默认 PRIMARY=100% |
| Q3 | SpaceLink 审批超时策略？ | ✅ 无超时，仅 APPROVE / REJECT |
| Q4 | 跨空间只透传 PUBLIC？ | ✅ 所有跨空间均只透传 PUBLIC，无例外 |
| Q5 | 多源聚合后统一 Rerank？ | ✅ 统一 Rerank，复用已有实现 |
| Q6 | Rerank 模型选型？ | ✅ 复用项目现有 Rerank 实现，无需新增 |
| Q7 | 前端权重持久化策略？ | ✅ 由前端独立处理，服务端无关 |

