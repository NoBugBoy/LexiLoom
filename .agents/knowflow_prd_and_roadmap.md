# KnowFlow 知识库管理系统 —— 需求规格说明书 (PRD) 与实现路线图

> **主包名**：`io.knowflow`  
> **设计模式**：模块化单体架构（Modular Monolith，具备随时平滑拆分为微服务的能力）  
> **核心定位**：集文档全生命周期管理、多格式解析、ES全文检索、RAG向量双路召回与Neo4j知识图谱（KAG）于一体的新一代智能知识库。
> **分离原则**：把可以留给前端的计算逻辑不要在后端做（这部分留给前端并未实现的要标记一下），比如diff操作，优先白嫖用户cpu，但是注意分寸，特别繁重的不要在前端
---

## 一、 系统架构重构：微服务转单机模块化

原方案由 10 个独立微服务构成。在单机单体阶段，我们将服务边界映射为 **内部独立业务模块（Package/Module）**，保持高内聚、低耦合：

```
io.knowflow
├── KnowFlowApplication.java         // 单体统一启动入口
│
├── common                            // 原 kb-common (全局异常/Result/JWT/雪花ID/AOP)
├── framework                         // 基础设施配置 (Security, Redis, ES, Neo4j, Async)
│
├── module-auth                       // 原 kb-gateway + kb-user-auth (认证/鉴权/团队/用户)
├── module-document                   // 原 kb-document (文档CRUD/分类树/版本Diff/审阅流/评论)
├── module-file                       // 原 kb-file (文件上传/Tika格式解析/多媒体转码)
├── module-search                     // 原 kb-search (Elasticsearch全文检索/高亮/补全)
├── module-ai                         // 原 kb-ai (多模型Chat/文档切块/向量化/RAG问答)
├── module-graph                      // 原 kb-graph (Neo4j图谱/实体抽取/路径遍历/KAG融合)
├── module-statistics                 // 原 kb-statistics (看板数据聚合/热点分析)
└── module-system                     // 原 kb-foundation (系统配置/WebSocket推送/操作审计)
```

### 1.1 关键通信机制简化（单机替代方案）

* **API Gateway 鉴权** $\rightarrow$ 由 Spring Security + `JwtAuthenticationFilter` 统一在应用层拦截。
* **RabbitMQ 异步事件** $\rightarrow$ 采用 Spring 内置的 **`ApplicationEventPublisher`** + `@Async`
  异步事件驱动（保留解耦接口，后续可一行配置无缝切回 RabbitMQ）。
* **跨服务 RPC 调用** $\rightarrow$ 模块之间直接通过 Spring Service 接口依赖注入调用，消除网络与序列化开销。

---

## 二、 核心功能模块详细需求规范

### 1. 用户与团队权限模块 (`module-auth`)

* **账号体系**：邮箱注册、激活验证码、登录、密码重置、JWT 无状态 Token 签发与刷新。
* **RBAC 权限模型**：
    * 系统角色：超级管理员、知识库管理员、普通成员、访客。
    * 团队（Workspace）：多租户隔离空间，支持团队成员邀请与角色分配。
    * 文档级 ACL 细粒度控制：公开（Public）、团队内部（Internal）、私密指定人（Private）。

### 2. 文档全生命周期与协同模块 (`module-document`)

> **核心定位**：知识库是一个**内容摄入与沉淀中心**，所有文档必须通过**外部文件导入**进入系统，全流程由 **DeepDoc** 驱动解析，不提供空白手动在线创建功能。

* **内容摄入来源（唯一核心来源）**：
    * **外部文件导入**：支持 PDF、Word (DOCX)、PPT (PPTX)、Excel (XLSX)、Markdown、TXT 等格式批量导入；全流程由 **DeepDoc** 视觉版面识别引擎（YOLO版面感知 + KMeans行距聚类）深度驱动解析，提取干净 Markdown 正文与语义原子切片，导入后自动沉淀为可二次编辑的草稿。
* **状态流转机**：
  $$\text{草稿 (DRAFT)} \xrightarrow{\text{提交}} \text{待审阅 (PENDING)} \xrightarrow[\text{驳回}]{\text{审批通过}} \text{已发布 (PUBLISHED)} \xrightarrow{\text{归档}} \text{已归档 (ARCHIVED)}$$
* **Markdown 编辑与自动保存**：
    * 文件导入解析完成后进入 DRAFT 状态，支持用户在 Markdown 编辑器中二次编辑与修改；
* **版本历史与 Diff 回溯**：
    * 每次正式发布或手动保存快照，生成唯一版本号（`v1.0`, `v1.1`）。
    * 支持两两版本间的 Myers Diff 算法对比（删除行红标、新增行绿标），支持一键回滚历史版本。
