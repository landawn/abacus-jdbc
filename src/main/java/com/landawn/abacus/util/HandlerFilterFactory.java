package com.landawn.abacus.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class HandlerFilterFactory {

    private static final Handler.Filter ALWAYS_TRUE = m -> true;

    private static final Map<String, Handler.Filter> handlerFilterPool = new ConcurrentHashMap<>();

    static {
        handlerFilterPool.put(ClassUtil.getCanonicalClassName(Handler.Filter.class), ALWAYS_TRUE);
        handlerFilterPool.put(ClassUtil.getClassName(ALWAYS_TRUE.getClass()), ALWAYS_TRUE);
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

        return handlerFilterPool.get(qualifier);
    }

    public static Handler.Filter get(final Class<? extends Handler.Filter> handlerFilterClass) {
        N.checkArgNotNull(handlerFilterClass, "handlerFilterClass");

        return get(ClassUtil.getCanonicalClassName(handlerFilterClass));
    }

    public static Handler.Filter getOrCreate(final Class<? extends Handler.Filter> handlerFilterClass) {
        N.checkArgNotNull(handlerFilterClass, "handlerFilterClass");

        Handler.Filter result = get(ClassUtil.getCanonicalClassName(handlerFilterClass));

        if (result == null) {
            result = N.newInstance(handlerFilterClass);

            if (result != null) {
                register(result);
            }
        }

        return result;
    }
}
