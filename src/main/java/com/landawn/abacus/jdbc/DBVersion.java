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
 * <p>This enum provides a standardized and type-safe way to identify different
 * database systems and their versions. It is crucial for developing database-agnostic
 * applications that can adapt to vendor-specific SQL dialects, features, and
 * performance optimizations. By using this enum, applications can implement
 * version-specific logic while maintaining overall code portability.</p>
 *
 * <p>The enum includes constants for widely used relational databases such as
 * H2, HSQLDB, MySQL (with specific major versions), MariaDB, PostgreSQL (with
 * specific major versions), Oracle, DB2, and SQL Server, along with generic
 * {@code _OTHERS} categories for broader compatibility.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Example: Adapting SQL syntax based on detected database version
 * DBProductInfo dbInfo = JdbcUtil.getDBProductInfo(connection);
 * DBVersion currentDbVersion = dbInfo.version();
 *
 * String query;
 * if (currentDbVersion.isMySQL()) {
 *     query = "SELECT * FROM products LIMIT 10 OFFSET 20";
 * } else if (currentDbVersion.isPostgreSQL()) {
 *     query = "SELECT * FROM products OFFSET 20 LIMIT 10";
 * } else if (currentDbVersion == DBVersion.Oracle) {
 *     query = "SELECT * FROM (SELECT p.*, ROWNUM rnum FROM products p) WHERE rnum > 20 AND ROWNUM <= 10";
 * } else {
 *     query = "SELECT * FROM products"; // Fallback or generic SQL
 * }
 * System.out.println("Executing query: " + query);
 *
 * // Example: Utilizing version-specific features
 * if (currentDbVersion == DBVersion.MySQL_8) {
 *     System.out.println("Using MySQL 8.x specific features like window functions.");
 *     // Code to use JSON functions or window functions available in MySQL 8
 * } else if (currentDbVersion.isPostgreSQL()) {
 *     System.out.println("Using PostgreSQL array types or advanced JSONB features.");
 *     // Code to leverage PostgreSQL-specific data types
 * }
 * }</pre>
 *
 * @see DBProductInfo
 * @see JdbcUtil#getDBProductInfo(java.sql.Connection)
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
    MySQL_5_5,

    /**
     * MySQL version 5.6.
     */
    MySQL_5_6,

    /**
     * MySQL version 5.7.
     */
    MySQL_5_7,

    /**
     * MySQL version 5.8.
     */
    MySQL_5_8,

    /**
     * MySQL version 5.9.
     */
    MySQL_5_9,

    /**
     * MySQL version 6.x.
     */
    MySQL_6,

    /**
     * MySQL version 7.x.
     */
    MySQL_7,

    /**
     * MySQL version 8.x.
     */
    MySQL_8,

    /**
     * MySQL version 9.x.
     */
    MySQL_9,

    /**
     * MySQL version 10.x (MariaDB).
     */
    MySQL_10,

    /**
     * Other MySQL versions not specifically enumerated.
     */
    MySQL_OTHERS,

    /**
     * MariaDB (a fork of MySQL).
     */
    MariaDB,

    /**
     * PostgreSQL version 9.2.
     */
    PostgreSQL_9_2,

    /**
     * PostgreSQL version 9.3.
     */
    PostgreSQL_9_3,

    /**
     * PostgreSQL version 9.4.
     */
    PostgreSQL_9_4,

    /**
     * PostgreSQL version 9.5.
     */
    PostgreSQL_9_5,

    /**
     * PostgreSQL version 9.6.
     */
    PostgreSQL_9_6,

    /**
     * PostgreSQL version 10.x.
     */
    PostgreSQL_10,

    /**
     * PostgreSQL version 11.x.
     */
    PostgreSQL_11,

    /**
     * PostgreSQL version 12.x.
     */
    PostgreSQL_12,

    /**
     * Other PostgreSQL versions not specifically enumerated.
     */
    PostgreSQL_OTHERS,

    /**
     * Oracle Database.
     */
    Oracle,

    /**
     * IBM DB2 Database.
     */
    DB2,

    /**
     * Microsoft SQL Server.
     */
    SQL_Server,

    /**
     * Other database systems not specifically enumerated.
     */
    OTHERS;

    /**
     * Checks if this {@code DBVersion} enum constant represents any variant of MySQL.
     * This includes specific MySQL versions (e.g., {@code MySQL_5_7}, {@code MySQL_8})
     * and the generic {@code MySQL_OTHERS} constant.
     *
     * <p>The check is performed by verifying if the enum constant's name starts
     * with "MySQL" (case-insensitive).</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * DBProductInfo dbInfo = JdbcUtil.getDBProductInfo(connection);
     * DBVersion currentDbVersion = dbInfo.version();
     *
     * if (currentDbVersion.isMySQL()) {
     *     System.out.println("Connected to a MySQL database.");
     *     // Apply MySQL-specific SQL or optimizations
     * } else {
     *     System.out.println("Not a MySQL database.");
     * }
     * }</pre>
     *
     * @return {@code true} if this {@code DBVersion} is a MySQL variant, {@code false} otherwise.
     */
    public boolean isMySQL() {
        return Strings.startsWithIgnoreCase(name(), "MySQL");
    }

    /**
     * Checks if this {@code DBVersion} enum constant represents any variant of PostgreSQL.
     * This includes specific PostgreSQL versions (e.g., {@code PostgreSQL_9_6}, {@code PostgreSQL_12})
     * and the generic {@code PostgreSQL_OTHERS} constant.
     *
     * <p>The check is performed by verifying if the enum constant's name starts
     * with "PostgreSQL" (case-insensitive).</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * DBProductInfo dbInfo = JdbcUtil.getDBProductInfo(connection);
     * DBVersion currentDbVersion = dbInfo.version();
     *
     * if (currentDbVersion.isPostgreSQL()) {
     *     System.out.println("Connected to a PostgreSQL database.");
     *     // Apply PostgreSQL-specific SQL or features like JSONB
     * } else {
     *     System.out.println("Not a PostgreSQL database.");
     * }
     * }</pre>
     *
     * @return {@code true} if this {@code DBVersion} is a PostgreSQL variant, {@code false} otherwise.
     */
    public boolean isPostgreSQL() {
        return Strings.startsWithIgnoreCase(name(), "postgresql");
    }
}
