# LexiLoom OpenAPI 接口文档

## 基本信息

- 基础路径：`/api/v1/openapi`（兼容路径：`/v1/openapi`）
- 数据格式：JSON；流式接口使用 `text/event-stream`
- 认证：使用 `X-API-Key: <API_KEY>`，也支持 `Authorization: Bearer <API_KEY>`。API Key 的作用域、绑定工作空间和模型配置由平台管理。
- 调用来源：可通过 `X-Call-Source` 标记为 `CLI_SKILL`、`OPENAPI` 或 `WEB_CHAT`；缺省或非法值按 `OPENAPI` 处理。GET 检索也可用 `callSource` 查询参数。

本文档由 `GET /api/v1/openapi/docs` 提供，兼容路径为 `GET /v1/openapi/docs`。

## 接口一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/workspaces` | 查询当前凭据可访问的工作空间 |
| POST | `/knowledge/search` | 检索知识库切片（推荐） |
| GET | `/knowledge/search` | 检索知识库切片（便于联调） |
| GET | `/documents/{id}` | 获取单篇文档正文 |
| POST | `/chat/completions` | OpenAI 风格统一对话入口，按 `stream` 选择同步或流式 |
| POST | `/chat` | 同步问答 |
| POST | `/chat/stream` | SSE 流式问答 |
| GET | `/docs` | 获取本文档（`text/markdown`） |

## 1. 查询工作空间

```http
GET /api/v1/openapi/workspaces
X-API-Key: <API_KEY>
```

响应为数组：

```json
[
  {
    "id": 12,
    "name": "产品知识库",
    "description": "产品与研发文档",
    "totalDocuments": 24,
    "totalChunks": 860
  }
]
```

结果受当前 API Key 绑定的空间范围及用户权限约束。

## 2. 知识库切片检索

### POST（推荐）

```http
POST /api/v1/openapi/knowledge/search
Content-Type: application/json
X-API-Key: <API_KEY>
X-Call-Source: OPENAPI
```

```json
{
  "q": "文档审批流程是什么？",
  "workspaceId": 12,
  "topK": 5,
  "minScore": 0.5
}
```

字段说明：`q` 必填且不可空白；`workspaceId` 可选，`topK` 可选（默认 5），`minScore` 可选（默认 0.50）。实际空间访问仍受凭据授权约束。

### GET（快速联调）

```http
GET /api/v1/openapi/knowledge/search?q=文档审批流程&workspaceId=12&topK=5&minScore=0.5
X-API-Key: <API_KEY>
X-Call-Source: CLI_SKILL
```

查询参数：

| 参数 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- |
| `q` | 是 | string | 自然语言检索内容，不能是空白 |
| `workspaceId` | 否 | long | 目标工作空间 ID |
| `topK` | 否 | integer | 召回切片数量上限，默认 5 |
| `minScore` | 否 | number | 相似度阈值，默认 0.50 |
| `callSource` | 否 | string | 调用来源；非空时优先于 `X-Call-Source` |

> GET 实现中，当 `callSource` 非空时优先使用查询参数，否则使用请求头。

响应示例：

```json
{
  "query": "文档审批流程是什么？",
  "workspaceId": 12,
  "totalMatches": 1,
  "results": [
    {
      "chunkId": "doc-45-chunk-2",
      "documentId": 45,
      "documentTitle": "文档管理规范",
      "categoryName": "研发规范",
      "content": "文档提交后进入审批流程……",
      "score": 0.91,
      "chunkIndex": 2,
      "pageNumber": 3,
      "deepDocCoordinates": null
    }
  ],
  "traceId": "trace-id"
}
```

## 3. 获取文档正文

```http
GET /api/v1/openapi/documents/45
X-API-Key: <API_KEY>
```

响应示例：

```json
{
  "id": 45,
  "title": "文档管理规范",
  "workspaceId": 12,
  "categoryName": "研发规范",
  "status": "APPROVED",
  "version": 3,
  "content": "# 文档管理规范\n...",
  "createTime": "2026-01-20 10:30:00",
  "updateTime": "2026-02-01 09:10:00"
}
```

仅返回当前凭据可访问的文档；未授权或不存在的资源由统一异常处理返回错误响应。

## 4. 对话与知识库问答

### 统一入口（兼容 OpenAI 风格路径）

`POST /api/v1/openapi/chat/completions` 接受以下请求体。`stream` 为 `false` 或省略时返回完整 JSON；为 `true` 时返回 SSE 流。

```json
{
  "message": "总结一下文档审批流程",
  "workspaceId": 12,
  "sessionId": "session-001",
  "stream": false,
  "enableThinking": false,
  "documentId": 45,
  "topK": 5
}
```

| 字段 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- |
| `message` | 是 | string | 用户问题，不能是空白 |
| `workspaceId` | 否 | long | 知识库工作空间 ID |
| `sessionId` | 否 | string | 会话 ID，用于延续会话 |
| `stream` | 否 | boolean | 是否流式输出；默认 `false` |
| `enableThinking` | 否 | boolean | 是否请求模型思考内容 |
| `documentId` | 否 | long | 指定文档 ID，供文档定向问答使用 |
| `topK` | 否 | integer | 检索切片数量 |

同步响应示例：

```json
{
  "content": "审批流程包括提交、审核和发布。",
  "thinkingContent": null,
  "citations": [],
  "latencyMs": 1200,
  "sessionId": "session-001",
  "traceId": "trace-id",
  "promptTokens": 500,
  "completionTokens": 80,
  "totalTokens": 580
}
```

### 同步问答快捷入口

```http
POST /api/v1/openapi/chat
Content-Type: application/json
X-API-Key: <API_KEY>
```

请求体与 `/chat/completions` 相同；`stream` 字段不改变此接口行为，该路径始终返回完整 JSON。

### SSE 流式问答快捷入口

```http
POST /api/v1/openapi/chat/stream
Content-Type: application/json
Accept: text/event-stream
X-API-Key: <API_KEY>
```

请求体与 `/chat/completions` 相同；该路径始终以 SSE 方式输出。客户端应逐条读取 `data:` 事件，直到流结束。

## 5. 获取接口文档

```http
GET /api/v1/openapi/docs
X-API-Key: <API_KEY>
```

返回本文档原文，响应类型为 `text/markdown;charset=UTF-8`。兼容路径：`GET /v1/openapi/docs`。

## 错误与调用建议

- 请求参数校验失败、认证失败、权限不足、资源不存在或上游模型异常时，响应由服务端统一异常处理生成；具体错误结构以部署版本为准。
- 不要在客户端日志或代码仓库中暴露 API Key。
- 生产环境建议优先使用 POST 检索接口，避免将查询内容写入 URL 访问日志。
- 将 `traceId` 与请求日志关联，便于问题排查。
