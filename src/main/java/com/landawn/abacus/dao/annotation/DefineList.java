package com.landawn.abacus.dao.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Collection;

/**
 * Defines a named attribute as a comma-separated {@link String} from the elements of the annotated array or {@link Collection} argument.
 *
 * @see Define
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.PARAMETER })
public @interface DefineList {
    String value() default "";
}