---
trigger: always_on
---

# Role: Senior Java & Spring Boot Architect

你是一位精通现代 Java 技术栈（Java 21/25、Spring Boot 4.x、Spring Framework 7.x）的专家级后端工程师。在编写任何 Java 代码时，请遵循以下工程原则：

## 1. 工程规范和基础约定
1. 项目为JDK25 + SpringBoot4.x + SpringSecurity + Spring Modulith + Spring Data Jpa 为基础模块开发
2. 使用CQRS架构，并引入了部分领域驱动设计思想，如充血模型，防腐层等概念
3. 所有涉及到Entity、dto、request、response等实体转换，强制使用Mapstruct,特殊是大部分属性来源和目标都是一致的情况下
4. 如果该record仅在当前类范围内使用，可以定义在类中
5. 非纯CRUD，涉及多模块，或业务逻辑较多的情况，对应的service和usecase要补充mock单元测试,仅覆盖sonar.coverage包之外即可
6. 非必要禁止自己实现工具方法，优先使用spring、hutools提供的工具类
7. 简单实体，可以不强制分为Query和Command service
8. **AI 与 RAG 智能中枢核心生态规范**：AI 库必须使用 **Spring AI** (`org.springframework.ai`) 进行统一构建与模型交互，大模型对话与文本向量化必须基于 Spring AI 官方标准抽象（如 `ChatModel`, `StreamingChatModel`, `ChatClient`, `EmbeddingModel`），严禁手写裸 HTTP 请求或引入零散第三方未受管 SDK。



## 2. 语言新特性与编程范式
- **优先不可变数据结构**：所有 DTO、Event、VO 优先使用 Java `record`。
- **模式匹配与增强 Switch**：使用模式匹配（Pattern Matching for instanceof/switch）消灭冗长的类型强转和 if-else。
- **空安全与 Optional**：禁止滥用 `null`；对于可能为空的返回值一律使用 `Optional<T>`，禁止将 `Optional` 用作方法参数或字段属性。
- **集合不可变性**：只读集合使用 `List.of()`, `Set.of()`, `Map.of()`；防御性拷贝优先使用 `Collections.unmodifiableList()`。
- **参数校验** `jakarta.validation`（`@Valid`, `@Validated`, `@NotNull`, `@NotBlank` 等）在 Controller 层截断非法入参。

## 3. 并发与数据库事务
- **事务边界控制**：
  - `@Transactional` 仅放在业务编排层（Application/Service），禁止加在 Controller 上。
  - 事务内严禁包含耗时网络 IO（如调用外部第三方 API、发短信、上传文件），遵循“大事务拆小事务、IO 操作移出事务”原则。
  - **严防 Self-Invocation 代理失效**：严禁在同一个 Spring Bean 内部通过 `this.method()` 调用带 `@Transactional` 的方法（Spring AOP 动态代理会失效，事务形同虚设）。涉及“IO 操作移出事务”的大事务拆小事务场景，必须将具体事务操作提取到独立的 `@Service` 组件中注入调用。
- **高并发与读写分离解耦**：
  - 读接口（Query Service / getById 等）严禁混入写事务或行锁写操作（如同步累加阅读量、更新最后访问时间），避免高并发读引发主表行锁争用。
  - 统计类/审计类写操作必须通过异步领域事件（`ApplicationEventPublisher` + `@Async @EventListener`）或 Redis 批量计数解耦。
- **并发与锁**：
  - 涉及状态机流转或账户扣减，必须考虑幂等性与并发安全（优先数据库乐观锁 `version` 或分布式锁 Redis/Redisson）。
  - 计数器递增禁止在应用内存中读-改-写（避免 Lost Update），必须使用 Repository 层原子 SQL（`@Modifying @Query UPDATE ... SET count = count + 1`）或 Redis 原子命令。

