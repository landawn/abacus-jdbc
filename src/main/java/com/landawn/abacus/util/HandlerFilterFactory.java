package com.landawn.abacus.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.landawn.abacus.util.JdbcUtil.Handler;

public class HandlerFilterFactory {

    private static final Handler.Filter ALWAYS_TRUE = m -> true;

    private static final Map<String, Handler.Filter> handlerFilterPool = new ConcurrentHashMap<>();
    private static final SpringApplicationContext spingAppContext;

    static {
        handlerFilterPool.put(ClassUtil.getCanonicalClassName(Handler.Filter.class), ALWAYS_TRUE);
        handlerFilterPool.put(ClassUtil.getClassName(ALWAYS_TRUE.getClass()), ALWAYS_TRUE);

        SpringApplicationContext tmp = null;

        try {
            tmp = new SpringApplicationContext();
        } catch (Throwable e) {
            // ignore.
        }

        spingAppContext = tmp;
    }

    public static boolean register(final Class<? extends Handler.Filter> handlerFilterClass) {
        N.checkArgNotNull(handlerFilterClass, "handlerFilterClass");

        return register(N.newInstance(handlerFilterClass));
    }

    public static boolean register(final Handler.Filter handlerFilter) {
        N.checkArgNotNull(handlerFilter, "handlerFilter");

        return register(ClassUtil.getCanonicalClassName(handlerFilter.getClass()), handlerFilter);
    }

    public static boolean register(final String qualifier, final Handler.Filter handlerFilter) {
        N.checkArgNotNullOrEmpty(qualifier, "qualifier");
        N.checkArgNotNull(handlerFilter, "handlerFilter");

        if (handlerFilterPool.containsKey(qualifier)) {
            return false;
        }

        handlerFilterPool.put(qualifier, handlerFilter);

        return true;
    }

    public static Handler.Filter get(final String qualifier) {
        N.checkArgNotNullOrEmpty(qualifier, "qualifier");

        Handler.Filter result = handlerFilterPool.get(qualifier);

        if (result == null && spingAppContext != null) {
            Object bean = spingAppContext.getBean(qualifier);

            if (bean != null && bean instanceof Handler.Filter) {
                result = (Handler.Filter) bean;

                handlerFilterPool.put(qualifier, result);
            }
        }

        return result;
    }

    public static Handler.Filter get(final Class<? extends Handler.Filter> handlerFilterClass) {
        N.checkArgNotNull(handlerFilterClass, "handlerFilterClass");

        final String qualifier = ClassUtil.getCanonicalClassName(handlerFilterClass);

        Handler.Filter result = handlerFilterPool.get(qualifier);

        if (result == null && spingAppContext != null) {
            result = spingAppContext.getBean(handlerFilterClass);

            if (result == null) {
                Object bean = spingAppContext.getBean(qualifier);

                if (bean != null && bean instanceof Handler.Filter) {
                    result = (Handler.Filter) bean;
                }
            }

            if (result != null) {
                handlerFilterPool.put(qualifier, result);
            }
        }

        return result;
    }

    public static Handler.Filter getOrCreate(final Class<? extends Handler.Filter> handlerFilterClass) {
        N.checkArgNotNull(handlerFilterClass, "handlerFilterClass");

        Handler.Filter result = get(handlerFilterClass);

        if (result == null) {
            result = N.newInstance(handlerFilterClass);

            if (result != null) {
                register(result);
            }
        }

        return result;
    }
}
