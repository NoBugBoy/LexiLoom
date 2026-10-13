# 阶段二：文档全生命周期与协同模块（任务实施全景清单）

> **所属项目**：KnowFlow 知识库管理系统  
> **模块定位**： **内容资产中心（Content Asset Center）**  
> **架构技术规范**：JDK 25 + Spring Boot 4.x + Spring Modulith + Spring Data JPA + CQRS + DDD 充血模型 + COLA
> StateMachine + MapStruct + rustfs 存储防腐层 + DeepDoc / MarkItDown 解析防腐层

---

## 一、 模块核心定位与业务全景

### 1.1 业务认知纠偏：什么是“内容资产中心”？

文档模块绝非传统单一的手动在线文本编辑，而是企业级知识资产的 **摄入、加工、流转、沉淀与检索底座**：

```
                    ┌── [唯一来源] 外部文件导入：PDF、Word (DOCX)、PPT (PPTX)、Excel (XLSX)、Markdown、TXT 批量导入
                    │             全流程由 DeepDoc 版面分析引擎深度驱动解析（YOLO版面识别 + KMeans行距聚类）
唯一内容输入渠道 ──┤             提取高保真 Markdown 正文、结构化原子切片与物理边界坐标 (BBox)
                    │             导入后自动沉淀为 DRAFT 草稿，支持在在线 Markdown 编辑器中二次精修
                    └───────────────────┐
                                        │
                                        ▼
                  ╔═══════════════════════════════════════════╗
                  ║           KnowFlow 内容资产中心           ║
                  ║  ┌─────────────────────────────────────┐  ║
                  ║  │ 状态机引擎 (COLA StateMachine)       │  ║
                  ║  │ 统一存储底座 (rustfs / 对象存储)     │  ║
                  ║  │ 原始文件溯源与元数据索引             │  ║
                  ║  │ 树状目录 / 标签体系 / 工作区隔离    │  ║
                  ║  │ 细粒度 ACL / 审批协同 / 评论树       │  ║
                  ║  │ 版本快照 / Myers Diff / 一键回滚     │  ║
                  ║  └─────────────────────────────────────┘  ║
                  ╚═══════════════════════════════════════════╝
                                        │
                                        ▼
下游赋能输出 ────► Elasticsearch 全文检索 + 向量切块 RAG + 来源文件精准回溯 (Source Tracing)
```

### 1.2 外部文件导入与编辑策略（业内成熟标准）

系统建立分类路由策略，兼顾 **编辑灵活性**与 **格式版面保真度**：

| 文件类型                                | 编辑策略                           | 处理管线与存储落地机制                                                                                                                                                                        |
|:----------------------------------------|:-----------------------------------|:----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **纯文本类**<br>`Markdown / TXT / HTML` | **100% 自由编辑**                  | 直接提取纯文本/Markdown 内容灌入在线编辑器；原文件存入 rustfs 作为备份源。                                                                                                                    |
| **办公文档类**<br>`Word (.docx)`        | **自动转化，自由编辑**             | 后端通过解析引擎（MarkItDown）提取标题、层级、加粗、代码块、列表并输出标准 Markdown 塞入文档正文；**原件 .docx 文件落地到 rustfs 存储**，绑定来源文件元数据，供后续下载或原件查验。           |
| **固化版面类**<br>`PDF / 扫描件`        | **双轨制**<br>(只读预览 + AI 提炼) | **默认轨**：直接作为不可修改的资产附件，前端利用 PDF 阅读器做分页向量化与原件渲染预览；<br>**提炼轨**：提供“一键提取为在线文档”动作，异步通过 OCR/MarkItDown 提炼生成新的独立 Markdown 副本。 |

---

## 二、 关键技术选型与架构规范

1. **状态机引擎**：采用轻量级阿里巴巴开源 **COLA StateMachine (`cola-component-statemachine`)**。
    - 严格消除深层 `if-else` 与随意状态修改；
    - 显式定义状态转移矩阵：`State`、`Event`、`Condition`、`Action`；
    - 业务流转过程触发审计日志与领域事件发布。
2. **文档解析引擎防腐层（MarkItDown ACL）**：
    - 核心抽象：`DocParserGateway`，定义多格式文件到 Markdown 的标准转换契约；
    - 阶段落地方案：当前提供 `MockDocParserAdapter`（内置对 MD、TXT 的纯文本处理及 Word/PDF 的结构化 Mock 响应），预留独立的
      Python / MarkItDown 微服务 HTTP/gRPC 远程调用适配器，未来切换无需改动任何业务编排代码,先上传再解析,解析服务只接受文件类型和rustfs地址而不是字节流。
