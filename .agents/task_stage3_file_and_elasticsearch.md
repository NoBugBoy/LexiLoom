# 阶段三：Elasticsearch 全文检索接入（任务实施全景清单）

> **所属项目**：LexiLoom 知识库管理系统
> **模块定位**：**内容检索底座（Content Retrieval Foundation）**
> **架构技术规范**：JDK 25 + Spring Boot 4.x + Spring Modulith + Spring Data JPA + CQRS
> + Spring Data Elasticsearch + MapStruct
>
> **阶段时间范围（参考）**：第 9 ~ 13 天

---

## 一、 阶段二已完成交付物（阶段三上游依赖）

> 以下为项目中**已实际落地**的代码，阶段三直接复用，无需重复开发。

| 已完成模块 | 关键类 | 说明 |
|:-----------|:-------|:-----|
| 文件存储防腐层 | `FileStorageGateway` / `RustfsStorageAdapter` / `LocalStorageAdapter` | 文件上传/下载/检测，阶段三直接复用 |
| 解析防腐层 SPI | `DocParserGateway` / `DefaultDocParserGateway` | 统一解析入口，AUTO 路由已按扩展名分发 |
| MarkItDown 引擎 | `MarkItDownDocParserEngine` | Office 系列（docx/xlsx/pptx）解析，已预留 HTTP 端点配置 |
| MinerU 引擎 | `MinerUDocParserEngine` | PDF 高精度解析，已预留 endpoint + api-key 配置 |
| Mock 降级引擎 | `MockDocParserEngine` | md/txt/docx/pdf 全支持，远程引擎未配置时自动委托 |
| 导入流水线 | `DocumentImportPipeline` | 完整编排：sha256 秒传 → 存储 → 解析 → 落库 → 状态回写 |
| 状态机事件 | `DocumentStateTransitionEvent` | 状态流转成功后发布，阶段三用于触发 ES 异步同步 |
| 领域实体 | `DocumentEntity` / `DocSourceFileEntity` | 已具备充血方法，持有 `rawText`、`wordCount` 等 ES 所需字段 |

> [!NOTE]
> 解析引擎层（MarkItDown/MinerU/Mock）无需在阶段三改动，当前 Mock 降级已能支撑开发联调。真实引擎的 HTTP 调用实现预留在阶段三结束后或独立迭代中对接。

---

## 二、 阶段三目标范围

```
文档状态流转（阶段二）
        │  DocumentStateTransitionEvent(PUBLISHED)
        ▼
┌─────────────────────────────────────────────────────────────┐
│                    阶段三：内容检索底座                       │
│                                                             │
│  ┌───────────────────────────────────────────────────┐      │
│  │  Elasticsearch 8.x   lexiloom_doc_index            │      │
│  │  IK 分词 + 高亮 + Suggester + dense_vector(预留)   │      │
│  └──────────────────────────┬────────────────────────┘      │
│                             │                               │
│  ┌──────────────────────────▼────────────────────────┐      │
│  │  搜索接口层                                         │      │
│  │  关键词全文搜索 / 高亮 / 分页 / 自动补全 / 搜索历史  │      │
│  └───────────────────────────────────────────────────┘      │
└─────────────────────────────────────────────────────────────┘
        │  （为阶段四铺路）
        ▼
dense_vector 字段预留 → RAG 双路召回
```

---

## 三、 关键设计约定

1. **ES 索引同步触发点**：监听 `DocumentStateTransitionEvent`，仅关注两个状态变化：
   - `→ PUBLISHED`：写入/覆盖 ES 索引
   - `→ ARCHIVED` 或 `→ DRAFT`（退回草稿）：从 ES 删除索引

2. **索引文档的内容来源**：
   - 正文 `content`：`DocumentEntity.bodyContent`（Markdown 原文）
   - 原文纯文本 `rawText`：`DocSourceFileEntity.rawText`（Tika/MarkItDown/MinerU 提取的纯文本，供全文检索）
   - 两者均建立全文索引，搜索结果高亮优先展示 `title` > `rawText` > `content`

3. **IK 分词器约定**：
   - 索引时：`ik_max_word`（最细粒度）
   - 查询时：`ik_smart`（最粗粒度，召回率与精准度平衡）

4. **`dense_vector` 字段预留**：阶段三 Mapping 中声明 `content_vector`（dim=1536），阶段四写入，避免后期改 Mapping 触发索引重建。

5. **幂等同步**：使用 `documentId`（Long 转 String）作为 ES `_id`，重复发布自然覆盖，天然幂等。

6. **搜索必须带 `workspaceId` 过滤**：所有检索请求强制带 `workspaceId` term filter，避免跨工作区数据泄露。

