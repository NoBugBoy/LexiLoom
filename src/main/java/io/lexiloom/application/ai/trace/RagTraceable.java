package io.lexiloom.application.ai.trace;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 标记该 RAG 问答方法需要由 AOP 统一接入 ScopedValue 上下文与全生命周期观测 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RagTraceable {

  /** 观测操作标识名称 */
  String operation() default "RAG_CHAT";
}
