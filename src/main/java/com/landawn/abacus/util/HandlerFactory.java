package com.landawn.abacus.util;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.landawn.abacus.util.JdbcUtil.Handler;
import com.landawn.abacus.util.Tuple.Tuple3;

public class HandlerFactory {

    @SuppressWarnings("rawtypes")
    static final Handler EMPTY = new Handler() {
        // Do nothing.
    };

    private static final Map<String, Handler<?>> handlerPool = new ConcurrentHashMap<>();
    private static final SpringApplicationContext spingAppContext;

    static {
        handlerPool.put(ClassUtil.getCanonicalClassName(Handler.class), EMPTY);
        handlerPool.put(ClassUtil.getClassName(EMPTY.getClass()), EMPTY);

        SpringApplicationContext tmp = null;

        try {
            tmp = new SpringApplicationContext();
        } catch (Throwable e) {
            // ignore.
        }

        spingAppContext = tmp;
    }

    public static boolean register(final Class<? extends Handler<?>> handlerClass) {
        N.checkArgNotNull(handlerClass, "handlerClass");

        return register(N.newInstance(handlerClass));
    }

    public static boolean register(final Handler<?> handler) {
        N.checkArgNotNull(handler, "handler");

        return register(ClassUtil.getCanonicalClassName(handler.getClass()), handler);
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

        Handler<?> result = handlerPool.get(qualifier);

        if (result == null && spingAppContext != null) {
            Object bean = spingAppContext.getBean(qualifier);

            if (bean != null && bean instanceof Handler) {
                result = (Handler<?>) bean;

                handlerPool.put(qualifier, result);
            }
        }

        return result;
    }

    public static Handler<?> get(final Class<? extends Handler<?>> handlerClass) {
        N.checkArgNotNull(handlerClass, "handlerClass");

        final String qualifier = ClassUtil.getCanonicalClassName(handlerClass);

        Handler<?> result = handlerPool.get(qualifier);

        if (result == null && spingAppContext != null) {
            result = spingAppContext.getBean(handlerClass);

            if (result == null) {
                Object bean = spingAppContext.getBean(qualifier);

                if (bean != null && bean instanceof Handler) {
                    result = (Handler<?>) bean;
                }
            }

            if (result != null) {
                handlerPool.put(qualifier, result);
            }
        }

        return result;
    }

    public static Handler<?> getOrCreate(final Class<? extends Handler<?>> handlerClass) {
        N.checkArgNotNull(handlerClass, "handlerClass");

        Handler<?> result = get(handlerClass);

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