---

## 四、 详细任务分解清单（Task Breakdown）

### 任务组 1：依赖引入与基础设施配置

- [x] **1.1 引入 Spring Data Elasticsearch 依赖**
  - `pom.xml` 引入 `spring-boot-starter-data-elasticsearch`，确认版本与 Spring Boot 4.x 兼容。
  - 新增版本变量 `<elasticsearch.version>` 统一管理。

- [x] **1.2 配置 ES 连接**
  - `application.yml` 新增：
    ```yaml
    spring:
      elasticsearch:
        uris: ${ES_URIS:http://localhost:9200}
        socket-timeout: 30s
        connection-timeout: 5s
    lexiloom:
      elasticsearch:
        index-name: lexiloom_doc_index
        auto-create-index: true
    ```
  - 新增 `ElasticsearchProperties`（`@ConfigurationProperties(prefix = "lexiloom.elasticsearch")`）。

- [x] **1.3 新增 ES 相关错误码**
  - 在 `ErrorCode.java` 的 `Document` 枚举中追加：
    - `ES_INDEX_FAILED`：ES 索引写入失败
    - `ES_SEARCH_FAILED`：ES 检索执行失败

---

### 任务组 2：Elasticsearch 文档实体与索引设计

- [x] **2.1 设计 `DocSearchDocument`（ES 文档实体）**
  - 路径：`document/search/DocSearchDocument.java`
  - 使用 `@Document(indexName = "#{@elasticsearchProperties.indexName}")`（动态引用配置）

  | 字段名 | ES 类型 | Analyzer | 说明 |
  |--------|---------|----------|------|
  | `id` | `keyword` | — | 与 `documentId` 对应，用作 `_id` |
  | `workspaceId` | `keyword` | — | 工作区隔离，所有查询必带 filter |
  | `title` | `text` | `ik_max_word` / `ik_smart` | boost=3，高亮优先 |
  | `summary` | `text` | `ik_max_word` / `ik_smart` | boost=2 |
  | `content` | `text` | `ik_max_word` / `ik_smart` | Markdown 正文，boost=1 |
  | `rawText` | `text` | `ik_max_word` / `ik_smart` | 原文件纯文本（来自 DocSourceFileEntity.rawText） |
  | `titleSuggest` | `completion` | — | 用于标题自动补全 Suggester |
  | `tags` | `keyword[]` | — | 标签过滤 |
  | `categoryId` | `keyword` | — | 分类过滤 |
  | `sourceType` | `keyword` | — | MANUAL / IMPORT / AI_GENERATED |
  | `authorId` | `keyword` | — | 创建人 userId |
  | `status` | `keyword` | — | 冗余存储，仅 PUBLISHED 状态写入 |
  | `publishedAt` | `date` | — | 发布时间，用于时间排序 |
  | `contentVector` | `dense_vector(1536)` | — | **阶段四填充**，阶段三写 `null` 占位 |

- [x] **2.2 定义 `DocSearchRepository`**
  - 路径：`document/search/DocSearchRepository.java`
  - 继承 `ElasticsearchRepository<DocSearchDocument, String>`
  - 补充自定义方法：
    - `void deleteByWorkspaceIdAndId(String workspaceId, String id)` — 按工作区安全删除

- [x] **2.3 ES 索引自动初始化**
  - 路径：`document/search/ElasticsearchIndexInitializer.java`
  - `@PostConstruct` + `@ConditionalOnProperty(name = "lexiloom.elasticsearch.auto-create-index", havingValue = "true")`
  - 通过 `IndexOperations` 检查索引是否存在，不存在则创建并应用 Mapping（含 IK 分词器 settings）。

---

### 任务组 3：ES 索引同步——异步事件监听器

- [x] **3.1 实现 `DocumentSearchSyncListener`**
  - 路径：`document/search/DocumentSearchSyncListener.java`
  - `@EventListener` + `@Async` 监听 `DocumentStateTransitionEvent`
  - 路由逻辑：
    ```java
    switch (event.targetState()) {
        case PUBLISHED  -> indexDocument(event.documentId());
        case ARCHIVED,
             DRAFT      -> removeFromIndex(event.documentId());
        default         -> { /* 其他状态不处理 */ }
    }
    ```
  - `indexDocument` 内部步骤：
    1. `DocumentRepository.findById`（含 PUBLISHED 状态校验）
    2. `DocSourceFileRepository.findByDocumentId`（可选，获取 `rawText`）
    3. `DocSearchMapper.toSearchDoc(doc, sourceFile)` 映射
    4. `DocSearchRepository.save(searchDoc)` 写入 ES
  - 异常处理：记录 `log.error`，不影响主流程（已在独立 `@Async` 线程中隔离）。

