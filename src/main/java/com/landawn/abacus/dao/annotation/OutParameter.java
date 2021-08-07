package com.landawn.abacus.dao.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.sql.CallableStatement;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(OutParameterList.class)
public @interface OutParameter {
    /**
     *
     * @return
     * @see CallableStatement#registerOutParameter(String, int)
     */
    String name() default "";

    /**
     * Starts from 1.
     * @return
     * @see CallableStatement#registerOutParameter(int, int)
     */
    int position() default -1;

    /**
     *
     * @return
     * @see {@code java.sql.Types}
     */
    int sqlType();
}