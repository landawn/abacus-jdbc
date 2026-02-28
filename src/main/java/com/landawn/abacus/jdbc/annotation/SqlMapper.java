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
 * Associates a DAO interface with an external SQL mapper XML file.
 * This annotation allows SQL statements to be defined in external XML files rather than
 * inline annotations, promoting better separation of concerns and easier SQL maintenance.
 * 
 * <p>The SQL mapper XML file contains named SQL statements that can be referenced
 * by their IDs in DAO method annotations using {@link Query#id()}, etc.</p>
 * 
 * <p>Benefits of using external SQL mappers:</p>
 * <ul>
 *   <li>Centralized SQL management</li>
 *   <li>Better support for complex, multi-line SQL statements</li>
 *   <li>Easier SQL formatting and syntax highlighting in XML editors</li>
 *   <li>Ability to share SQL statements across multiple DAO methods</li>
 *   <li>Separation of SQL from Java code</li>
 * </ul>
 * 
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * @SqlMapper("sql/UserDao.xml")
 * public interface UserDao extends CrudDao<User, Long, SQLBuilder.PSC, UserDao> {
 *     
 *     @Query(id = "findActiveUsers")
 *     List<User> findActiveUsers();
 *     
 *     @Query(id = "updateLastLogin")
 *     int updateLastLogin(@Bind("userId") long userId, @Bind("loginTime") Date loginTime);
 * }
 * }</pre>
 * 
 * <p>Example SQL mapper XML file (sql/UserDao.xml):</p>
 * <pre>{@code
 * <?xml version="1.0" encoding="UTF-8"?>
 * <sqlMapper>
 *     <sql id="findActiveUsers">
 *         SELECT * FROM users 
 *         WHERE status = 'ACTIVE' 
 *         ORDER BY created_date DESC
 *     </sql>
 *     
 *     <sql id="updateLastLogin">
 *         UPDATE users 
 *         SET last_login = :loginTime 
 *         WHERE id = :userId
 *     </sql>
 * </sqlMapper>
 * }</pre>
 * 
 * @see Query#id()
 * @see SqlScript
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.TYPE })
public @interface SqlMapper {

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
     * @SqlMapper("sql/UserDao.xml")              // In sql directory
     * @SqlMapper("com/example/dao/UserDao.xml")  // Package-based path
     * @SqlMapper("mappers/user-queries.xml")     // Custom naming
     * }</pre>
     *
     * @return the classpath-relative path to the SQL mapper XML file.
     */
    String value() default "";
}