- [x] **3.2 新增 `DocSearchMapper`（MapStruct）**
  - 路径：`document/mapper/DocSearchMapper.java`
  - 映射规则：
    - `DocumentEntity` → `DocSearchDocument` 主字段映射
    - `DocSourceFileEntity.rawText` → `DocSearchDocument.rawText`（`@Mapping(source = "sourceFile.rawText", target = "rawText")`）
    - `publishedAt` 取 `DocumentEntity.updateTime`（状态变为 PUBLISHED 时更新）
    - `titleSuggest` 由 `title` 自动填充（`afterMappingMethod`）

---

### 任务组 4：搜索 DTO 与接口实现（Query 侧）

- [x] **4.1 定义搜索 DTO（全 record 设计）**
  - `DocumentSearchRequest`（record）：
    - `keyword`（`@NotBlank`）、`workspaceId`（`@NotNull`）、`categoryId`（可选）
    - `tags`（`List<String>`，可选）、`pageNum`（default=1）、`pageSize`（default=20）
    - `sortBy`（`enum：RELEVANCE / PUBLISHED_AT`，default=RELEVANCE）
  - `DocumentSearchResponse`（record）：`total`、`pages`、`items: List<DocSearchItemVO>`
  - `DocSearchItemVO`（record）：
    - `id`、`title`（带高亮 `<em>` 片段）、`summary`（带高亮）
    - `hitSnippet`（正文/rawText 命中摘要，最多 200 字，带高亮）
    - `categoryId`、`tags`、`sourceType`、`publishedAt`

- [x] **4.2 实现 `DocumentSearchQueryService`**
  - 路径：`document/service/DocumentSearchQueryService.java` + `impl/`
  - 使用 `NativeQuery` + `ElasticsearchTemplate` 构建查询：
    ```
    Bool Query:
      must:
        multi_match(keyword, fields=[title^3, summary^2, content^1, rawText^1])
      filter:
        term(workspaceId)          ← 必须
        term(status=PUBLISHED)     ← 必须
        term(categoryId)           ← 可选
        terms(tags)                ← 可选
    highlight:
        title, summary, content, rawText
        pre_tag=<em class="hl">, post_tag=</em>
    sort:
        _score desc（RELEVANCE）或 publishedAt desc（PUBLISHED_AT）
    from/size 分页
    ```
  - 搜索完成后**异步**调用 `SearchHistoryService.record(userId, keyword)`（非阻塞）。

- [x] **4.3 实现 `SearchSuggestQueryService`（自动补全）**
  - 基于 `DocSearchDocument.titleSuggest`（`@CompletionField`）实现 Suggester 查询。
  - 返回最多 10 条 `List<String>` 候选词，供前端搜索框实时提示。

- [x] **4.4 实现 `SearchHistoryService`**
  - 路径：`document/service/SearchHistoryService.java`
  - **写入**：Redis `ZSet`，Key = `ll:search:history:{userId}`，score = 当前时间戳，member = keyword
    - 写入后立即 `ZREMRANGEBYRANK` 保留最新 20 条
  - **读取**：`ZREVRANGE` 倒序取最近 N 条（默认 10）
  - **删除单条**：`ZREM`
  - **清空**：`DEL`
  - **DB 异步归档**：每次写入发布 `SearchHistoryRecordEvent`，由 `@EventListener @Async` 异步落库至 `ll_search_history`，防 Redis 丢失。

---

### 任务组 5：数据库补充（搜索历史归档表）

- [ ] **5.1 Flyway 迁移脚本（按要求暂缓落地）**
  - 文件：`V3__add_search_history.sql`
  - 建表：`ll_search_history`
    ```sql
    CREATE TABLE ll_search_history (
        id            BIGSERIAL    PRIMARY KEY,
        user_id       BIGINT       NOT NULL,
        workspace_id  BIGINT       NOT NULL,
        keyword       VARCHAR(200) NOT NULL,
        search_at     TIMESTAMP    NOT NULL DEFAULT now()
    );
    CREATE INDEX idx_search_history_user ON ll_search_history (user_id, search_at DESC);
    ```

- [x] **5.2 `SearchHistoryEntity` + `SearchHistoryRepository`**
  - 仅用于异步归档，无需充血方法（纯数据记录，`@Entity` + 静态工厂 `of(userId, workspaceId, keyword)` 即可）。
  - 已补充 `SearchHistoryArchiveListener` 监听领域事件异步落库。

---

### 任务组 6：Controller 接口暴露

