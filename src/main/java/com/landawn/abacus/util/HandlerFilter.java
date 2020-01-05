package com.landawn.abacus.util;

import java.lang.reflect.Method;

import com.landawn.abacus.util.function.Predicate;

public interface HandlerFilter extends Predicate<Method> {

    static final HandlerFilter ALWAYS_TRUE = m -> true;

    @Override
    boolean test(Method method);
}
