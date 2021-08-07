package com.landawn.abacus.dao.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotated methods in {@code Dao} for:
 *
 * <li> No {@code Handler} is applied to {@code non-db Operation} </li>
 * <li> No {@code sql/performance} log is applied to {@code non-db Operation} </li>
 * <li> No {@code Transaction} annotation is applied to {@code non-db Operation} </li>
 *
 * <br />
 * By default, {@code targetEntityClass/dataSource/sqlMapper/executor/asyncExecutor/prepareQuery/prepareNamedQuery/prepareCallableQuery} methods in {@code Dao} are annotated with {@code NonDBOperation}.
 *
 * <br />
 * <br />
 *
 * @author haiyangl
 *
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.METHOD })
public @interface NonDBOperation {

}