* **审阅流与协同**：
    * 支持多级审批流指派；
    * 嵌套评论树（支持 @成员、Markdown 语法回复、点赞、文档收藏）。

### 3. 文件处理引擎 (`module-file`)

* **统一对象存储适配**：支持 Local 本地磁盘存储、MinIO 或 S3 协议（开箱即用）。
* **Apache Tika 文本提取**：
    * 支持 PDF、Word (DOC/DOCX)、Excel、PowerPoint、HTML、TXT、Markdown 等 25+ 种格式。
    * 自动抽取正文文本、元数据（作者、标题、创建时间），供后续搜索和 RAG 切块。
* **多媒体处理**：集成 FFmpeg，支持音视频文件的封面截帧与异步转码。

### 4. 全文检索引擎 (`module-search`)

* **底层驱动**：Elasticsearch 8.x。
* **核心能力**：
    * 倒排索引：文档标题、正文、标签、分类全字段分词索引（IK 分词器）。
    * 关键词高亮（Highlighting）。
    * 搜索建议（Suggester）与拼音/前缀自动补全。
    * 用户历史搜索记录（基于 Redis 热点存储 + DB 归档）。

### 5. AI 对话与 RAG 检索增强生成 (`module-ai`)

* **模型集成**：支持统一接口调用 DeepSeek-V3/R1、Qwen（通义千问）或本地 Ollama。
* **DeepDoc 语义原子切块与上下文帽子拼接**：
    * 摒弃盲目的字符滑动窗口与 Overlap；基于 DeepDoc（YOLO版面感知 + KMeans行距聚类）直接以识别出的原子段落（DocTextChunk）为切块单元；
    * 上下文帽子拼接：在向量化与检索前拼接"[文档: 《{title}》] > [章节: {headerPath}]"上下文前缀，并保留 page 与 bbox 坐标溯源。
* **向量化（Embedding）**：调用 Embedding 接口生成 Dense Vector，写入 ES 密集向量索引或向量数据库。
* **双路召回与混合重排（Hybrid Search with RRF）**：
    * 路径 1：BM25 关键词全文检索；
    * 路径 2：Cosine 向量语义相似度检索；
    * 融合：利用 **RRF（Reciprocal Rank Fusion）** 算法重排，解决专业术语匹配与语义理解的权衡问题。
* **上下文组装与流式问答**：
    * SSE（Server-Sent Events）流式推送到前端；
    * 答案强制标注**引用源片段（Source Citation）**与原文定位跳转。

### 6. KAG 知识图谱与图检索 (`module-graph`)

* **存储引擎**：Neo4j 图数据库。
* **实体与关系抽取**：通过 Prompt 驱动大模型从文档切块中提取 `(实体1)-[关系]->(实体2)`。
* **图遍历与推理**：
    * 挖掘跨文档的隐式逻辑（例如：“A 文档提到了张三负责项目 X，B 文档提到了项目 X 依赖技术 Y，从而推导出张三懂技术 Y”）。
* **GraphRAG 融合**：问答时同时召回向量切块与图谱子图，作为 Context 喂给 LLM。

### 7. 数据分析看板与系统审计 (`module-statistics` & `module-system`)

* **指标看板**：文档总量、日增阅读量、活跃用户 Top 榜、热点文档排行、分类占比扇形图。
* **实时推送**：基于 Spring WebSocket (STOMP 协议)，推送审阅待办、审批结果、系统公告。
* **全链路审计**：基于自定义 AOP 注解 `@LogOperation` 自动采集操作人、IP、入参出参及耗时，异步入库。

---

## 三、 数据存储全景图

| 存储中间件         | 承担职责                                           | 选型建议                               |
|:-------------------|:---------------------------------------------------|:---------------------------------------|
| **关系型主库**     | 用户、权限、团队、文档状态元数据、分类树、操作日志 | **PostgreSQL** 或 **MySQL 8.0**        |
| **内容存储库**     | 文档 Markdown 原始富文本内容、版本快照快照数据     | **MongoDB** (或 PostgreSQL JSONB 字段) |
| **高速缓存**       | Token 校验、验证码、编辑自动保存暂存、热点文档计数 | **Redis** (本地或 Upstash)             |
| **全文与向量检索** | 文档关键词倒排索引、Chunk 密集向量索引（双路召回） | **Elasticsearch 8.x**                  |
| **知识图谱**       | 文档实体节点、概念关系边、图推理查询               | **Neo4j**                              |
| **文件对象存储**   | 用户上传的 PDF、Word 原件、音视频媒体附件          | **MinIO** 或 本地文件系统目录          |

---

## 四、 建议实现步骤（循序渐进里程碑）

