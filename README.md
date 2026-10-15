# LexiLoom 智能知识库管理系统

<p align="center">
  <img src="https://img.shields.io/badge/Java-25-orange.svg" alt="Java 25" />
  <img src="https://img.shields.io/badge/Spring%20Boot-4.x-brightgreen.svg" alt="Spring Boot 4.x" />
  <img src="https://img.shields.io/badge/Spring%20Modulith-1.3-blue.svg" alt="Spring Modulith" />
  <img src="https://img.shields.io/badge/Spring%20AI-1.0-6db33f.svg" alt="Spring AI" />
  <a href="https://coderabbit.ai">
    <img src="https://img.shields.io/coderabbit/prs/github/NoBugBoy/LexiLoom?utm_source=oss&utm_medium=github&utm_campaign=NoBugBoy%2FLexiLoom&labelColor=171717&color=FF570A&link=https%3A%2F%2Fcoderabbit.ai&label=CodeRabbit+Reviews" alt="CodeRabbit Pull Request Reviews" />
  </a>
</p>

---

## 📖 项目简介 (Overview)

**LexiLoom**（织语智库）是基于 **JDK 25 + Spring Boot 4.x + Spring Modulith + Spring AI** 构建的新一代企业级模块化智能知识库与 RAG（检索增强生成）智能中枢。

系统集文档资产全生命周期管理、深度结构化解析（DeepDoc）、Elasticsearch 8.x 全文检索、向量化切片召回与流式 RAG 问答于一体，遵循领域驱动设计（DDD）充血模型与 CQRS 架构，具备随时平滑拆分为微服务集群的高内聚单体演进能力。

---

## ✨ 核心特性 (Key Features)

### 1. 领域驱动与 CQRS 架构
- **充血模型设计**：聚合根（`DocumentEntity`、`WorkSpaceEntity`）自闭合管理生命周期与业务状态流转，杜绝贫血实体与上帝类。
- **读写分离解耦**：Command Service 编排业务事务，Query Service 极速查询，行锁写操作与读接口物理隔离。
- **现代化 Java 范式**：DTO、Event、VO 强制使用不可变 Java `record`，模式匹配与 `Optional` 空安全控制。

### 2. 模块化单体 (Spring Modulith)
- 遵循 Spring Modulith 强模块边界，按业务领域划分为文档（`document`）、用户权限（`user`）、检索（`search`）、AI 与 RAG（`ai`）等核心模块。
- 通过 Spring Modulith 事件发布注册表机制（`event_publication`）保证跨模块领域事件持久化与高可靠自愈。

### 3. 多模态文档解析引擎 (DeepDoc Engine)
- 支持 Word / PDF / TXT / Markdown 多格式文档解析，自适应路由调度。
- 深度对接 DeepDoc (YOLO + KMeans) 引擎，精准提取文档版面语义、分栏表格、页码与像素级定位坐标锚点（BBox），保留物理版式结构。

### 4. Elasticsearch 8.x 混合多路召回 (Hybrid Search)
- **全文检索**：集成 IK 中文分词器、多字段加权打分、多租户工作区隔离、高亮截断与搜索建议提示。
- **向量检索**：基于 Dense Vector（Cosine 相似度）与 kNN 向量召回，支持动态维度自愈重建。
- **两阶段重排**：结合语义重排（Rerank）与多样性切片回填，实现精准问答段落定位。

### 5. Spring AI 官方标准中枢 (RAG Center)
- 严格遵循 Spring AI 官方标准框架（`ChatModel`, `ChatClient`, `EmbeddingModel`），支持 OpenAI 兼容协议（DeepSeek, Qwen 等主流大模型）。
- 具备防幻觉提示词工程模板（`RagPromptTemplate`），支持多轮对话上下文滑动窗口维护与引用角标精准溯源。
- 支持 HTTP SSE 流式下发（Thinking 深度思考链、Markdown 逐字输出与结构化引用事件）。

---

## 🛠️ 快速启动 (Getting Started)

### 环境依赖
- **JDK**：21 / 25
- **Maven**：3.8.6+
- **Docker & Docker Compose**（用于快速启动基础中间件）

### 1. 启动本地基础中间件
项目已内置编排好的开发中间件（PostgreSQL 16、Redis 7、Elasticsearch 8.13、RustFS 对象存储）：

```bash
docker-compose up -d
```

### 2. 编译与单元测试
```bash
# 编译验证
mvn clean test-compile

# 运行全量测试用例（390+ 自动化测试用例）
mvn test
```

### 3. 启动应用
```bash
mvn spring-boot:run
```

---

## 🏗️ 架构规范与代码治理

本项目遵循严格的企业级架构规范与 CI/CD 自动治理体系：
- **安全拦截**：CI 流水线内置 **Semgrep SAST** 扫描（OWASP Top 10 / 阿里规约安全红线检查）。
- **智能审查**：集成 **CodeRabbit AI** 针对 Pull Request 进行上下文语义代码走查与一键建议应用。
- 更多工程原则与规范详见 [AGENTS.md](.agents/rules/AGENTS.md)。
