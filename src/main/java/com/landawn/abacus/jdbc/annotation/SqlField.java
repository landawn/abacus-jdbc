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
 * Annotation used to customize the mapping between a Java field and its corresponding SQL column.
 * This annotation can be applied to fields in entity classes to specify custom column names
 * or other SQL-related metadata that differs from the default field name.
 * 
 * <p>The annotation is retained at runtime, allowing JDBC frameworks and ORM tools to inspect
 * it via reflection and apply the custom mapping during SQL query generation and result set mapping.</p>
 * 
 * <p><b>Usage Example:</b></p>
 * <pre>{@code
 * public class User {
 *     @SqlField(id = "user_id")
 *     private Long id;
 *     
 *     @SqlField(id = "full_name")
 *     private String name;
 *     
 *     @SqlField  // Uses field name "email" as column name
 *     private String email;
 * }
 * }</pre>
 * 
 * @author Haiyang Li
 * @since 1.0
 * @see java.lang.annotation.Retention
 * @see java.lang.annotation.Target
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface SqlField {

    /**
     * Specifies the SQL column name or identifier that this field maps to.
     * If not specified (empty string), the field name itself will be used as the column identifier.
     * 
     * <p>This is useful when the database column naming convention differs from Java field naming
     * conventions, such as when the database uses snake_case while Java uses camelCase.</p>
     * 
     * <p><b>Examples:</b></p>
     * <ul>
     *   <li>{@code @SqlField(id = "customer_id")} - Maps to column "customer_id"</li>
     *   <li>{@code @SqlField(id = "FIRST_NAME")} - Maps to column "FIRST_NAME"</li>
     *   <li>{@code @SqlField()} or {@code @SqlField(id = "")} - Uses the field name as column name</li>
     * </ul>
     * 
     * @return the SQL column name/identifier for this field, or empty string to use the field name
     */
    String id() default ""; // default will be field name.
}