3. **统一存储底座与源文件溯源（rustfs Storage & Source Link）**：
    - 所有上传的文件、正文转化的 Markdown 资产、图片附件均通过 `FileStorageGateway` 统一存储至 **rustfs**（兼容 S3
      协议的高性能分布式对象存储底座）；
    - 建立 **文档源文件资产关联表 (`kf_doc_source_file`)**，记录原文件 MD5/SHA256、文件大小、rustfs 存储路径、原始扩展名与解析状态；
    - **核心价值**：后续阶段在 Elasticsearch 全文检索和 RAG 问答双路召回时，不仅能定位正文段落，还能 **精准将原始上传文件（PDF/Word
      原件）检索召回并提供原文定位下载**。

---

## 三、 详细任务分解清单 (Task Breakdown)

### 任务组 1：依赖引入与基础设施配置

- [x] **1.1 引入 COLA StateMachine 依赖**
    - 在 `pom.xml` 中引入 `com.alibaba.cola:cola-component-statemachine:4.3.2` 版本并编译通过。
- [x] **1.2 增强存储防腐层与 rustfs 适配**
    - 扩展 `FileStorageGateway` 接口，支持原文件流式下载 `download` 与状态探测 `exists`；
    - 落地 `RustfsStorageAdapter` 与 `RustfsStorageProperties`，支持 rustfs 高性能分布式对象存储与本地目录回退。
- [x] **1.3 定义状态机与解析器异常码**
    - 在 `ErrorCode.java` 中增加 `Document` 枚举分类：状态机流转异常（`STATE_MACHINE_ERROR`）、格式不支持（`UNSUPPORTED_DOC_TYPE`）、解析失败（`DOC_PARSE_FAILED`）、文档未找到（`DOC_NOT_FOUND`）、权限拒绝（`DOC_ACCESS_DENIED`）与存储异常（`FILE_STORAGE_ERROR`）。

---

### 任务组 2：领域建模与持久层设计（DDD 充血模型）

- [x] **2.1 文档主聚合根设计 (`DocumentEntity`)**
    - 继承 `BaseEntity`，拥有 `workspaceId`、`categoryId`、`title`、`summary`、`status`（枚举）、`visibility`（公开/内部/私有）、
      `currentVersion`、`sourceType`（**唯一支持 IMPORT**，纯导入型知识库，所有文档均由外部文件导入经 DeepDoc 解析产生）等核心属性；
    - 充血方法封装：`submitForReview()`、`approve()`、`reject()`、`publish()`、`archive()`、`updateContent()`、
      `bindSourceFile()`，守护状态与版本约束。
- [x] **2.2 来源文件资产实体设计 (`DocSourceFileEntity`)**
    - 建立与主文档的 1:1 或 1:N 关联；
    - 包含字段：`workSpaceId`、`documentId`、`originalFileName`、`fileSize`、`fileExt`、`fileHash` (SHA-256 用于秒传与去重)、
      `storagePath` (rustfs 对象路径)、`parseStatus` (PENDING/SUCCESS/FAILED)、`rawText` (供后续 ES/RAG 检索索引)、
      `errorMessage`；
    - 充血方法：`markParseSuccess()`、`markParseFailed()`。
- [x] **2.3 文档分类树实体 (`DocCategoryEntity`)**
    - 继承 `BaseEntity`，支持树状目录：`workSpaceId`、`parentId`、`name`、`sortOrder`、`level`、`path`（物化路径如 `/1/4/12/`
      ，便于快速检索子树）。
- [x] **2.4 文档版本快照实体 (`DocVersionEntity`)**
    - 记录每次发布的历史快照：`documentId`、`versionNumber`（如 1, 2, 3...）、`versionTag`（如 `v1.0`）、`title`、`content`
      (Markdown 文本存储路径或大文本)、`changeSummary`、`publisherId`；
    - 提供只读保护与快速还原能力。
- [x] **2.5 文档标签与协同评论实体**
    - `DocTagEntity` 与 `DocTagRelationEntity`：支持打标签与按标签过滤；
    - `DocCommentEntity`：支持根评论、楼中楼嵌套回复、Markdown 语法支持与删除。
- [x] **2.6 Repository 接口定义**
    - 继承 `BaseRepository<T>`（自带 JPA + QueryDSL）：
        - `DocumentRepository`
        - `DocSourceFileRepository`
        - `DocCategoryRepository`
        - `DocVersionRepository`
        - `DocTagRepository`
        - `DocCommentRepository`

