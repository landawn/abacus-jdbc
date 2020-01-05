package com.landawn.abacus.util;

import java.lang.reflect.Method;

import com.landawn.abacus.util.Tuple.Tuple3;
import com.landawn.abacus.util.function.Predicate;

public interface Handler<T> {

    /**
     *
     * @param targetObject
     * @param args
     * @param methodSignature The first element is {@code Method}, The second element is {@code parameterTypes}(it will be an empty Class<?> List if there is no parameter), the third element is {@code returnType}
     */
    default void beforeInvoke(final T targetObject, final Object[] args, final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
        // empty action.
    }

    /**
     *
     * @param <R>
     * @param result
     * @param targetObject
     * @param args
     * @param methodSignature The first element is {@code Method}, The second element is {@code parameterTypes}(it will be an empty Class<?> List if there is no parameter), the third element is {@code returnType}
     */
    default void afterInvoke(final Result<?, Exception> result, final T targetObject, final Object[] args,
            Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
        // empty action.
    }

    public static interface Filter extends Predicate<Method> {

        @Override
        boolean test(Method method);
    }

}
