/*
 * Copyright (c) 2021, Haiyang Li.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
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

/**
 * Replace the parts defined with format {@code {part}} in the sql annotated to the method.
 * For example:
 * <p>
 * <code>
 *
 *  @Select("SELECT first_name, last_name FROM {tableName} WHERE id = :id")
 *  <br />
 *  User selectByUserId(@Define("tableName") String realTableName, @Bind("id") int id) throws SQLException;
 *
 * <br />
 * <br />
 * <br />
 * OR with customized '{whatever}':
 * <br />
 *
 *  @Select("SELECT first_name, last_name FROM {tableName} WHERE id = :id ORDER BY {whatever -> orderBy{{P}}")
 *  <br/>
 *  User selectByUserId(@Define("tableName") String realTableName, @Bind("id") int id, @Define("{whatever -> orderBy{{P}}") String orderBy) throws SQLException;
 *
 * </code>
 * </p>
 *
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.PARAMETER })
public @interface Define {

    /**
     *
     *
     * @return
     */
    String value() default "";
}