- [x] **6.1 新增 `DocumentSearchController`**
  - 路径：`document/controller/DocumentSearchController.java`
  - 基础路径：`/api/search`

  | Method | Path | 参数 | 说明 |
  |--------|------|------|------|
  | `GET` | `/api/search` | `DocumentSearchRequest`（`@Valid`） | 全文搜索，返回高亮分页结果 |
  | `GET` | `/api/search/suggest` | `keyword`、`workspaceId` | 搜索建议，返回 `List<String>` |
  | `GET` | `/api/search/history` | `workspaceId`、`size`（default=10） | 当前用户搜索历史 |
  | `DELETE` | `/api/search/history/{keyword}` | — | 删除单条历史记录 |
  | `DELETE` | `/api/search/history` | — | 清空搜索历史 |

---

### 任务组 7：单元测试

> 测试范围：核心业务逻辑，Mock ES / Redis 外部依赖。

- [x] **7.1 `DocumentSearchSyncListener` 测试**
  - Mock `DocumentRepository`、`DocSourceFileRepository`、`DocSearchRepository`
  - 覆盖：`PUBLISHED` 事件 → 索引写入调用；`ARCHIVED` 事件 → 删除调用；`PENDING_REVIEW` 事件 → 无操作

- [x] **7.2 `DocumentSearchQueryService` 测试**
  - Mock `ElasticsearchTemplate` 返回固定 `SearchHits`
  - 验证：高亮字段正确提取并映射到 `hitSnippet`；`workspaceId` filter 必须存在于构建的 Query 中；`SearchHistoryService.record` 被异步调用

- [x] **7.3 `SearchHistoryService` 测试**
  - Mock `RedisTemplate`（`ZSetOperations`）
  - 覆盖：写入 + 超 20 条自动淘汰、倒序读取、删除单条、清空

- [x] **7.4 `DocumentSearchController` 测试**
  - Mock `DocumentSearchQueryService`、`SearchSuggestQueryService`、`SearchHistoryService`
  - 覆盖：5 个 REST 路由的成功调用、参数绑定、`@Valid` 参数校验拦截与用户安全上下文集成

---

## 五、 数据流全景图

```
① 文档审批通过 → DocumentStateMachineEngine.fire(APPROVED)
        │
        ▼ DocumentStateTransitionEvent(targetState=PUBLISHED)

② DocumentSearchSyncListener [@Async]
        │
        ├─ DocumentRepository.findById(docId)
        ├─ DocSourceFileRepository.findByDocumentId(docId)  ← 可选，获取 rawText
        ├─ DocSearchMapper.toSearchDoc(doc, sourceFile)
        └─ DocSearchRepository.save(searchDoc) ────────────► ES lexiloom_doc_index

③ 用户搜索请求
   GET /api/search?keyword=知识管理&workspaceId=1
        │
        ▼ DocumentSearchQueryService
        ├─ NativeQuery(multi_match + filter + highlight)
        ├─ ElasticsearchTemplate.search(query)
        ├─ 映射高亮结果 → DocSearchItemVO
        ├─ [Async] SearchHistoryService.record(userId, keyword) → Redis ZSet + DB 归档
        └─ 返回 DocumentSearchResponse(total, items)
```

---

## 六、 实施路线图（分步推进计划）

| 实施步骤 | 核心交付成果 | 关键文件 |
|:---------|:------------|:---------|
| **Step 3.1：依赖与基础设施** [x]已完成 | 引入 ES 依赖，配置连接，新增错误码，`ElasticsearchProperties` | `pom.xml`、`application.yml`、`ErrorCode` |
| **Step 3.2：ES 实体与 Mapping** [x]已完成 | `DocSearchDocument` 设计，`DocSearchRepository`，索引自动初始化 | `DocSearchDocument`、`ElasticsearchIndexInitializer` |
| **Step 3.3：异步索引同步** [x]已完成 | 监听 `DocumentStateTransitionEvent`，异步写入/删除 ES | `DocumentSearchSyncListener`、`DocSearchMapper` |
| **Step 3.4：搜索接口实现** [x]已完成 | 关键词高亮搜索、自动补全 Suggester | `DocumentSearchQueryService`、`SearchSuggestQueryService` |
| **Step 3.5：搜索历史** [x]已完成 | Redis ZSet + DB 异步归档 (Flyway 按需延后) | `SearchHistoryService`、`SearchHistoryEntity`、`SearchHistoryArchiveListener` |
| **Step 3.6：Controller 接口** [x]已完成 | 5 个 REST 接口暴露 | `DocumentSearchController` |
| **Step 3.7：单元测试** [x]已完成 | 覆盖同步监听、搜索服务、历史服务、控制器核心分支 | `*Test.java` |
