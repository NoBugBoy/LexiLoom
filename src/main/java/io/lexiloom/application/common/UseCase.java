package io.lexiloom.application.common;

/**
 * 统一用例契约
 *
 * @param <C> Command/Query 入参类型
 * @param <R> Return 返回值类型
 */
@FunctionalInterface
public interface UseCase<C, R> {
  R execute(C command);
}
