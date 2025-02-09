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

public enum DBVersion {

    H2,
    HSQLDB,
    /** The mysql 5 5. */
    MYSQL_5_5,
    /** The mysql 5 6. */
    MYSQL_5_6,
    /** The mysql 5 7. */
    MYSQL_5_7,
    /** The mysql 5 8. */
    MYSQL_5_8,
    /** The mysql 5 9. */
    MYSQL_5_9,
    /** The mysql 6. */
    MYSQL_6,
    /** The mysql 7. */
    MYSQL_7,
    /** The mysql 8. */
    MYSQL_8,
    /** The mysql 9. */
    MYSQL_9,
    /** The mysql 10. */
    MYSQL_10,
    MYSQL_OTHERS,
    /** The postgresql 9 2. */
    //
    POSTGRESQL_9_2,
    /** The postgresql 9 3. */
    POSTGRESQL_9_3,
    /** The postgresql 9 4. */
    POSTGRESQL_9_4,
    /** The postgresql 9 5. */
    POSTGRESQL_9_5,
    /** The postgresql 9 6. */
    POSTGRESQL_9_6,
    /** The postgresql 10. */
    POSTGRESQL_10,
    /** The postgresql 11. */
    POSTGRESQL_11,
    /** The postgresql 12. */
    POSTGRESQL_12,
    POSTGRESQL_OTHERS,
    //
    ORACLE,
    DB2,
    SQL_SERVER,
    OTHERS;

    /**
     *
     *
     * @return
     */
    public boolean isMySQL() {
        return Strings.startsWithIgnoreCase(name(), "mysql");
    }

    /**
     *
     *
     * @return
     */
    public boolean isPostgreSQL() {
        return Strings.startsWithIgnoreCase(name(), "postgresql");
    }
}
