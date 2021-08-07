package com.landawn.abacus.dao.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.dao.Dao;
import com.landawn.abacus.dao.EmptyHandler;
import com.landawn.abacus.util.JdbcUtil;

@Beta
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.METHOD, ElementType.TYPE })
@Repeatable(HandlerList.class)
public @interface Handler {
    String qualifier() default "";

    @SuppressWarnings("rawtypes")
    Class<? extends JdbcUtil.Handler<? extends Dao>> type() default EmptyHandler.class;

    /**
     * Those conditions(by contains ignore case or regular expression match) will be joined by {@code OR}, not {@code AND}.
     * It's only applied if target of annotation {@code Handler} is {@code Type}, and will be ignored if target is method.
     *
     * @return
     */
    String[] filter() default { ".*" };

    /**
     * This {@code Handler} will be ignored for the invoke from methods of the {@code Dao} if it's set to {@code true}. By default, it's {@code false}.
     *
     * @return
     */
    boolean isForInvokeFromOutsideOfDaoOnly() default false;
}