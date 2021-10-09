package com.landawn.abacus.dao.annotation;

public @interface FetchColumnByEntityClass {

    boolean value() default true;
}
