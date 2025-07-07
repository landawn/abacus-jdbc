/*
 * Copyright (c) 2020, Haiyang Li.
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

package com.landawn.abacus.jdbc;

import com.landawn.abacus.util.Strings;

/**
 * Enumeration representing various database products and their major versions.
 * 
 * <p>This enum provides a standardized way to identify different database systems
 * and their versions, which is useful for handling database-specific SQL syntax,
 * features, and optimizations.</p>
 * 
 * <p>The enum includes entries for popular databases like MySQL, PostgreSQL, Oracle,
 * and others, with specific version distinctions where behavior differences are significant.</p>
 * 
 * <p>Usage example:
 * <pre>{@code
 * DBVersion version = DBVersion.MYSQL_8;
 * if (version.isMySQL()) {
 *     // Use MySQL-specific syntax
 *     query = "SELECT * FROM users LIMIT 10";
 * } else if (version.isPostgreSQL()) {
 *     // Use PostgreSQL-specific syntax
 *     query = "SELECT * FROM users LIMIT 10";
 * }
 * }</pre>
 * 
 * @see DBProductInfo
 * @since 1.0
 */
public enum DBVersion {

    /**
     * H2 Database Engine.
     */
    H2,

    /**
     * HSQLDB (HyperSQL Database).
     */
    HSQLDB,

    /**
     * MySQL version 5.5.
     */
    MYSQL_5_5,

    /**
     * MySQL version 5.6.
     */
    MYSQL_5_6,

    /**
     * MySQL version 5.7.
     */
    MYSQL_5_7,

    /**
     * MySQL version 5.8.
     */
    MYSQL_5_8,

    /**
     * MySQL version 5.9.
     */
    MYSQL_5_9,

    /**
     * MySQL version 6.x.
     */
    MYSQL_6,

    /**
     * MySQL version 7.x.
     */
    MYSQL_7,

    /**
     * MySQL version 8.x.
     */
    MYSQL_8,

    /**
     * MySQL version 9.x.
     */
    MYSQL_9,

    /**
     * MySQL version 10.x (MariaDB).
     */
    MYSQL_10,

    /**
     * Other MySQL versions not specifically enumerated.
     */
    MYSQL_OTHERS,

    /**
     * PostgreSQL version 9.2.
     */
    POSTGRESQL_9_2,

    /**
     * PostgreSQL version 9.3.
     */
    POSTGRESQL_9_3,

    /**
     * PostgreSQL version 9.4.
     */
    POSTGRESQL_9_4,

    /**
     * PostgreSQL version 9.5.
     */
    POSTGRESQL_9_5,

    /**
     * PostgreSQL version 9.6.
     */
    POSTGRESQL_9_6,

    /**
     * PostgreSQL version 10.x.
     */
    POSTGRESQL_10,

    /**
     * PostgreSQL version 11.x.
     */
    POSTGRESQL_11,

    /**
     * PostgreSQL version 12.x.
     */
    POSTGRESQL_12,

    /**
     * Other PostgreSQL versions not specifically enumerated.
     */
    POSTGRESQL_OTHERS,

    /**
     * Oracle Database.
     */
    ORACLE,

    /**
     * IBM DB2 Database.
     */
    DB2,

    /**
     * Microsoft SQL Server.
     */
    SQL_SERVER,

    /**
     * Other database systems not specifically enumerated.
     */
    OTHERS;

    /**
     * Checks if this database version represents any variant of MySQL.
     * 
     * <p>This method performs a case-insensitive check to determine if the
     * enum constant's name starts with "mysql", which includes all MySQL
     * versions and MYSQL_OTHERS.</p>
     * 
     * <p>Example:
     * <pre>{@code
     * DBVersion version = DBVersion.MYSQL_8;
     * if (version.isMySQL()) {
     *     // Execute MySQL-specific logic
     * }
     * }</pre>
     *
     * @return {@code true} if this is a MySQL database version, {@code false} otherwise
     */
    public boolean isMySQL() {
        return Strings.startsWithIgnoreCase(name(), "mysql");
    }

    /**
     * Checks if this database version represents any variant of PostgreSQL.
     * 
     * <p>This method performs a case-insensitive check to determine if the
     * enum constant's name starts with "postgresql", which includes all PostgreSQL
     * versions and POSTGRESQL_OTHERS.</p>
     * 
     * <p>Example:
     * <pre>{@code
     * DBVersion version = DBVersion.POSTGRESQL_12;
     * if (version.isPostgreSQL()) {
     *     // Execute PostgreSQL-specific logic
     * }
     * }</pre>
     *
     * @return {@code true} if this is a PostgreSQL database version, {@code false} otherwise
     */
    public boolean isPostgreSQL() {
        return Strings.startsWithIgnoreCase(name(), "postgresql");
    }
}