---

### 任务组 3：COLA 状态机引擎集成与流转管控
- [x] **3.1 定义状态机枚举与上下文**
    - `DocumentStateEnum`：`DRAFT` (草稿), `PENDING_REVIEW` (待审阅), `PUBLISHED` (已发布), `REJECTED` (已驳回), `ARCHIVED` (已归档)；
    - `DocumentEventEnum`：`SUBMIT` (提交审批), `APPROVE` (审批通过), `REJECT` (驳回修改), `PUBLISH_DIRECT` (草稿直发), `ARCHIVE` (归档), `REVERT_DRAFT` (重回草稿)；
    - `DocumentStateContext` (Java `record`：包含操作人 ID、角色、审批意见、操作时间)。
- [x] **3.2 配置 COLA StateMachine 转移矩阵**
    - 使用 Fluent API 构建完整状态流转图（DRAFT/PENDING_REVIEW/PUBLISHED/REJECTED/ARCHIVED）；
    - 针对非法流转进行异常拦截与业务提示。
- [x] **3.3 状态机执行器封装与 Spring 领域事件联动**
    - 编写 `DocumentStateMachineEngine`：封装 `fire(currentState, event, context)` 并防止并发重复初始化；
    - 状态成功流转后发布 `DocumentStateTransitionEvent` 领域事件，供后续 ES 索引与向量化切块异步监听。

---

### 任务组 4：MarkItDown / MinerU 通用文档解析防腐层（ACL）与多源接入
- [x] **4.1 定义解析防腐层通用入口与契约 (`DocParserGateway`)**
    - 核心统一入口：`DocParserGateway.parse(DocParseCommand command)`；
    - 输入命令 `DocParseCommand`（Java record：支持流/存储路径、扩展名、偏好引擎、OCR 选项等）；
    - 输出对象 `DocParseResult`（Java record：支持标准 Markdown 正文、纯文本、结构化元数据、标题推测、双轨制状态等）。
- [x] **4.2 实现多引擎 SPI 架构与 Mock/预留实现**
    - 定义 `DocParserEngine` SPI 接口与引擎枚举 `DocParserEngineEnum` (AUTO, MARKITDOWN, MINERU, TIKA, MOCK)；
    - `MockDocParserEngine`：纯文本（md/txt/html）100% 还原自由编辑、Word 模拟转标准 Markdown、PDF 默认只读预览双轨制 + 支持一键 OCR 提炼；
    - `MarkItDownDocParserEngine`：预留微软 MarkItDown 微服务适配器；
    - `MinerUDocParserEngine`：预留 MinerU 官方开源模型/云端 API 适配器；
    - `DefaultDocParserGateway`：动态策略路由分发器，业务层完全解耦，未来落地任何真实引擎零改动。
    - 预留基于 HTTP REST / gRPC 远程调用真实 Python `markitdown` 服务的配置开关（`knowflow.parser.mode=mock|remote`）。
- [x] **4.3 实现导入流水线编排服务 (`DocumentImportPipeline`)**
    - 步骤一：入库存储防腐层 `rustfs`，生成唯一文件存储路径与 SHA-256 哈希；
    - 步骤二：记录 `DocSourceFileEntity`，状态设为 PENDING；
    - 步骤三：根据格式路由：
        - 若为 Markdown/TXT/Word：调用 `DocParserGateway` 转换为 Markdown，直接落成 `DocumentEntity`（状态为 DRAFT），建立双向关联；
        - 若为 PDF：生成附件资产记录，默认作为只读附件；若用户指定“提取为文档”，异步触发提炼管线。

---

### 任务组 5：版本历史管理与多版本比对契约（前端 Diff 渲染最佳实践）

- [x] **5.1 版本快照生成机制**
    - 当文档正式发布（`PUBLISH`）或手动点击“创建里程碑版本”时，自动聚合当前文档标题、Markdown 正文、分类、元数据生成不可变快照记录；
    - 版本号生成算法（如语义化 `v1.0` -> `v1.1` 或累加整型版本序号）。
- [x] **5.2 多版本对比数据契约（交由前端 Diff 渲染）**
    - 采纳现代 Web 架构最佳实践（类似 GitHub / Notion / Monaco Diff Editor），将高 CPU 消耗的逐行 Diff 比对与高亮渲染交由前端完成；
    - 后端提供轻量化 `DocVersionCompareDTO`（包含双版本完整文本、版本 Tag、文档元数据），释放服务端堆内存与计算压力；
    - 移除后端 `java-diff-utils` 依赖，保持服务端精简高效。
