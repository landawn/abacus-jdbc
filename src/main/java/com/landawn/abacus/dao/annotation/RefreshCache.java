package com.landawn.abacus.dao.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.landawn.abacus.annotation.Beta;

@Beta
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.METHOD, ElementType.TYPE })
public @interface RefreshCache {

    /**
     * Flag to identity if {@code RefreshCache} is disabled.
     * @return
     */
    boolean disabled() default false;

    //    /**
    //     *
    //     * @return
    //     */
    //    boolean forceRefreshStaticData() default false;

    /**
     * Those conditions(by contains ignore case or regular expression match) will be joined by {@code OR}, not {@code AND}.
     * It's only applied if target of annotation {@code RefreshCache} is {@code Type}, and will be ignored if target is method.
     *
     * @return
     */
    String[] filter() default { "save", "insert", "update", "delete", "upsert", "execute" };
}