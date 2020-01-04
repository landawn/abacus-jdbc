package com.landawn.abacus.util;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.landawn.abacus.util.Tuple.Tuple3;

public class HandlerFactory {

    private static final Map<String, Handler<?>> handlerPool = new ConcurrentHashMap<>();

    static {
        handlerPool.put(ClassUtil.getClassName(Handler.class), Handler.EMPTY);
        handlerPool.put(ClassUtil.getClassName(Handler.EMPTY.getClass()), Handler.EMPTY);
    }

    public static void register(final Class<? extends Handler<?>> handlerClass) {
        N.checkArgNotNull(handlerClass, "handlerClass");

        register(N.newInstance(handlerClass));
    }

    public static void register(final Handler<?> handler) {
        N.checkArgNotNull(handler, "handler");

        register(ClassUtil.getClassName(handler.getClass()), handler);
    }

    public static boolean register(final String qualifier, final Handler<?> handler) {
        N.checkArgNotNullOrEmpty(qualifier, "qualifier");
        N.checkArgNotNull(handler, "handler");

        if (handlerPool.containsKey(qualifier)) {
            return false;
        }

        handlerPool.put(qualifier, handler);
        return true;
    }

    public static Handler<?> get(final String qualifier) {
        N.checkArgNotNullOrEmpty(qualifier, "qualifier");

        return handlerPool.get(qualifier);
    }

    public static Handler<?> get(final Class<? extends Handler<?>> handlerClass) {
        N.checkArgNotNull(handlerClass, "handlerClass");

        return get(ClassUtil.getClassName(handlerClass));
    }

    public static Handler<?> getOrCreate(final Class<? extends Handler<?>> handlerClass) {
        N.checkArgNotNull(handlerClass, "handlerClass");

        Handler<?> result = get(ClassUtil.getClassName(handlerClass));

        if (result == null) {
            try {
                result = N.newInstance(handlerClass);

                if (result != null) {
                    register(result);
                }
            } catch (Throwable e) {
                // ignore
            }
        }

        return result;
    }

    public static <T, E extends RuntimeException> Handler<T> create(
            final Throwables.TriConsumer<T, Object[], Tuple3<Method, ImmutableList<Class<?>>, Class<?>>, E> beforeInvokeAction) {
        N.checkArgNotNull(beforeInvokeAction, "beforeInvokeAction");

        return new Handler<T>() {
            @Override
            public void beforeInvoke(final T targetObject, final Object[] args, final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
                beforeInvokeAction.accept(targetObject, args, methodSignature);
            }
        };
    }

    public static <T, E extends RuntimeException> Handler<T> create(
            final Throwables.QuadConsumer<Result<?, Exception>, T, Object[], Tuple3<Method, ImmutableList<Class<?>>, Class<?>>, E> afterInvokeAction) {
        N.checkArgNotNull(afterInvokeAction, "afterInvokeAction");

        return new Handler<T>() {
            @Override
            public void afterInvoke(final Result<?, Exception> result, final T targetObject, final Object[] args,
                    final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {

                afterInvokeAction.accept(result, targetObject, args, methodSignature);
            }
        };
    }

    public static <T, E extends RuntimeException> Handler<T> create(
            final Throwables.TriConsumer<T, Object[], Tuple3<Method, ImmutableList<Class<?>>, Class<?>>, E> beforeInvokeAction,
            final Throwables.QuadConsumer<Result<?, Exception>, T, Object[], Tuple3<Method, ImmutableList<Class<?>>, Class<?>>, E> afterInvokeAction) {
        N.checkArgNotNull(beforeInvokeAction, "beforeInvokeAction");
        N.checkArgNotNull(afterInvokeAction, "afterInvokeAction");

        return new Handler<T>() {
            @Override
            public void beforeInvoke(final T targetObject, final Object[] args, final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
                beforeInvokeAction.accept(targetObject, args, methodSignature);
            }

            @Override
            public void afterInvoke(final Result<?, Exception> result, final T targetObject, final Object[] args,
                    final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {

                afterInvokeAction.accept(result, targetObject, args, methodSignature);
            }
        };
    }
}
