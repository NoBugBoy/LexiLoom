# LexiLoom 知识库检索 Skill 契约接口规范 (API Spec)

为了让 CLI、Agent 及外部脚本无需依赖复杂的前端 Session/Cookie，本规范定义了供 Skill 调用的**无状态、轻量级 REST API**。

---

## 1. 鉴权机制 (Authentication)

* **请求头**：`Authorization: Bearer <API_KEY>` 或 `X-API-Key: <API_KEY>`
* **Key 格式**：`lx_live_xxxxxxxxxxxxxxxxxxxxxxxx`
* **权限模型**：
  * **管理员全局唯一 API Key**：每个后台管理员用户在全局配置中维护唯一一个永久有效的 API 密钥；
  * **永不过期**：密钥等同于永久 Token，无需频繁刷新；
  * **安全轮转与吊销**：如果密钥意外泄漏，管理员可在「管理中心 / 全局设置 → API 密钥」一键重新生成（轮转），旧密钥立即失效并清空缓存；也可以主动吊销；
  * 请求携带此 Key 时，服务端 `AuthenticationFilter` 校验通过后自动注入当前管理员的 `LoginUserAuthentication`（含所属管理员角色 `ADMIN` / `SUPER_ADMIN` 等）。

---

## 2. API 密钥管理端点 (Admin API Key Management)

供 Web 管理后台控制台或客户端管理全局 API 密钥使用：

### ① 查询当前管理员的 API 密钥
* **路径**：`GET /admin/api-key`（亦支持兼容别名 `GET /user/api-key`）
* **响应**：
```json
{
  "hasApiKey": true,
  "apiKey": "lx_live_098f6bcd4621d373cade4e832627b4f6",
  "name": "Default CLI Key",
  "createTime": "2026-10-22 08:30:00",
  "updateTime": "2026-10-22 08:30:00",
  "lastUsedTime": "2026-10-22 08:35:00"
}
```

### ② 生成或重新生成 (轮转) API 密钥
* **路径**：`POST /admin/api-key?name=MyKey`（亦支持兼容别名 `POST /user/api-key?name=MyKey`）
* **响应**：返回全新生成的 `AdminApiKeyVO`，原密钥即刻作废。

### ③ 吊销 API 密钥
* **路径**：`DELETE /admin/api-key`（亦支持兼容别名 `DELETE /user/api-key`）
* **响应**：`204 No Content`，删除密钥并清除 Redis 鉴权缓存。

---

## 3. 核心知识库端点定义

### ① 查询可用工作空间列表 (Workspaces Discovery)
用于 `setup.sh` 初始化绑定向导时，获取当前 API Key 具备访问权限的工作空间。

* **路径**：`GET /api/v1/openapi/workspaces`
* **响应格式**：
```json
[
  {
    "id": 1,
    "name": "LexiLoom 研发空间",
    "description": "后端架构设计与业务规则规范",
    "totalDocuments": 35,
    "totalChunks": 1280
  },
  {
    "id": 2,
    "name": "产品 PRD 知识库",
    "description": "产品功能规划与需求定义",
    "totalDocuments": 18,
    "totalChunks": 560
  }
]
```

---

### ② 知识库语义检索 (Semantic Chunk Search)
无头轻量检索端点，仅执行 ES 混合召回 + 重排（若开启），直接返回切片及其元数据，**不进行大模型回答生成**，以达到毫秒级响应和零 LLM Token 损耗。

* **路径**：`GET /api/v1/openapi/knowledge/search` 或 `POST /api/v1/openapi/knowledge/search`
* **Query 参数**：
  | 参数名 | 类型 | 必填 | 默认值 | 说明 |
  | :--- | :--- | :--- | :--- | :--- |
  | `q` | String | 是 | - | 用户自然语言检索 Query |
  | `workspaceId` | Long | 否 | 绑定空间 | 指定检索空间（不传则使用 API Key 绑定的默认空间） |
  | `topK` | Integer | 否 | 5 | 召回切片数量上限 |
  | `minScore` | Double | 否 | 0.50 | 相似度得分阈值过滤 |
* **响应格式**：
```json
{
  "query": "Token 统计计算规则",
  "workspaceId": 1,
  "totalMatches": 12,
  "results": [
    {
      "chunkId": "chk_8f93e2b1",
      "documentId": 105,
      "documentTitle": "RAG 观测与成本治理设计方案",
      "categoryName": "技术架构",
      "content": "统计必须覆盖全周期总计、各模型独立占比以及连续 7 天按日零填充折线图序列...",
      "score": 0.942,
      "chunkIndex": 3,
      "pageNumber": 2,
      "deepDocCoordinates": "[{\"x\":120,\"y\":340,\"w\":450,\"h\":180}]"
    }
  ]
}
```

---

### ③ 单文档完整正文提取 (Get Document Detail)
当 Agent 需要通读某篇核心规范文档时使用。

* **路径**：`GET /api/v1/openapi/documents/{id}`
* **响应格式**：
```json
{
  "id": 105,
  "title": "RAG 观测与成本治理设计方案",
  "workspaceId": 1,
  "categoryName": "技术架构",
  "status": "APPROVED",
  "version": 2,
  "content": "# RAG 观测与成本治理设计方案\n\n## 1. 概述...",
  "createTime": "2026-10-15 10:00:00",
  "updateTime": "2026-10-21 14:30:00"
}
```
