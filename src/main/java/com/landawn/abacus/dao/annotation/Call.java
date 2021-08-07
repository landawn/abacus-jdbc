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

import com.landawn.abacus.dao.OP;

/**
 * The Interface Call.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Call {

    /**
     *
     * @return
     * @deprecated using sql="call update_account(?)" for explicit call.
     */
    @Deprecated
    String value() default "";

    /**
     *
     * @return
     */
    String id() default ""; // id defined SqlMapper

    /**
     *
     * @return
     */
    String sql() default "";

    /**
     *
     * @return
     */
    int fetchSize() default -1;

    /**
     * Unit is seconds.
     *
     * @return
     */
    int queryTimeout() default -1;

    /**
     * Set it to true if there is only one input parameter and the type is Collection/Object Array, and the target db column type is Collection/Object Array.
     *
     * @return
     */
    boolean isSingleParameter() default false;

    /**
     * Set it to true if want to retrieve all the {@code ResultSets} returned from the executed procedure by {@code queryAll/listAll/streamAll}.
     * It is false by default. The reason is all the query methods extended from {@code AbstractPreparedQuery} only retrieve the first {@code ResultSet}.
     *
     */
    OP op() default OP.DEFAULT;
}