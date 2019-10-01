/*
 * Copyright (C) 2015 HaiYang Li
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.landawn.abacus.util;

import java.util.HashMap;
import java.util.Map;

// TODO: Auto-generated Javadoc
/**
 * The Class DBDataTypeMapper.
 *
 * @author Haiyang Li
 * @since 0.8
 */
public final class DBDataTypeMapper {

    /** The Constant mySQLDataTypeMapper. */
    private static final Map<String, String> mySQLDataTypeMapper = new HashMap<>();

    static {
        mySQLDataTypeMapper.put("TIME", "DATETIME");
        mySQLDataTypeMapper.put("CLOB", "TEXT");
        mySQLDataTypeMapper.put("BYTEA", "BLOB");
    }

    /** The Constant postgreSQLDataTypeMapper. */
    private static final Map<String, String> postgreSQLDataTypeMapper = new HashMap<>();

    static {
        postgreSQLDataTypeMapper.put("TINYINT", "SMALLINT");
        postgreSQLDataTypeMapper.put("MEDIUMINT", "INT");
        postgreSQLDataTypeMapper.put("DATETIME", "TIMESTAMP");

        postgreSQLDataTypeMapper.put("TINYTEXT", "TEXT");
        postgreSQLDataTypeMapper.put("TEXT", "TEXT");
        postgreSQLDataTypeMapper.put("MEDIUMTEXT", "TEXT");
        postgreSQLDataTypeMapper.put("LONGTEXT", "TEXT");
        postgreSQLDataTypeMapper.put("TINYBLOB", "BYTEA");
        postgreSQLDataTypeMapper.put("BLOB", "BYTEA");
        postgreSQLDataTypeMapper.put("MEDIUMBLOB", "BYTEA");
        postgreSQLDataTypeMapper.put("LONGBLOB", "BYTEA");
    }

    /**
     * Gets the my SQL data type.
     *
     * @param dataType
     * @return
     */
    public static String getMySQLDataType(String dataType) {
        String newDataType = mySQLDataTypeMapper.get(dataType.toUpperCase());

        return (newDataType == null) ? dataType : newDataType;
    }

    /**
     * Gets the postgre SQL data type.
     *
     * @param dataType
     * @return
     */
    public static String getPostgreSQLDataType(String dataType) {
        String newDataType = postgreSQLDataTypeMapper.get(dataType.toUpperCase());

        return (newDataType == null) ? dataType : newDataType;
    }
}