- [x] **5.3 一键历史版本回滚用例**
    - 支持将文档正文一键还原为历史快照版本内容，创建新的编辑草稿并记录回滚操作日志。

---

### 任务组 6：草稿模块废除与生命周期极简收敛（架构认知纠偏）

> **架构决策**：知识库核心定位为外部文件资产导入（DeepDoc解析）、全文/向量检索与安全清理，彻底移除旧版基于 Redis 的协同草稿自动暂存（`kf:doc:draft:*`）机制，杜绝把知识库复杂化为协同文本编辑器的设计偏差。

---

### 任务组 7：组织管理、分类树与细粒度 ACL 访问控制

- [x] **7.1 分类树管理（Category Tree）**
    - 支持创建子分类、重命名、拖拽排序、层级移动；
    - 递归构建树形响应结构（返回树状 JSON），支持统计各分类下的文档总数。
- [x] **7.2 文档权限与 ACL 控制拦截器**
    - 根据文档公开级别进行权限判定：
        - `PUBLIC`：所有登录成员可读，权限按团队角色判定可写性；
        - `INTERNAL`：仅当前 Workspace 成员可读写；
        - `PRIVATE`：仅文档创建者、协作者或 Workspace Owner/Admin 有权查看与编辑；
    - 在服务层与用例编排层统一断言，提供安全隔离。

---

### 任务组 8：CQRS 服务分层与 API 暴露

- [x] **8.1 DTO / Request / Response（全 record 不可变设计）**
    - `DocumentUpdateRequest`（编辑/修改已有草稿正文与元数据，不允许凭空创建）、`DocumentPageRequest`
    - `DocumentImportRequest`（**唯一创建入口**，支持 Multipart 文件上传与元数据透传）
    - `DocDiffResponse`、`DocVersionResponse`、`CategoryTreeResponse`
- [x] **8.2 MapStruct 实体映射**
    - `DocumentMapper`、`DocCategoryMapper`、`DocVersionMapper`、`DocSourceFileMapper`
- [x] **8.3 CQRS 读写分离服务**
    - `DocumentCommandService`：文档创建、更新、删除、导入、状态机流转操作；
    - `DocumentQueryService`：文档详情、分页检索、分类树拉取、草稿获取；
    - `DocVersionQueryService` & `DocVersionCommandService`：版本列表、Diff 计算、版本回退；
    - `DocCommentService`：评论发表、回复、删除。
- [x] **8.4 Controller 层接口**
    - `DocumentController`（`/api/documents`）：文档核心操作
    - `DocumentCategoryController`（`/api/documents/categories`）：分类树维护
    - `DocumentVersionController`（`/api/documents/{docId}/versions`）：版本历史与 Diff

---

### 任务组 9：单元测试与领域行为覆盖

- [x] **9.1 状态机流转单元测试**
    - 覆盖正常流转（DRAFT -> PENDING -> PUBLISHED -> ARCHIVED）与非法逆向流转拦截；
- [x] **9.2 文档导入与解析 Mock 单元测试**
    - 测试 Word 转换、PDF 双轨制分支与 rustfs 路径绑定；
- [x] **9.3 版本快照与 Diff 比对测试**
    - 验证 Myers Diff 增删行解析正确性与一键回退行为；
- [x] **9.4 权限断言与异常分支测试**
    - 覆盖非团队成员访问私密文档的权限拦截。

---

## 四、 实施路线图（分步推进计划）