按照敏捷迭代思想，分为 **6 个阶段** 逐步实现：

### 阶段一：项目骨架与基础设施基座（第 1 ~ 3 天）

- [ ] 创建 Maven/Gradle 多模块单体工程，定义统一主包名 `io.knowflow`。
- [ ] 封装 `common` 模块：统一响应体 `Result<T>`、全局异常拦截器、业务错误码枚举。
- [ ] 配置 PostgreSQL/MySQL 连接与 MyBatis-Plus 代码生成器。
- [ ] 整合 Spring Security + JWT，实现用户注册、登录、获取当前用户上下文（`UserContext`）。

### 阶段二：文档核心生命周期与版本回溯（第 4 ~ 8 天）

- [ ] 设计文档核心表：`t_kb_document`、`t_kb_category`、`t_kb_doc_version`、`t_kb_doc_source_file`。
- [ ] 实现文档分类树递归查询。
- [ ] 实现文件上传导入管线（**唯一文档创建入口**）：Word/PDF/Markdown/TXT → MarkItDown 解析 → Markdown 落库为草稿（`sourceType=IMPORT`）。
- [ ] 实现文档状态机流转：草稿 $\rightarrow$ 提交审批 $\rightarrow$ 审核通过/驳回 $\rightarrow$ 发布。
- [ ] 集成版本快照存储与 Myers Diff 比对算法接口（返回两个版本差异行）。

### 阶段三：文件解析与 Elasticsearch 全文检索（第 9 ~ 13 天）

- [ ] 封装文件上传接口（Local/MinIO 存储适配）。
- [ ] 引入 **Apache Tika**，编写统一解析工具类，实现上传 PDF/Word 后自动提取纯文本。
- [ ] 搭建 Elasticsearch，设计 `knowflow_doc_index` 索引结构。
- [ ] 监听文档发布事件（Spring Event），异步将文档内容同步至 ES 索引库。
- [ ] 实现关键词搜索接口：支持分词匹配、标题正文高亮显示（Highlighting）、搜索历史记录。

### 阶段四：AI 智能问答与 RAG 落地（第 14 ~ 20 天）

> **AI 核心库规范**：**AI 模块必须基于 Spring AI 官方框架 (`spring-ai-core` / `spring-ai-bom`) 构建**，统一基于 `ChatModel` / `StreamingChatModel` / `ChatClient` 及 `EmbeddingModel` 契约驱动大模型与向量化交互，杜绝手写裸 HTTP 请求。

- [ ] 引入并集成 **Spring AI**（`spring-ai-bom` / `spring-ai-core`），统一抽象 LLM 与 Embedding 模型接入层。
- [ ] 基于 Spring AI 对接 LLM API（DeepSeek / Qwen / Ollama），实现 Spring WebMvc / SSE 流式对话接口（支持思维链推理与思考过程流式推送）。
- [ ] 编写 DeepDoc 上下文帽子组装器（DeepDocContextEnricher），保护原子段落语义边界，拼接文档名与章节前缀并保留视觉坐标。
- [ ] 基于 Spring AI `EmbeddingModel` 对接文本向量化模型，将切片向量化并存入 ES 的 `dense_vector` 字段。
- [ ] 实现 **双路混合检索召回**：BM25 文本分值 + 向量余弦相似度分值通过 RRF 公式加权归一。
- [ ] 组装 Prompt 提示词，实现携带召回切片上下文的流式 RAG 问答，并在末尾输出高保真引用源与视觉坐标。

### 阶段五：Neo4j 知识图谱与 KAG 增强（第 21 ~ 26 天）

- [ ] 部署并连接 Neo4j 数据库，设计 Node（文档、实体、标签）与 Relationship（引用、属于、包含）。
- [ ] 设计实体抽取 Prompt，让大模型在切块完成后自动提取 JSON 格式的三元组 `(Subject, Predicate, Object)`。
- [ ] 将三元组写入 Neo4j，构建多文档交叉实体图谱。
- [ ] 实现知识图谱查询接口（Cypher 语句）：支持根据某个实体展开关联节点与探索路径。
- [ ] 优化 RAG 流程为 KAG：问答时先通过图谱拓展关联实体，再与向量召回结果合并注入提示词。

### 阶段六：看板统计、实时推送与系统审计（第 27 ~ 30 天）

- [ ] 集成 Spring WebSocket (STOMP)，当文档被提交审阅或被驳回时，向对应用户实时推送弹窗通知。
- [ ] 实现数据看板接口：聚合统计文档阅读排行、分类占比、发布趋势。
- [ ] 编写自定义 `@LogOperation` 切面，异步持久化操作日志，完成系统审计闭环。
- [ ] 进行全链路集成测试与性能压测。
