package com.landawn.abacus.dao.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.TYPE })
public @interface Config {
    /**
     * Single query method includes: queryForSingleXxx/queryForUniqueResult/findFirst/findOnlyOne/exists/count...
     *
     * @return
     */
    boolean addLimitForSingleQuery() default false;

    /**
     * flag to call {@code generateId} for {@code CrudDao.insert(T entity), CrudDao.batchInsert(Collection<T> entities)} if the ids are not set or set with default value.
     *
     * @return
     */
    boolean callGenerateIdForInsertIfIdNotSet() default false;

    /**
     * flag to call {@code generateId} for {@code CrudDao.insert(String sql, T entity), CrudDao.batchInsert(String sql, Collection<T> entities)} if the ids are not set or set with default value.
     *
     *
     * @return
     */
    boolean callGenerateIdForInsertWithSqlIfIdNotSet() default false;

    /**
     *
     * @return
     */
    boolean allowJoiningByNullOrDefaultValue() default false;

    //    // why do we need this?
    //    boolean excludePrepareQueryMethodsFromNonDBOpereation() default false;
}