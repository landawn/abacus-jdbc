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
 * Associates a DAO interface with an external SQL mapper resource.
 *
 * <p>The mapped resource provides named SQL definitions that can be referenced from
 * {@link Query#id()}. Use this when inline SQL becomes too large or when several DAO
 * methods need to share the same statement text.</p>
 *
 * @see Query#id()
 * @see SqlScript
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.TYPE })
public @interface SqlSource {

    /**
     * Specifies the path to the SQL mapper XML file.
     * The path is relative to the classpath root and should include the file extension.
     *
     * <p>If not specified (empty string), the default mapper file location will be used.</p>
     * 
     * <p>Common conventions:</p>
     * <ul>
     *   <li>Place mapper files in a resources directory mirroring the package structure</li>
     *   <li>Name mapper files after the DAO interface (e.g., UserDao.xml for UserDao interface)</li>
     *   <li>Use a dedicated directory like "sql/" or "mappers/" for organization</li>
     * </ul>
     * 
     * <p>Example paths:</p>
     * <pre>{@code
     * @SqlSource("sql/UserDao.xml")              // In sql directory
     * @SqlSource("com/example/dao/UserDao.xml")  // Package-based path
     * @SqlSource("mappers/user-queries.xml")     // Custom naming
     * }</pre>
     *
     * @return the classpath-relative path to the SQL mapper XML file.
     */
    String value() default "";
}
