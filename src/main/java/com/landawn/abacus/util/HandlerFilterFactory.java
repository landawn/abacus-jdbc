package com.landawn.abacus.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class HandlerFilterFactory {

    private static final Map<String, HandlerFilter> HandlerFilterPool = new ConcurrentHashMap<>();

    static {
        HandlerFilterPool.put(ClassUtil.getClassName(HandlerFilter.class), HandlerFilter.ALWAYS_TRUE);
        HandlerFilterPool.put(ClassUtil.getClassName(HandlerFilter.ALWAYS_TRUE.getClass()), HandlerFilter.ALWAYS_TRUE);
    }

    public static void register(final Class<? extends HandlerFilter> HandlerFilterClass) {
        N.checkArgNotNull(HandlerFilterClass, "HandlerFilterClass");

        register(N.newInstance(HandlerFilterClass));
    }

    public static void register(final HandlerFilter HandlerFilter) {
        N.checkArgNotNull(HandlerFilter, "HandlerFilter");

        register(ClassUtil.getCanonicalClassName(HandlerFilter.getClass()), HandlerFilter);
    }

    public static boolean register(final String qualifier, final HandlerFilter HandlerFilter) {
        N.checkArgNotNullOrEmpty(qualifier, "qualifier");
        N.checkArgNotNull(HandlerFilter, "HandlerFilter");

        if (HandlerFilterPool.containsKey(qualifier)) {
            return false;
        }

        HandlerFilterPool.put(qualifier, HandlerFilter);
        return true;
    }

    public static HandlerFilter get(final String qualifier) {
        N.checkArgNotNullOrEmpty(qualifier, "qualifier");

        return HandlerFilterPool.get(qualifier);
    }

    public static HandlerFilter get(final Class<? extends HandlerFilter> HandlerFilterClass) {
        N.checkArgNotNull(HandlerFilterClass, "HandlerFilterClass");

        return get(ClassUtil.getCanonicalClassName(HandlerFilterClass));
    }

    public static HandlerFilter getOrCreate(final Class<? extends HandlerFilter> HandlerFilterClass) {
        N.checkArgNotNull(HandlerFilterClass, "HandlerFilterClass");

        HandlerFilter result = get(ClassUtil.getCanonicalClassName(HandlerFilterClass));

        if (result == null) {
            try {
                result = N.newInstance(HandlerFilterClass);

                if (result != null) {
                    register(result);
                }
            } catch (Throwable e) {
                // ignore
            }
        }

        return result;
    }
}