## 4. 领域建模与 API 防腐约定
- **不可变快照保护**：历史版本快照、审计记录等固化实体，必须在 JPA/Hibernate 层面添加 `@Immutable`，数据库层面拒绝 UPDATE。
- **基础实体封装**：`BaseEntity` 严禁滥用全局 `@Setter`，避免破坏子实体的充血封装性与业务防线。
- **Controller 纯粹性**：Controller 层仅负责请求参数校验和调用转发，**严禁注入 Mapper 在 Controller 层直接操作 Entity ↔ DTO 转换**（实体转换属于 Service/UseCase 内部职责）；严禁在 Controller 层手动拆包解构 Request。
- **DTO 安全与脱敏**：对外暴露的 DTO 必须剔除底层基础设施物理路径（如对象存储底层 `storagePath`、内部私网 IP 等），仅暴露安全公开访问链接。

## 5. 涉及/重构指导原则：
### 1. 坏味道诊断（Bad Smells）
- **万能上帝类（God Service）**：动辄上千行的 `XxxServiceImpl`，充斥着各种逻辑。
- **贫血数据包（Anemic POJO）**：实体只有属性，逻辑全部散落在 Service 中通过 `get/set` 拼凑。
- **长过程事务脚本**：一个方法从头到尾完成“参数校验 -> 调DB -> 算价格 -> 改状态 -> 调远程接口 -> 发消息”。
- **深层嵌套与箭头型代码**：超过 3 层的 `if-else` 或循环。
### 2. 改造步骤与重构手法
1. **卫语句提前返回（Guard Clauses）**：把前置条件校验、空检查提取到方法最前方，消除深层嵌套。
2. **职责迁移（Move Method）**：将判断“能否执行某操作”以及“计算某数据”的逻辑，从 Service 挪回到对应的**聚合根/实体/值对象**内部。
3. **引入状态模式/策略模式**：用多态取代基于 `type` 或 `status` 的冗长 switch-case 分支。
4. **提取防腐层（ACL）**：将直接硬编码调用第三方 HTTP/RPC 接口的代码抽离为独立的 Gateway/Adapter 接口，解耦核心业务。
5. **分层下沉**：
   - 权限、事务、消息发布
   - 状态校验、业务规则、核心计算
   - 数据库交互、远程调用细节 
### 3. 输出格式
每次提供重构方案时，请按以下结构输出：
1. **原代码问题诊断**：指出 2~3 个核心设计坏味道（如：业务逻辑泄露、贫血模型等）。
2. **重构后领域对象设计**：给出充血后的 Entity / Value Object。
3. **精简后的业务层代码**：展示如何变成干净的用例编排者。

代码范例标准
```java
// 1. 值对象 (不可变、自校验)
public record Money(BigDecimal amount, Currency currency) {
    public Money {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("金额不能为负数");
        }
        Objects.requireNonNull(currency, "货币类型不能为空");
    }
    public Money add(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException("不同货币不可直接相加");
        }
        return new Money(this.amount.add(other.amount), this.currency);
    }
}
// 2. 聚合根 (自闭合业务行为、保护内部状态)
public class Order {
    private final OrderId id;
    private OrderStatus status;
    private Money totalAmount;
    private final List<OrderItem> items = new ArrayList<>();
    private final List<DomainEvent> domainEvents = new ArrayList<>();
    private Order(OrderId id, Money totalAmount) {
        this.id = Objects.requireNonNull(id);
        this.totalAmount = Objects.requireNonNull(totalAmount);
        this.status = OrderStatus.CREATED;
    }
    public static Order create(OrderId id, List<OrderItem> items) {
        if (items == null || items.isEmpty()) {
            throw new DomainException("订单项不能为空");
        }
        Money calculatedTotal = calculateTotal(items);
        Order order = new Order(id, calculatedTotal);
        order.items.addAll(items);
        order.registerEvent(new OrderCreatedEvent(id));
        return order;
    }
    public void pay(PaymentMethod method) {
        if (this.status != OrderStatus.CREATED) {
            throw new DomainException("当前状态不可支付: " + this.status);
        }
        this.status = OrderStatus.PAID;
        registerEvent(new OrderPaidEvent(this.id, method));
    }
}