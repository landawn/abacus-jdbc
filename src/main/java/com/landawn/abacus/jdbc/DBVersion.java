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
 * features, and optimizations. The enum helps in writing database-portable code
 * while allowing version-specific behavior when necessary.</p>
 *
 * <p>The enum includes entries for popular databases like MySQL, PostgreSQL, Oracle,
 * SQL Server, DB2, and others, with specific version distinctions where behavior
 * differences are significant.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Detect database version and use appropriate syntax
 * DBVersion version = DBVersion.MySQL_8;
 * if (version.isMySQL()) {
 *     // Use MySQL-specific syntax
 *     query = "SELECT * FROM users LIMIT 10";
 * } else if (version.isPostgreSQL()) {
 *     // Use PostgreSQL-specific syntax
 *     query = "SELECT * FROM users LIMIT 10";
 * }
 *
 * // Handle version-specific features
 * if (version == DBVersion.MySQL_8) {
 *     // Use MySQL 8.x window functions
 *     query = "SELECT *, ROW_NUMBER() OVER (ORDER BY id) as row_num FROM users";
 * } else if (version == DBVersion.MySQL_5_7) {
 *     // Fall back to MySQL 5.7 compatible syntax
 *     query = "SELECT @row_num := @row_num + 1 as row_num, t.* FROM users t, (SELECT @row_num := 0) r";
 * }
 * }</pre>
 *
 * @see DBProductInfo
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
     * Checks if this database version represents any variant of MySQL.
     * 
     * <p>This method performs a case-insensitive check to determine if the
     * enum constant's name starts with "MySQL", which includes all MySQL
     * versions and MySQL_OTHERS.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * DBVersion version = DBVersion.MySQL_8;
     * if (version.isMySQL()) {
     *     // Execute MySQL-specific logic
     * }
     * }</pre>
     *
     * @return {@code true} if this is a MySQL database version, {@code false} otherwise
     */
    public boolean isMySQL() {
        return Strings.startsWithIgnoreCase(name(), "MySQL");
    }

    /**
     * Checks if this database version represents any variant of PostgreSQL.
     * 
     * <p>This method performs a case-insensitive check to determine if the
     * enum constant's name starts with "postgresql", which includes all PostgreSQL
     * versions and PostgreSQL_OTHERS.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * DBVersion version = DBVersion.PostgreSQL_12;
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