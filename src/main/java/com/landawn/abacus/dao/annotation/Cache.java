package com.landawn.abacus.dao.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.landawn.abacus.annotation.Beta;

// TODO: First of all, it's bad idea to implement cache in DAL layer?! and how if not?
/**
 * Mostly, it's used for static tables.
 */
@Beta
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.TYPE })
public @interface Cache {
    int capacity() default 1000;

    long evictDelay() default 3000; // unit milliseconds.
}