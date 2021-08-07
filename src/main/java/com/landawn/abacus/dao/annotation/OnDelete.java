package com.landawn.abacus.dao.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.landawn.abacus.dao.OnDeleteAction;

/**
 * Unsupported operation.
 *
 * @deprecated won't be implemented. It should be defined and done in DB server side.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.METHOD })
public @interface OnDelete {
    OnDeleteAction action() default OnDeleteAction.NO_ACTION;
}