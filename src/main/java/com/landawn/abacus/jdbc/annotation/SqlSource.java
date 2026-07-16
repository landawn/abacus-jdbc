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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Associates a DAO interface with an external SQL mapper resource. The mapped file provides
 * named SQL definitions (each with a unique id) that the DAO methods reference via
 * {@link Query#id() @Query(id = ...)}.
 *
 * <p>The DAO proxy loads the mapper at proxy-build time ({@code DaoImpl} uses
 * {@code SqlMapper.loadFrom(...)} to parse the resource). Resolved entries become the SQL text used
 * by every {@code @Query(id = "...")} on the same DAO. Use this when:</p>
 * <ul>
 *   <li>Inline SQL strings would crowd the Java source.</li>
 *   <li>The same statement is shared across several DAO methods.</li>
 *   <li>You want SQL to be edited by people who do not touch Java code.</li>
 *   <li>You want to maintain database-specific variants of the same query in one place.</li>
 * </ul>
 *
 * <p>If the resolved id list contains an entry that is also declared by a {@link SqlScript}
 * field on the DAO type, DAO initialization fails with {@code IllegalArgumentException} — every
 * id must be unique across both sources. Every loaded id must also be a valid Java identifier;
 * punctuated ids fail DAO initialization as well.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * @SqlSource("sql/UserDao.xml")
 * public interface UserDao extends CrudDao<User, Long, UserDao> {
 *
 *     @Query(id = "findActiveAdults")              // Reference into the mapper file.
 *     List<User> findActiveAdults(@Bind("minAge") int minAge);
 *
 *     @Query(id = "softDeleteById")
 *     int softDeleteById(@Bind("id") Long id);
 * }
 *
 * // sql/UserDao.xml:
 * // <sqlMapper>
 * //   <sql id="findActiveAdults">
 * //     SELECT * FROM users WHERE status = 'ACTIVE' AND age >= :minAge
 * //   </sql>
 * //   <sql id="softDeleteById">
 * //     UPDATE users SET deleted = 1, deleted_at = NOW() WHERE id = :id
 * //   </sql>
 * // </sqlMapper>
 * }</pre>
 *
 * @see Query#id()
 * @see SqlScript
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.TYPE })
public @interface SqlSource {

    /**
     * Specifies the path to the SQL mapper XML file.
     * After surrounding whitespace is trimmed, the location is passed to
     * {@code SqlMapper.loadFrom(...)} at DAO initialization time and should include the file
     * extension.
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
     * <p>Every SQL id loaded from this resource must be unique across the mapper and any
     * {@link SqlScript} fields declared on the same DAO; a collision fails DAO initialization with
     * {@code IllegalArgumentException}.</p>
     *
     * @return the SQL-mapper location passed to {@code SqlMapper.loadFrom}; empty (default) means
     *         no external mapper is associated with this DAO
     */
    String value() default "";
}