| 实施阶段                                      | 核心交付成果                                                                                                  | 关键依赖与输出物                                       |
|:----------------------------------------------|:--------------------------------------------------------------------------------------------------------------|:-------------------------------------------------------|
| **Step 2.1：基础脚手架与状态机引擎** [x]已完成 | 引入 COLA StateMachine，搭建状态机转移矩阵；扩展 rustfs 存储防腐层与通用解析入口 `DocParserGateway` 抽象。   | `pom.xml`, `cola-statemachine`, `FileStorageGateway`, `DocParserGateway` |
| **Step 2.2：充血模型与数据表落地** [x]已完成 | 完成 `DocumentEntity`、`DocSourceFileEntity`、`DocCategoryEntity`、`DocVersionEntity` 充血实体及 Repository。 | Entity 充血方法, MapStruct, JPA Repository             |
| **Step 2.3：多格式导入与 MarkItDown 接入** [x]已完成 | 实现文件上传入 rustfs、源文件元数据落库、Word/PDF 转换管线与双轨制策略。                                      | `DocParserGateway`, `MockDocParserAdapter`, 导入流水线 |
| **Step 2.4：版本快照、前端 Diff 契约与自动保存** [x]已完成 | 实现文档发布自动快照、提供前后端高效解耦的 `DocVersionCompareDTO` 比对契约。              | 快照生成, 前端 Diff 契约, Redis 自动保存, 快照回滚    |
| **Step 2.5：分类树、权限 ACL 与协作评论** [x]已完成 | 实现多级分类树递归查询、文档 ACL 访问断言、多级评论树与协同。                                                 | 分类树 API, ACL 拦截, 评论接口                         |
| **Step 2.6：服务编排、Controller 与单元测试** [x]已完成 | 完善 CQRS Command/Query 服务、RESTful 控制器，编写覆盖核心业务的 Mockito 单元测试。                           | REST 接口, Mockito 单元测试 (100% 关键分支)            |
| **Step 2.7：代码质量审查与架构加固** [x]已完成 | 修复事务代理失效、动态角色上下文、读写异步解耦、不可变实体、状态守卫、脱敏与测试扩展。 | 54 个单元测试 100% 通过，规则沉淀至 AGENTS.md |

---

## 五、 代码质量审查与架构加固记录（2026-09-30 交付）

经过对阶段二全生命周期实现文件的全面工程审查，完成以下 8 大核心缺陷修复与加固：

1. **Spring AOP Self-Invocation 事务代理失效加固**：
   - 提取独立的 `DocumentImportTransactionalService`，将来源文件初始入库、主文档落库与 PDF 提炼三处小事务彻底独立为 Spring Bean，消除 `this.method()` 调用导致的事务失效隐患。
2. **状态机操作人角色动态解析（消除硬编码）**：
   - 在 `DocAccessControlService` 中新增 `resolveUserRole()`，在 `DocumentCommandService.transitionState()` 中根据当前用户在所属工作空间的真实身份（OWNER / ADMIN / MEMBER）动态装配，杜绝权限降级或鉴权漏洞。
3. **读写分离与高并发行锁争用解耦**：
   - 将 `DocumentQueryService.getById()` 调整为严格 `@Transactional(readOnly = true)`；
   - 阅读量递增解耦为发布 `DocumentReadEvent` 领域事件，由 `@Async @EventListener` 异步调用 `DocumentRepository.incrementReadCount()` 原子更新，杜绝高并发读引发主表行锁争用。
4. **并发计数原子化（防 Lost Update）**：
   - 在 `DocumentRepository` 新增 `@Modifying @Query` 原生原子更新方法（`incrementReadCount` / `incrementLikeCount`），消灭应用内存读-改-写竞态。
5. **不可变快照与状态机防重入守卫**：
   - `DocVersionEntity` 标注 Hibernate `@Immutable`，数据库层面阻止快照内容被意外修改；
   - `DocSourceFileEntity` 增加状态机逆向流转防御（已 `SUCCESS` / `PREVIEW_ONLY` 不可逆转为 `FAILED`），并 DRY 抽取 `applyParseResult()`；
   - `DocumentStateMachineEngine` 采用动态实例 `machineId`，彻底隔离单元测试与并发执行时的闭包依赖污染。
6. **Controller 纯粹性与分层边界治理**：
   - `DocVersionService.rollbackToVersion()` 统一在 Service 层完成 `DocumentEntity → DocumentDTO` 转换；
   - `DocumentVersionController` 移除 `DocumentMapper` 依赖，移除冗余 `/diff` 路由别名，不再在 Controller 手动拆包 Request。
7. **数据传输对象（DTO）敏感信息脱敏**：
   - `DocSourceFileDTO` 剔除底层基础设施存储路径 `storagePath`，仅对外暴露安全可访问的 `storageUrl`；
   - `DocCategoryMoveRequest` 放开 `newParentId` 的 `@NotNull` 约束，原生支持移动至根节点（`0L`）。
8. **测试套件全量覆盖与校验**：
   - 补充 `DocumentImportPipelineTest` 文件存储上传失败与解析失败异常流测试；
   - 补充 `DocumentStateMachineEngineTest` 非法状态逆向流转（草稿不能审批、已归档不能提交、已发布不能重复审批等）；
   - 阶段二 54 个单元测试全部通过（`mvn test -Dtest="*Document*,*Doc*"` BUILD SUCCESS）。
