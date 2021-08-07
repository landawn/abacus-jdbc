/*
 * Copyright (c) 2021, Haiyang Li.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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