package com.landawn.abacus.dao.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * It's only for methods with default implementation in {@code Dao} interfaces. Don't use it for the abstract methods.
 * And the last parameter of the method should be {@code String[]: (param1, param2, ..., String ... sqls)}
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Sqls {
    /**
     *
     * @return
     */
    String[] value() default {};
}