# LexiLoom 混合检索标准响应规范模板 (Response Template)

当执行 `lexiloom-hybrid-search` 技能后，输出给用户的回答必须遵循以下结构规范，确保“代码实现”与“业务知识”兼备，溯源清晰。

---

## 标准模板结构

```markdown
### 🎯 核心结论与技术概要 (Executive Summary)
> [!NOTE]
> [1~3 句话直击要害，概括该功能在代码中的实现位置与在知识库中的业务定义]

---

### 💻 代码实现溯源 (Code Implementation Trace)
* **核心类/组件**：[`XxxService.java`](file:///Users/.../XxxService.java#L40-L65)
* **核心实现剖析**：
  ```java
  // 关键核心逻辑，保留清晰注释
  ```
* **调用调用栈/执行流**：
  `Controller (路由)` → `Service (编排与事务)` → `Repository / QueryDSL (数据访问)`

---

### 📚 知识库设计与业务规则 (Knowledge Base Context)
* **依据文档**：📄 **《[文档标题](http://lexiloom.io/docs/123)》**（所属工作空间: `技术研发空间`, 匹配得分: `0.92`）
* **规范约束摘要**：
  > “文档中关于此状态或业务流转的明确要求...”
* **关键参数/设计意图**：
  - 为什么此处这样设计？
  - 是否有前置审批流或合规限制？

---

### ⚖️ 融合对齐与差异诊断 (Code & Knowledge Gap Analysis)
| 检查维度 | 知识库规范要求 | 实际代码落地现状 | 一致性诊断 |
| :--- | :--- | :--- | :--- |
| **状态/参数定义** | 如 APPROVED, PENDING | 对应枚举 `DocumentStateEnum` | ✅ 完全一致 |
| **边界条件拦截** | 每日上限 100 次 | 代码中已通过 Redis 计数器截断 | ✅ 符合设计 |
| **异常处理** | 超限抛出 400 状态码 | 代码中目前静默丢弃未报错 | ⚠️ 代码待补充拦截 |

---

### 💡 建议与下一步行动 (Actionable Recommendations)
1. **代码改进**：具体的重构建议、防御性校验或单元测试补充。
2. **文档同步**：若代码已升级（如新增了参数），提示同步更新知识库文档。
```

---

## 示例参考

### 提问示例：
> “我们项目最近 7 天的 Token 统计是怎么计算的？知识库里对此有什么规范要求？”

### 输出示例：
```markdown
### 🎯 核心结论与技术概要 (Executive Summary)
> [!NOTE]
> 最近 7 天的 Token 消耗统计由 `AiStatsQueryApiImpl#getTokenUsageStats` 统一编排，基于 QueryDSL 针对 RAG 链路观测表 `ll_ai_rag_trace` 进行数据库级 `SUM` 和 `GROUP BY` 聚合。知识库明确规定统计必须覆盖全周期总计、各模型独立占比以及连续 7 天按日零填充折线图序列。

---

### 💻 代码实现溯源 (Code Implementation Trace)
* **核心类/组件**：[`AiStatsQueryApiImpl.java`](file:///Users/yujian/Downloads/trade/src/main/java/io/lexiloom/application/ai/service/impl/AiStatsQueryApiImpl.java#L60-L125)
* **核心实现剖析**：
  ```java
  StringExpression dateExpr = Expressions.stringTemplate("TO_CHAR({0}, 'YYYY-MM-DD')", qTrace.createTime);
  List<Tuple> tuples = queryFactory
      .select(dateExpr, qTrace.modelName, qTrace.totalTokens.sum(), qTrace.count())
      .from(qTrace)
      .where(builder)
      .groupBy(dateExpr, qTrace.modelName)
      .orderBy(dateExpr.asc())
      .fetch();
  ```
* **调用调用栈/执行流**：
  `AiChatController#getTokenUsageStats` → `AiStatsQueryService` → `AiStatsQueryApiImpl` → `TokenStatsMapper` (MapStruct DTO 转换)

---

### 📚 知识库设计与业务规则 (Knowledge Base Context)
* **依据文档**：📄 **《RAG 观测与成本治理设计方案》**（所属工作空间: `LexiLoom 核心研发`, 匹配得分: `0.94`）
* **规范约束摘要**：
  > “观测指标必须包含全链路真实 Token 统计（非估算）。前端控制台必须支持 7 天折线图动态渲染，对于无调用的日期必须自动补零，避免前端折线图断点。”

---

### ⚖️ 融合对齐与差异诊断 (Code & Knowledge Gap Analysis)
| 检查维度 | 知识库规范要求 | 实际代码落地现状 | 一致性诊断 |
| :--- | :--- | :--- | :--- |
| **统计周期** | 默认 7 天连续日历窗口 | `LocalDate.now().minusDays(i)` 循环填充 | ✅ 完全一致 |
| **缺失日期补零** | 无调用日期需补齐 0 | 已在内存构建序列时对空日期自动补 0 | ✅ 完全一致 |
| **模型细分** | 需输出各模型单独占比 | 输出 `percentage` 保留 2 位小数 | ✅ 完全一致 |

---

### 💡 建议与下一步行动 (Actionable Recommendations)
1. **测试用例保障**：目前已在 `AiStatsQueryApiImplTest` 中覆盖了空数据兜底与按日补零测试。
2. **文档同步更新**：文档中暂未包含新增的 `GET /api/ai/token-stats` 路由别名，建议同步更新 API 字典。
```
