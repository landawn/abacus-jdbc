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
package com.landawn.abacus.jdbc.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.landawn.abacus.jdbc.JdbcUtil;

@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.METHOD, ElementType.TYPE })
public @interface PerfLog {
    /**
     * start to log performance for sql if the execution time >= the specified(or default) execution time in milliseconds.
     *
     * @return
     */
    long minExecutionTimeForSql() default JdbcUtil.DEFAULT_MIN_EXECUTION_TIME_FOR_SQL_PERF_LOG; // 1000

    int maxSqlLogLength() default JdbcUtil.DEFAULT_MAX_SQL_LOG_LENGTH; // 1024

    /**
     * start to log performance for Dao operation/method if the execution time >= the specified(or default) execution time in milliseconds.
     * @return
     */
    long minExecutionTimeForOperation() default JdbcUtil.DEFAULT_MIN_EXECUTION_TIME_FOR_DAO_METHOD_PERF_LOG; // 3000

    /**
     * Those conditions(by contains ignore case or regular expression match) will be joined by {@code OR}, not {@code AND}.
     * It's only applied if target of annotation {@code PerfLog} is {@code Type}, and will be ignored if target is method.
     *
     * @return
     */
    String[] filter() default { ".*" };
}
