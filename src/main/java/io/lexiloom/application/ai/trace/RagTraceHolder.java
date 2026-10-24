package io.lexiloom.application.ai.trace;

import io.lexiloom.application.ai.trace.model.RagTraceContext;

/**
 * 基于 JDK 21+ ScopedValue 的 RAG 全链路上下文作用域容器
 *
 * <p>遵循纯粹虚拟线程并发架构，杜绝 ThreadLocal 内存泄漏风险。 在请求入口处单向绑定，链路下游各组件（包括 HybridSearchQueryService）零参数透传直接获取。
 */
public final class RagTraceHolder {

  public static final ScopedValue<RagTraceContext> CURRENT = ScopedValue.newInstance();

  private RagTraceHolder() {}

  /**
   * 安全获取当前作用域绑定的链路上下文
   *
   * @return 若当前处于 ScopedValue 作用域内返回上下文对象，否则返回 {@code null}
   */
  public static RagTraceContext get() {
    return CURRENT.isBound() ? CURRENT.get() : null;
  }
}
