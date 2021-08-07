package com.landawn.abacus.dao.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.landawn.abacus.IsolationLevel;
import com.landawn.abacus.util.Propagation;

/**
 *
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Transactional {
    Propagation propagation() default Propagation.REQUIRED;

    IsolationLevel isolation() default IsolationLevel.DEFAULT;
}