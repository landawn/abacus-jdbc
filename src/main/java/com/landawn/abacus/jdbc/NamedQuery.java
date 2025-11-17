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

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URL;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.BeanInfo;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.Beans;
import com.landawn.abacus.util.ClassUtil;
import com.landawn.abacus.util.EntityId;
import com.landawn.abacus.util.IntList;
import com.landawn.abacus.util.N;

/**
 * A JDBC wrapper class that provides named parameter support for SQL queries, similar to Spring's NamedParameterJdbcTemplate.
 * This class wraps a {@link PreparedStatement} and allows you to use named parameters (e.g., :name, :age) instead of 
 * positional parameters (?) in your SQL queries.
 * 
 * <p>The backed {@code PreparedStatement/CallableStatement} will be closed by default
 * after any execution methods(which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, 
 * for example: get/query/queryForInt/Long/../findFirst/findOnlyOne/list/execute/...).
 * except the {@code 'closeAfterExecution'} flag is set to {@code false} by calling {@code #closeAfterExecution(false)}.
 *
 * <p>Generally, don't cache or reuse the instance of this class,
 * except the {@code 'closeAfterExecution'} flag is set to {@code false} by calling {@code #closeAfterExecution(false)}.
 *
 * <p>The {@code ResultSet} returned by query will always be closed after execution, even {@code 'closeAfterExecution'} flag is set to {@code false}.
 *
 * <p>Remember: parameter/column index in {@code PreparedStatement/ResultSet} starts from 1, not 0.
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * String sql = "SELECT * FROM users WHERE name = :name AND age > :age";
 * try (NamedQuery query = JdbcUtil.prepareNamedQuery(connection, sql)) {
 *     query.setString("name", "John")
 *          .setInt("age", 25)
 *          .query(ResultExtractor.toList(User.class));
 * }
 * }</pre>
 *
 * @see com.landawn.abacus.annotation.ReadOnly
 * @see com.landawn.abacus.annotation.ReadOnlyId
 * @see com.landawn.abacus.annotation.NonUpdatable
 * @see com.landawn.abacus.annotation.Transient
 * @see com.landawn.abacus.annotation.Table
 * @see com.landawn.abacus.annotation.Column
 * @see <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Connection.html">Connection</a>
 * @see <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Statement.html">Statement</a>
 * @see <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/PreparedStatement.html">PreparedStatement</a>
 * @see <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/ResultSet.html">ResultSet</a>
 */
@SuppressWarnings("resource")
public final class NamedQuery extends AbstractQuery<PreparedStatement, NamedQuery> {

    static final int MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP = 5;

    private final ParsedSql namedSql;

    private final List<String> parameterNames;

    private final int parameterCount;

    private Map<String, IntList> paramNameIndexMap;

    NamedQuery(final PreparedStatement stmt, final ParsedSql namedSql) {
        super(stmt);
        this.namedSql = namedSql;
        parameterNames = namedSql.getNamedParameters();
        parameterCount = namedSql.getParameterCount();

        if (N.size(namedSql.getNamedParameters()) != parameterCount) {
            throw new IllegalArgumentException("Invalid named sql: " + namedSql.sql());
        }
    }

    private void initParamNameIndexMap() {
        paramNameIndexMap = new HashMap<>(parameterCount);
        int index = 1;

        for (final String paramName : parameterNames) {
            final IntList indexes = paramNameIndexMap.computeIfAbsent(paramName, k -> new IntList(1));

            indexes.add(index++);
        }
    }

    /**
     * Sets the specified named parameter to SQL NULL.
     * 
     * <p>This method sets all occurrences of the named parameter in the SQL query to NULL.
     * If the parameter appears multiple times in the query, all occurrences will be set to NULL.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setNull("userId", Types.INTEGER);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set to NULL (without the ':' prefix)
     * @param sqlType the SQL type code defined in {@link java.sql.Types}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     * @see java.sql.Types
     */
    public NamedQuery setNull(final String parameterName, final int sqlType) throws SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setNull(i + 1, sqlType);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName); //NOSONAR
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setNull(indexes.get(0), sqlType);
                } else if (indexes.size() == 2) {
                    setNull(indexes.get(0), sqlType);
                    setNull(indexes.get(1), sqlType);
                } else if (indexes.size() == 3) {
                    setNull(indexes.get(0), sqlType);
                    setNull(indexes.get(1), sqlType);
                    setNull(indexes.get(2), sqlType);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setNull(indexes.get(i), sqlType);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets the specified named parameter to SQL NULL with a specified SQL type and type name.
     * 
     * <p>This method is particularly useful for database-specific SQL types. It sets all occurrences 
     * of the named parameter in the SQL query to NULL with the specified type information.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setNull("location", Types.STRUCT, "POINT");
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set to NULL (without the ':' prefix)
     * @param sqlType the SQL type code defined in {@link java.sql.Types}
     * @param typeName the SQL type name (for SQL user-defined or REF types)
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     * @see java.sql.Types
     */
    public NamedQuery setNull(final String parameterName, final int sqlType, final String typeName) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setNull(i + 1, sqlType, typeName);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setNull(indexes.get(0), sqlType, typeName);
                } else if (indexes.size() == 2) {
                    setNull(indexes.get(0), sqlType, typeName);
                    setNull(indexes.get(1), sqlType, typeName);
                } else if (indexes.size() == 3) {
                    setNull(indexes.get(0), sqlType, typeName);
                    setNull(indexes.get(1), sqlType, typeName);
                    setNull(indexes.get(2), sqlType, typeName);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setNull(indexes.get(i), sqlType, typeName);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a boolean value.
     * 
     * <p>This method sets all occurrences of the named parameter in the SQL query to the specified boolean value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setBoolean("isActive", true);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the boolean value to set
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setBoolean(final String parameterName, final boolean x) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setBoolean(i + 1, x);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setBoolean(indexes.get(0), x);
                } else if (indexes.size() == 2) {
                    setBoolean(indexes.get(0), x);
                    setBoolean(indexes.get(1), x);
                } else if (indexes.size() == 3) {
                    setBoolean(indexes.get(0), x);
                    setBoolean(indexes.get(1), x);
                    setBoolean(indexes.get(2), x);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setBoolean(indexes.get(i), x);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a Boolean value.
     * If the value is {@code null}, the parameter will be set to SQL NULL.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Boolean isVerified = getUserVerificationStatus(); // might return null
     * query.setBoolean("isVerified", isVerified);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the Boolean value to set, or {@code null} to set the parameter to SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setBoolean(final String parameterName, final Boolean x) throws IllegalArgumentException, SQLException {
        if (x == null) {
            setNull(parameterName, java.sql.Types.BOOLEAN);
        } else {
            setBoolean(parameterName, x.booleanValue());
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a byte value.
     * 
     * <p>This method sets all occurrences of the named parameter in the SQL query to the specified byte value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setByte("status", (byte) 1);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the byte value to set
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setByte(final String parameterName, final byte x) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setByte(i + 1, x);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setByte(indexes.get(0), x);
                } else if (indexes.size() == 2) {
                    setByte(indexes.get(0), x);
                    setByte(indexes.get(1), x);
                } else if (indexes.size() == 3) {
                    setByte(indexes.get(0), x);
                    setByte(indexes.get(1), x);
                    setByte(indexes.get(2), x);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setByte(indexes.get(i), x);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a Byte value.
     * If the value is {@code null}, the parameter will be set to SQL NULL.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Byte priorityLevel = getPriorityLevel(); // might return null
     * query.setByte("priority", priorityLevel);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the Byte value to set, or {@code null} to set the parameter to SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setByte(final String parameterName, final Byte x) throws IllegalArgumentException, SQLException {
        if (x == null) {
            setNull(parameterName, java.sql.Types.TINYINT);
        } else {
            setByte(parameterName, x.byteValue());
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a short value.
     * 
     * <p>This method sets all occurrences of the named parameter in the SQL query to the specified short value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setShort("quantity", (short) 100);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the short value to set
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setShort(final String parameterName, final short x) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setShort(i + 1, x);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setShort(indexes.get(0), x);
                } else if (indexes.size() == 2) {
                    setShort(indexes.get(0), x);
                    setShort(indexes.get(1), x);
                } else if (indexes.size() == 3) {
                    setShort(indexes.get(0), x);
                    setShort(indexes.get(1), x);
                    setShort(indexes.get(2), x);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setShort(indexes.get(i), x);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a Short value.
     * If the value is {@code null}, the parameter will be set to SQL NULL.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Short categoryId = getCategoryId(); // might return null
     * query.setShort("categoryId", categoryId);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the Short value to set, or {@code null} to set the parameter to SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setShort(final String parameterName, final Short x) throws IllegalArgumentException, SQLException {
        if (x == null) {
            setNull(parameterName, java.sql.Types.SMALLINT);
        } else {
            setShort(parameterName, x.shortValue());
        }

        return this;
    }

    /**
     * Sets the specified named parameter to an int value.
     * 
     * <p>This method sets all occurrences of the named parameter in the SQL query to the specified int value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setInt("userId", 12345);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the int value to set
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setInt(final String parameterName, final int x) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setInt(i + 1, x);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setInt(indexes.get(0), x);
                } else if (indexes.size() == 2) {
                    setInt(indexes.get(0), x);
                    setInt(indexes.get(1), x);
                } else if (indexes.size() == 3) {
                    setInt(indexes.get(0), x);
                    setInt(indexes.get(1), x);
                    setInt(indexes.get(2), x);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setInt(indexes.get(i), x);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets the specified named parameter to an Integer value.
     * 
     * <p>This method handles {@code null} values by setting the parameter to SQL NULL if the provided value is {@code null}.
     * Otherwise, it sets the parameter to the int value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Integer age = getAge(); // might return null
     * query.setInt("age", age);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the Integer value to set, or {@code null} to set the parameter to SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setInt(final String parameterName, final Integer x) throws IllegalArgumentException, SQLException {
        if (x == null) {
            setNull(parameterName, java.sql.Types.INTEGER);
        } else {
            setInt(parameterName, x.intValue());
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a char value by converting it to an int.
     * 
     * <p>This method converts the char to its numeric value (Unicode code point) and stores it as an integer.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setInt("grade", 'A'); // stores 65 (ASCII value of 'A')
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the char value to set
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     * @see #setString(String, char)
     * @deprecated generally {@code char} should be saved as {@code String} in db. Use {@link #setString(String, char)} instead.
     */
    @Deprecated
    public NamedQuery setInt(final String parameterName, final char x) throws IllegalArgumentException, SQLException {
        return setInt(parameterName, (int) x);
    }

    /**
     * Sets the specified named parameter to a Character value by converting it to an int.
     * 
     * <p>This method handles {@code null} values by setting the parameter to SQL NULL if the provided value is {@code null}.
     * Otherwise, it converts the char to its numeric value and stores it as an integer.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Character grade = getGrade(); // might return null
     * query.setInt("grade", grade);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the Character value to set, or {@code null} to set the parameter to SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     * @see #setString(String, Character)
     * @deprecated generally {@code char} should be saved as {@code String} in db. Use {@link #setString(String, Character)} instead.
     */
    @Deprecated
    public NamedQuery setInt(final String parameterName, final Character x) throws IllegalArgumentException, SQLException {
        if (x == null) {
            setNull(parameterName, java.sql.Types.INTEGER);
        } else {
            setInt(parameterName, x.charValue());
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a long value.
     * 
     * <p>This method sets all occurrences of the named parameter in the SQL query to the specified long value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setLong("timestamp", System.currentTimeMillis());
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the long value to set
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setLong(final String parameterName, final long x) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setLong(i + 1, x);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setLong(indexes.get(0), x);
                } else if (indexes.size() == 2) {
                    setLong(indexes.get(0), x);
                    setLong(indexes.get(1), x);
                } else if (indexes.size() == 3) {
                    setLong(indexes.get(0), x);
                    setLong(indexes.get(1), x);
                    setLong(indexes.get(2), x);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setLong(indexes.get(i), x);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a Long value.
     * If the value is {@code null}, the parameter will be set to SQL NULL.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Long accountId = getAccountId(); // might return null
     * query.setLong("accountId", accountId);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the Long value to set, or {@code null} to set the parameter to SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setLong(final String parameterName, final Long x) throws IllegalArgumentException, SQLException {
        if (x == null) {
            setNull(parameterName, java.sql.Types.BIGINT);
        } else {
            setLong(parameterName, x.longValue());
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a BigInteger value by converting it to a long.
     * 
     * <p>This method converts the BigInteger to a long using {@link BigInteger#longValueExact()}.
     * If the BigInteger value is too large to fit in a long, an ArithmeticException will be thrown.
     * Consider using {@link #setBigDecimal(String, BigInteger)} or {@link #setBigIntegerAsString(String, BigInteger)} 
     * for values that might exceed long range.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * BigInteger largeNumber = new BigInteger("9223372036854775807");
     * query.setLong("largeNumber", largeNumber);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the BigInteger value to set, or {@code null} to set the parameter to SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws ArithmeticException if the BigInteger value is too large to fit in a long
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setLong(final String parameterName, final BigInteger x) throws IllegalArgumentException, SQLException {
        if (x == null) {
            setNull(parameterName, java.sql.Types.BIGINT);
        } else {
            setLong(parameterName, x.longValueExact());
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a float value.
     * 
     * <p>This method sets all occurrences of the named parameter in the SQL query to the specified float value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setFloat("price", 19.99f);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the float value to set
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setFloat(final String parameterName, final float x) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setFloat(i + 1, x);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setFloat(indexes.get(0), x);
                } else if (indexes.size() == 2) {
                    setFloat(indexes.get(0), x);
                    setFloat(indexes.get(1), x);
                } else if (indexes.size() == 3) {
                    setFloat(indexes.get(0), x);
                    setFloat(indexes.get(1), x);
                    setFloat(indexes.get(2), x);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setFloat(indexes.get(i), x);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a Float value.
     * If the value is {@code null}, the parameter will be set to SQL NULL.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Float discount = getDiscount(); // might return null
     * query.setFloat("discount", discount);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the Float value to set, or {@code null} to set the parameter to SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setFloat(final String parameterName, final Float x) throws IllegalArgumentException, SQLException {
        if (x == null) {
            setNull(parameterName, java.sql.Types.FLOAT);
        } else {
            setFloat(parameterName, x.floatValue());
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a double value.
     * 
     * <p>This method sets all occurrences of the named parameter in the SQL query to the specified double value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setDouble("latitude", 37.7749);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the double value to set
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setDouble(final String parameterName, final double x) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setDouble(i + 1, x);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setDouble(indexes.get(0), x);
                } else if (indexes.size() == 2) {
                    setDouble(indexes.get(0), x);
                    setDouble(indexes.get(1), x);
                } else if (indexes.size() == 3) {
                    setDouble(indexes.get(0), x);
                    setDouble(indexes.get(1), x);
                    setDouble(indexes.get(2), x);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setDouble(indexes.get(i), x);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a Double value.
     * If the value is {@code null}, the parameter will be set to SQL NULL.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Double balance = getAccountBalance(); // might return null
     * query.setDouble("balance", balance);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the Double value to set, or {@code null} to set the parameter to SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setDouble(final String parameterName, final Double x) throws IllegalArgumentException, SQLException {
        if (x == null) {
            setNull(parameterName, java.sql.Types.DOUBLE);
        } else {
            setDouble(parameterName, x.doubleValue());
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a BigDecimal value.
     * 
     * <p>This method sets all occurrences of the named parameter in the SQL query to the specified BigDecimal value.
     * BigDecimal is the preferred type for precise decimal values in databases.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setBigDecimal("amount", new BigDecimal("123.45"));
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the BigDecimal value to set, or {@code null} to set the parameter to SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setBigDecimal(final String parameterName, final BigDecimal x) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setBigDecimal(i + 1, x);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setBigDecimal(indexes.get(0), x);
                } else if (indexes.size() == 2) {
                    setBigDecimal(indexes.get(0), x);
                    setBigDecimal(indexes.get(1), x);
                } else if (indexes.size() == 3) {
                    setBigDecimal(indexes.get(0), x);
                    setBigDecimal(indexes.get(1), x);
                    setBigDecimal(indexes.get(2), x);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setBigDecimal(indexes.get(i), x);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a BigInteger value by converting it to BigDecimal.
     * 
     * <p>This method converts the BigInteger to a BigDecimal and stores it as a DECIMAL type.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * BigInteger bigInt = new BigInteger("123456789012345678901234567890");
     * query.setBigDecimal("largeValue", bigInt);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the BigInteger value to set, or {@code null} to set the parameter to SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setBigDecimal(final String parameterName, final BigInteger x) throws IllegalArgumentException, SQLException {
        if (x == null) {
            return setNull(parameterName, Types.DECIMAL);
        } else {
            return setBigDecimal(parameterName, new BigDecimal(x));
        }
    }

    /**
     * Sets the specified named parameter to a BigInteger value by converting it to String.
     * 
     * <p>This method stores very large integer values as strings in the database. This is useful
     * when the numeric value exceeds the range of standard numeric types.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * BigInteger veryLargeNumber = new BigInteger("99999999999999999999999999999999");
     * query.setBigIntegerAsString("bigNumber", veryLargeNumber);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the BigInteger value to set, or {@code null} to set the parameter to SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     * @see #setString(String, BigInteger)
     * @see #setBigDecimal(String, BigInteger)
     * @see #setLong(String, BigInteger)
     */
    @Beta
    public NamedQuery setBigIntegerAsString(final String parameterName, final BigInteger x) throws IllegalArgumentException, SQLException {
        return setString(parameterName, x);
    }

    /**
     * Sets the specified named parameter to a String value.
     * 
     * <p>This method sets all occurrences of the named parameter in the SQL query to the specified String value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setString("username", "john_doe");
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the String value to set, or {@code null} to set the parameter to SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setString(final String parameterName, final String x) throws SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setString(i + 1, x);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setString(indexes.get(0), x);
                } else if (indexes.size() == 2) {
                    setString(indexes.get(0), x);
                    setString(indexes.get(1), x);
                } else if (indexes.size() == 3) {
                    setString(indexes.get(0), x);
                    setString(indexes.get(1), x);
                    setString(indexes.get(2), x);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setString(indexes.get(i), x);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a CharSequence value.
     * 
     * <p>This method converts the CharSequence (StringBuilder, StringBuffer, etc.) to a String
     * and sets the parameter. Null values are handled appropriately.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * StringBuilder sb = new StringBuilder("Hello World");
     * query.setString("message", sb);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the CharSequence value to set, or {@code null} to set the parameter to SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setString(final String parameterName, final CharSequence x) throws IllegalArgumentException, SQLException {
        return setString(parameterName, x == null ? (String) null : x.toString()); //NOSONAR
    }

    /**
     * Sets the specified named parameter to a single character value.
     * 
     * <p>This method converts the char to a String and sets the parameter.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setString("grade", 'A');
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the char value to set
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setString(final String parameterName, final char x) throws IllegalArgumentException, SQLException {
        return setString(parameterName, String.valueOf(x));
    }

    /**
     * Sets the specified named parameter to a Character value.
     * 
     * <p>This method converts the Character to a String and sets the parameter.
     * Null values are handled appropriately.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Character initial = getMiddleInitial(); // might return null
     * query.setString("middleInitial", initial);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the Character value to set, or {@code null} to set the parameter to SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setString(final String parameterName, final Character x) throws IllegalArgumentException, SQLException {
        return setString(parameterName, x == null ? (String) null : x.toString()); //NOSONAR
    }

    /**
     * Sets the specified named parameter to a BigInteger value as a String.
     * 
     * <p>This method converts the BigInteger to its decimal string representation.
     * This is useful for storing very large integers that exceed database numeric limits.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * BigInteger bigInt = new BigInteger("123456789012345678901234567890");
     * query.setString("largeNumber", bigInt);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the BigInteger value to set, or {@code null} to set the parameter to SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setString(final String parameterName, final BigInteger x) throws IllegalArgumentException, SQLException {
        if (x == null) {
            return setNull(parameterName, Types.VARCHAR);
        } else {
            return setString(parameterName, x.toString(10));
        }
    }

    /**
     * Sets the specified named parameter to a national character string value.
     * 
     * <p>This method is used for databases that distinguish between regular strings and national character strings
     * (NCHAR, NVARCHAR). It sets all occurrences of the named parameter to the specified value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setNString("description", "Description with unicode: 你好");
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the String value to set, or {@code null} to set the parameter to SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setNString(final String parameterName, final String x) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setNString(i + 1, x);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setNString(indexes.get(0), x);
                } else if (indexes.size() == 2) {
                    setNString(indexes.get(0), x);
                    setNString(indexes.get(1), x);
                } else if (indexes.size() == 3) {
                    setNString(indexes.get(0), x);
                    setNString(indexes.get(1), x);
                    setNString(indexes.get(2), x);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setNString(indexes.get(i), x);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a national character string value from a CharSequence.
     * 
     * <p>This method converts the CharSequence to a String and sets it as a national character string.
     * Useful for databases that support NCHAR/NVARCHAR types.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * StringBuilder unicodeText = new StringBuilder("Unicode text: 世界");
     * query.setNString("unicodeField", unicodeText);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the CharSequence value to set, or {@code null} to set the parameter to SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setNString(final String parameterName, final CharSequence x) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setNString(i + 1, x);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setNString(indexes.get(0), x);
                } else if (indexes.size() == 2) {
                    setNString(indexes.get(0), x);
                    setNString(indexes.get(1), x);
                } else if (indexes.size() == 3) {
                    setNString(indexes.get(0), x);
                    setNString(indexes.get(1), x);
                    setNString(indexes.get(2), x);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setNString(indexes.get(i), x);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a java.sql.Date value.
     * 
     * <p>This method sets a date value without time information. For date with time,
     * use {@link #setTimestamp(String, java.sql.Timestamp)}.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setDate("birthDate", java.sql.Date.valueOf("1990-01-15"));
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the java.sql.Date value to set, or {@code null} to set the parameter to SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setDate(final String parameterName, final java.sql.Date x) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setDate(i + 1, x);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setDate(indexes.get(0), x);
                } else if (indexes.size() == 2) {
                    setDate(indexes.get(0), x);
                    setDate(indexes.get(1), x);
                } else if (indexes.size() == 3) {
                    setDate(indexes.get(0), x);
                    setDate(indexes.get(1), x);
                    setDate(indexes.get(2), x);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setDate(indexes.get(i), x);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a java.util.Date value.
     * 
     * <p>This method converts java.util.Date to java.sql.Date, truncating time information.
     * For preserving time information, use {@link #setTimestamp(String, java.util.Date)}.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Date utilDate = new Date();
     * query.setDate("createdDate", utilDate);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the java.util.Date value to set, or {@code null} to set the parameter to SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setDate(final String parameterName, final java.util.Date x) throws IllegalArgumentException, SQLException {
        setDate(parameterName, x == null ? null : x instanceof java.sql.Date ? (java.sql.Date) x : new java.sql.Date(x.getTime()));

        return this;
    }

    /**
     * Sets the specified named parameter to a LocalDate value.
     * 
     * <p>This method converts the Java 8 LocalDate to java.sql.Date for database storage.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * LocalDate today = LocalDate.now();
     * query.setDate("eventDate", today);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the LocalDate value to set, or {@code null} to set the parameter to SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setDate(final String parameterName, final LocalDate x) throws IllegalArgumentException, SQLException {
        setDate(parameterName, x == null ? null : java.sql.Date.valueOf(x));

        return this;
    }

    /**
     * Sets the specified named parameter to a java.sql.Time value.
     * 
     * <p>This method sets a time value without date information.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setTime("startTime", java.sql.Time.valueOf("09:30:00"));
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the java.sql.Time value to set, or {@code null} to set the parameter to SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setTime(final String parameterName, final java.sql.Time x) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setTime(i + 1, x);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setTime(indexes.get(0), x);
                } else if (indexes.size() == 2) {
                    setTime(indexes.get(0), x);
                    setTime(indexes.get(1), x);
                } else if (indexes.size() == 3) {
                    setTime(indexes.get(0), x);
                    setTime(indexes.get(1), x);
                    setTime(indexes.get(2), x);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setTime(indexes.get(i), x);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a time value using a java.util.Date.
     * 
     * <p>This method converts a java.util.Date to java.sql.Time, preserving only the time
     * portion (hours, minutes, seconds) and discarding the date portion. If the provided
     * Date is already a java.sql.Time instance, it is used directly without conversion.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * java.util.Date date = new Date(); // Current date and time
     * query.setTime("startTime", date); // Only time portion is used
     * 
     * // Using Calendar
     * Calendar cal = Calendar.getInstance();
     * cal.set(Calendar.HOUR_OF_DAY, 14);
     * cal.set(Calendar.MINUTE, 30);
     * query.setTime("startTime", cal.getTime());
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the java.util.Date value containing the time to set, or {@code null} to set the parameter to SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setTime(final String parameterName, final java.util.Date x) throws IllegalArgumentException, SQLException {
        setTime(parameterName, x == null ? null : x instanceof java.sql.Time ? (java.sql.Time) x : new java.sql.Time(x.getTime()));

        return this;
    }

    /**
     * Sets the specified named parameter to a time value using a LocalTime.
     * 
     * <p>This method converts a java.time.LocalTime to java.sql.Time for database operations.
     * LocalTime represents a time without a time-zone in the ISO-8601 calendar system,
     * such as 10:15:30.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * LocalTime lunchTime = LocalTime.of(12, 30, 0);
     * query.setTime("lunchTime", lunchTime);
     * 
     * // Using current time
     * query.setTime("currentTime", LocalTime.now());
     * 
     * // Parsing from string
     * query.setTime("endTime", LocalTime.parse("17:30:00"));
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the LocalTime value to set, or {@code null} to set the parameter to SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setTime(final String parameterName, final LocalTime x) throws IllegalArgumentException, SQLException {
        setTime(parameterName, x == null ? null : java.sql.Time.valueOf(x));

        return this;
    }

    /**
     * Sets the specified named parameter to a java.sql.Timestamp value.
     * 
     * <p>This method sets a timestamp value that includes both date and time information
     * with nanosecond precision. Timestamps are typically used for recording when events
     * occur in the database.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Using current timestamp
     * query.setTimestamp("createdAt", new Timestamp(System.currentTimeMillis()));
     * 
     * // Creating specific timestamp
     * query.setTimestamp("eventTime", Timestamp.valueOf("2023-12-25 10:30:00"));
     * 
     * // Setting null
     * query.setTimestamp("updatedAt", null);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the java.sql.Timestamp value to set, or {@code null} to set the parameter to SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setTimestamp(final String parameterName, final java.sql.Timestamp x) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setTimestamp(i + 1, x);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setTimestamp(indexes.get(0), x);
                } else if (indexes.size() == 2) {
                    setTimestamp(indexes.get(0), x);
                    setTimestamp(indexes.get(1), x);
                } else if (indexes.size() == 3) {
                    setTimestamp(indexes.get(0), x);
                    setTimestamp(indexes.get(1), x);
                    setTimestamp(indexes.get(2), x);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setTimestamp(indexes.get(i), x);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a timestamp value using a java.util.Date.
     * 
     * <p>This method converts a java.util.Date to java.sql.Timestamp, preserving both
     * date and time information. If the provided Date is already a java.sql.Timestamp
     * instance, it is used directly without conversion.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Date now = new Date();
     * query.setTimestamp("lastLogin", now);
     * 
     * // Using Calendar
     * Calendar cal = Calendar.getInstance();
     * cal.add(Calendar.DAY_OF_MONTH, -7); // 7 days ago
     * query.setTimestamp("weekAgo", cal.getTime());
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the java.util.Date value to convert and set, or {@code null} to set the parameter to SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setTimestamp(final String parameterName, final java.util.Date x) throws IllegalArgumentException, SQLException {
        setTimestamp(parameterName, x == null ? null : x instanceof java.sql.Timestamp ? (java.sql.Timestamp) x : new java.sql.Timestamp(x.getTime()));

        return this;
    }

    /**
     * Sets the specified named parameter to a timestamp value using a LocalDateTime.
     * 
     * <p>This method converts a java.time.LocalDateTime to java.sql.Timestamp. LocalDateTime
     * represents a date-time without a time-zone in the ISO-8601 calendar system,
     * such as 2023-12-25T10:15:30.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * LocalDateTime dateTime = LocalDateTime.of(2023, 12, 25, 10, 30, 0);
     * query.setTimestamp("eventDate", dateTime);
     * 
     * // Using current date-time
     * query.setTimestamp("createdAt", LocalDateTime.now());
     * 
     * // Parsing from string
     * query.setTimestamp("deadline", LocalDateTime.parse("2023-12-31T23:59:59"));
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the LocalDateTime value to set, or {@code null} to set the parameter to SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setTimestamp(final String parameterName, final LocalDateTime x) throws IllegalArgumentException, SQLException {
        setTimestamp(parameterName, x == null ? null : Timestamp.valueOf(x));

        return this;
    }

    /**
     * Sets the specified named parameter to a timestamp value using a ZonedDateTime.
     * 
     * <p>This method converts a java.time.ZonedDateTime to java.sql.Timestamp by extracting
     * the instant. The time zone information is used for the conversion but is not stored
     * in the resulting Timestamp.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ZonedDateTime zonedDateTime = ZonedDateTime.now(ZoneId.of("America/New_York"));
     * query.setTimestamp("meetingTime", zonedDateTime);
     * 
     * // Creating specific zoned date-time
     * ZonedDateTime utcTime = ZonedDateTime.parse("2023-12-25T10:30:00Z[UTC]");
     * query.setTimestamp("utcEvent", utcTime);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the ZonedDateTime value to set, or {@code null} to set the parameter to SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setTimestamp(final String parameterName, final ZonedDateTime x) throws IllegalArgumentException, SQLException {
        setTimestamp(parameterName, x == null ? null : Timestamp.from(x.toInstant()));

        return this;
    }

    /**
     * Sets the specified named parameter to a timestamp value using an OffsetDateTime.
     * 
     * <p>This method converts a java.time.OffsetDateTime to java.sql.Timestamp by extracting
     * the instant. The offset information is used for the conversion but is not stored
     * in the resulting Timestamp.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OffsetDateTime offsetDateTime = OffsetDateTime.now();
     * query.setTimestamp("recordedAt", offsetDateTime);
     * 
     * // With specific offset
     * OffsetDateTime utcPlus8 = OffsetDateTime.parse("2023-12-25T10:30:00+08:00");
     * query.setTimestamp("asiaTime", utcPlus8);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the OffsetDateTime value to set, or {@code null} to set the parameter to SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setTimestamp(final String parameterName, final OffsetDateTime x) throws IllegalArgumentException, SQLException {
        setTimestamp(parameterName, x == null ? null : Timestamp.from(x.toInstant()));

        return this;
    }

    /**
     * Sets the specified named parameter to a timestamp value using an Instant.
     * 
     * <p>This method converts a java.time.Instant to java.sql.Timestamp. An Instant
     * represents a point on the time-line in UTC.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Current instant
     * query.setTimestamp("timestamp", Instant.now());
     * 
     * // Specific instant
     * Instant epochPlus1Hour = Instant.ofEpochSecond(3600);
     * query.setTimestamp("startTime", epochPlus1Hour);
     * 
     * // From milliseconds
     * query.setTimestamp("eventTime", Instant.ofEpochMilli(System.currentTimeMillis()));
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the Instant value to set, or {@code null} to set the parameter to SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setTimestamp(final String parameterName, final Instant x) throws IllegalArgumentException, SQLException {
        setTimestamp(parameterName, x == null ? null : Timestamp.from(x));

        return this;
    }

    /**
     * Sets the specified named parameter to a byte array value.
     * 
     * <p>This method is typically used for storing binary data such as images, files,
     * or serialized objects in BLOB columns. The entire byte array is sent to the database.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Storing file content
     * byte[] fileContent = Files.readAllBytes(Paths.get("document.pdf"));
     * query.setBytes("fileData", fileContent);
     * 
     * // Storing serialized object
     * ByteArrayOutputStream baos = new ByteArrayOutputStream();
     * ObjectOutputStream oos = new ObjectOutputStream(baos);
     * oos.writeObject(myObject);
     * query.setBytes("serializedData", baos.toByteArray());
     * 
     * // Setting null
     * query.setBytes("binaryData", null);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the byte array to set, or {@code null} to set the parameter to SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setBytes(final String parameterName, final byte[] x) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setBytes(i + 1, x);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setBytes(indexes.get(0), x);
                } else if (indexes.size() == 2) {
                    setBytes(indexes.get(0), x);
                    setBytes(indexes.get(1), x);
                } else if (indexes.size() == 3) {
                    setBytes(indexes.get(0), x);
                    setBytes(indexes.get(1), x);
                    setBytes(indexes.get(2), x);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setBytes(indexes.get(i), x);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets the specified named parameter to an ASCII stream value.
     * 
     * <p>This method is used to set very large ASCII values. The JDBC driver will read
     * the data from the stream as needed. The stream should contain only ASCII characters.
     * Note that the stream will be read when the query is executed, not when this method is called.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // From file
     * FileInputStream fis = new FileInputStream("large_text.txt");
     * query.setAsciiStream("textData", fis);
     * 
     * // From string
     * String text = "Large ASCII text content...";
     * InputStream stream = new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII));
     * query.setAsciiStream("content", stream);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the InputStream containing ASCII data, or {@code null} to set the parameter to SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setAsciiStream(final String parameterName, final InputStream x) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setAsciiStream(i + 1, x);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setAsciiStream(indexes.get(0), x);
                } else if (indexes.size() == 2) {
                    setAsciiStream(indexes.get(0), x);
                    setAsciiStream(indexes.get(1), x);
                } else if (indexes.size() == 3) {
                    setAsciiStream(indexes.get(0), x);
                    setAsciiStream(indexes.get(1), x);
                    setAsciiStream(indexes.get(2), x);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setAsciiStream(indexes.get(i), x);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets the specified named parameter to an ASCII stream value with a specified length.
     * 
     * <p>This method is used to set very large ASCII values when the length is known.
     * The JDBC driver will read exactly 'length' bytes from the stream. This can be
     * more efficient than the length-unspecified version.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * File file = new File("data.txt");
     * FileInputStream fis = new FileInputStream(file);
     * query.setAsciiStream("fileContent", fis, file.length());
     * 
     * // With ByteArrayInputStream
     * byte[] data = "ASCII content".getBytes(StandardCharsets.US_ASCII);
     * ByteArrayInputStream bais = new ByteArrayInputStream(data);
     * query.setAsciiStream("content", bais, data.length);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the InputStream containing ASCII data, or {@code null} to set the parameter to SQL NULL
     * @param length the number of bytes in the stream
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setAsciiStream(final String parameterName, final InputStream x, final long length) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setAsciiStream(i + 1, x, length);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setAsciiStream(indexes.get(0), x, length);
                } else if (indexes.size() == 2) {
                    setAsciiStream(indexes.get(0), x, length);
                    setAsciiStream(indexes.get(1), x, length);
                } else if (indexes.size() == 3) {
                    setAsciiStream(indexes.get(0), x, length);
                    setAsciiStream(indexes.get(1), x, length);
                    setAsciiStream(indexes.get(2), x, length);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setAsciiStream(indexes.get(i), x, length);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a binary stream value.
     * 
     * <p>This method is used to set very large binary values such as images, documents,
     * or other binary data. The JDBC driver will read the data from the stream as needed.
     * The stream will be read when the query is executed, not when this method is called.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Uploading image
     * FileInputStream imageStream = new FileInputStream("photo.jpg");
     * query.setBinaryStream("photo", imageStream);
     * 
     * // From byte array
     * byte[] documentData = getDocumentBytes();
     * ByteArrayInputStream bais = new ByteArrayInputStream(documentData);
     * query.setBinaryStream("document", bais);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the InputStream containing binary data, or {@code null} to set the parameter to SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setBinaryStream(final String parameterName, final InputStream x) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setBinaryStream(i + 1, x);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setBinaryStream(indexes.get(0), x);
                } else if (indexes.size() == 2) {
                    setBinaryStream(indexes.get(0), x);
                    setBinaryStream(indexes.get(1), x);
                } else if (indexes.size() == 3) {
                    setBinaryStream(indexes.get(0), x);
                    setBinaryStream(indexes.get(1), x);
                    setBinaryStream(indexes.get(2), x);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setBinaryStream(indexes.get(i), x);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a binary stream value with a specified length.
     * 
     * <p>This method is used to set very large binary values when the length is known.
     * The JDBC driver will read exactly 'length' bytes from the stream. This can be
     * more efficient than the length-unspecified version and is required by some drivers.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Uploading file with known size
     * File file = new File("document.pdf");
     * FileInputStream fis = new FileInputStream(file);
     * query.setBinaryStream("pdfData", fis, file.length());
     * 
     * // From byte array with specific length
     * byte[] data = getLargeData();
     * ByteArrayInputStream bais = new ByteArrayInputStream(data);
     * query.setBinaryStream("binaryData", bais, data.length);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the InputStream containing binary data, or {@code null} to set the parameter to SQL NULL
     * @param length the number of bytes in the stream
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setBinaryStream(final String parameterName, final InputStream x, final long length) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setBinaryStream(i + 1, x, length);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setBinaryStream(indexes.get(0), x, length);
                } else if (indexes.size() == 2) {
                    setBinaryStream(indexes.get(0), x, length);
                    setBinaryStream(indexes.get(1), x, length);
                } else if (indexes.size() == 3) {
                    setBinaryStream(indexes.get(0), x, length);
                    setBinaryStream(indexes.get(1), x, length);
                    setBinaryStream(indexes.get(2), x, length);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setBinaryStream(indexes.get(i), x, length);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a character stream value.
     * 
     * <p>This method is used to set very large character values such as CLOB data.
     * The JDBC driver will read the data from the Reader as needed. The Reader should
     * contain Unicode character data.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // From file
     * FileReader reader = new FileReader("large_text.txt", StandardCharsets.UTF_8);
     * query.setCharacterStream("content", reader);
     * 
     * // From string
     * String largeText = getLargeTextContent();
     * StringReader stringReader = new StringReader(largeText);
     * query.setCharacterStream("description", stringReader);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the Reader containing character data, or {@code null} to set the parameter to SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setCharacterStream(final String parameterName, final Reader x) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setCharacterStream(i + 1, x);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setCharacterStream(indexes.get(0), x);
                } else if (indexes.size() == 2) {
                    setCharacterStream(indexes.get(0), x);
                    setCharacterStream(indexes.get(1), x);
                } else if (indexes.size() == 3) {
                    setCharacterStream(indexes.get(0), x);
                    setCharacterStream(indexes.get(1), x);
                    setCharacterStream(indexes.get(2), x);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setCharacterStream(indexes.get(i), x);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a character stream value with a specified length.
     * 
     * <p>This method is used to set very large character values when the length is known.
     * The JDBC driver will read exactly 'length' characters from the Reader. This can be
     * more efficient than the length-unspecified version.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // From file with known character count
     * String content = readFileContent();
     * StringReader reader = new StringReader(content);
     * query.setCharacterStream("largeText", reader, content.length());
     * 
     * // Limited portion of text
     * String fullText = getFullText();
     * StringReader reader = new StringReader(fullText);
     * query.setCharacterStream("summary", reader, 1000); // First 1000 characters
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the Reader containing character data, or {@code null} to set the parameter to SQL NULL
     * @param length the number of characters in the stream
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setCharacterStream(final String parameterName, final Reader x, final long length) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setCharacterStream(i + 1, x, length);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setCharacterStream(indexes.get(0), x, length);
                } else if (indexes.size() == 2) {
                    setCharacterStream(indexes.get(0), x, length);
                    setCharacterStream(indexes.get(1), x, length);
                } else if (indexes.size() == 3) {
                    setCharacterStream(indexes.get(0), x, length);
                    setCharacterStream(indexes.get(1), x, length);
                    setCharacterStream(indexes.get(2), x, length);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setCharacterStream(indexes.get(i), x, length);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a national character stream value.
     * 
     * <p>This method is used to set very large NCHAR, NVARCHAR, or NCLOB values.
     * The data will be read from the stream as needed. This is used for databases
     * that distinguish between character and national character data types.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Unicode text with special characters
     * String unicodeText = "Hello 世界 🌍";
     * StringReader reader = new StringReader(unicodeText);
     * query.setNCharacterStream("unicodeContent", reader);
     * 
     * // From file containing unicode data
     * FileReader fileReader = new FileReader("unicode_text.txt", StandardCharsets.UTF_8);
     * query.setNCharacterStream("content", fileReader);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the Reader containing national character data, or {@code null} to set the parameter to SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setNCharacterStream(final String parameterName, final Reader x) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setNCharacterStream(i + 1, x);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setNCharacterStream(indexes.get(0), x);
                } else if (indexes.size() == 2) {
                    setNCharacterStream(indexes.get(0), x);
                    setNCharacterStream(indexes.get(1), x);
                } else if (indexes.size() == 3) {
                    setNCharacterStream(indexes.get(0), x);
                    setNCharacterStream(indexes.get(1), x);
                    setNCharacterStream(indexes.get(2), x);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setNCharacterStream(indexes.get(i), x);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a national character stream value with a specified length.
     * 
     * <p>This method is used to set very large NCHAR, NVARCHAR, or NCLOB values when
     * the length is known. The JDBC driver will read exactly 'length' characters from
     * the Reader.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Unicode content with known length
     * String unicodeContent = getUnicodeContent();
     * StringReader reader = new StringReader(unicodeContent);
     * query.setNCharacterStream("description", reader, unicodeContent.length());
     * 
     * // Partial content
     * String fullText = "Large unicode text with emojis 😀😃😄...";
     * query.setNCharacterStream("preview", new StringReader(fullText), 100);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the Reader containing national character data, or {@code null} to set the parameter to SQL NULL
     * @param length the number of characters in the stream
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setNCharacterStream(final String parameterName, final Reader x, final long length) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setNCharacterStream(i + 1, x, length);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setNCharacterStream(indexes.get(0), x, length);
                } else if (indexes.size() == 2) {
                    setNCharacterStream(indexes.get(0), x, length);
                    setNCharacterStream(indexes.get(1), x, length);
                } else if (indexes.size() == 3) {
                    setNCharacterStream(indexes.get(0), x, length);
                    setNCharacterStream(indexes.get(1), x, length);
                    setNCharacterStream(indexes.get(2), x, length);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setNCharacterStream(indexes.get(i), x, length);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a java.sql.Blob value.
     * 
     * <p>This method is used to set a BLOB (Binary Large Object) parameter. BLOBs are
     * typically used for storing large binary data such as images, audio, or video files
     * in the database.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Creating a Blob from connection
     * Blob blob = connection.createBlob();
     * blob.setBytes(1, imageBytes);
     * query.setBlob("image", blob);
     * 
     * // Setting existing Blob
     * Blob existingBlob = resultSet.getBlob("data");
     * query.setBlob("binaryData", existingBlob);
     * 
     * // Setting null
     * query.setBlob("attachment", null);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the java.sql.Blob object, or {@code null} to set the parameter to SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setBlob(final String parameterName, final java.sql.Blob x) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setBlob(i + 1, x);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setBlob(indexes.get(0), x);
                } else if (indexes.size() == 2) {
                    setBlob(indexes.get(0), x);
                    setBlob(indexes.get(1), x);
                } else if (indexes.size() == 3) {
                    setBlob(indexes.get(0), x);
                    setBlob(indexes.get(1), x);
                    setBlob(indexes.get(2), x);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setBlob(indexes.get(i), x);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a BLOB value using an InputStream.
     * 
     * <p>This method creates a BLOB from the provided InputStream. The JDBC driver
     * will read all available data from the stream. This is convenient when you have
     * binary data in a stream format.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // From file
     * FileInputStream fis = new FileInputStream("image.jpg");
     * query.setBlob("photo", fis);
     * 
     * // From byte array
     * byte[] pdfData = generatePDF();
     * ByteArrayInputStream bais = new ByteArrayInputStream(pdfData);
     * query.setBlob("document", bais);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the InputStream containing the BLOB data, or {@code null} to set the parameter to SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setBlob(final String parameterName, final InputStream x) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setBlob(i + 1, x);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setBlob(indexes.get(0), x);
                } else if (indexes.size() == 2) {
                    setBlob(indexes.get(0), x);
                    setBlob(indexes.get(1), x);
                } else if (indexes.size() == 3) {
                    setBlob(indexes.get(0), x);
                    setBlob(indexes.get(1), x);
                    setBlob(indexes.get(2), x);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setBlob(indexes.get(i), x);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a BLOB value using an InputStream with a specified length.
     * 
     * <p>This method creates a BLOB from the provided InputStream, reading exactly
     * 'length' bytes. This is more efficient when the size is known and ensures
     * exactly the specified amount of data is read.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // From file with known size
     * File imageFile = new File("large_image.jpg");
     * FileInputStream fis = new FileInputStream(imageFile);
     * query.setBlob("image", fis, imageFile.length());
     * 
     * // Partial data from stream
     * byte[] fullData = getFullData();
     * ByteArrayInputStream bais = new ByteArrayInputStream(fullData);
     * query.setBlob("preview", bais, 1024); // First 1KB only
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the InputStream containing the BLOB data, or {@code null} to set the parameter to SQL NULL
     * @param length the number of bytes to read from the stream
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setBlob(final String parameterName, final InputStream x, final long length) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setBlob(i + 1, x, length);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setBlob(indexes.get(0), x, length);
                } else if (indexes.size() == 2) {
                    setBlob(indexes.get(0), x, length);
                    setBlob(indexes.get(1), x, length);
                } else if (indexes.size() == 3) {
                    setBlob(indexes.get(0), x, length);
                    setBlob(indexes.get(1), x, length);
                    setBlob(indexes.get(2), x, length);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setBlob(indexes.get(i), x, length);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a java.sql.Clob value.
     * 
     * <p>This method is used to set a CLOB (Character Large Object) parameter. CLOBs are
     * typically used for storing large text data such as documents, XML, or JSON data
     * in the database.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Creating a Clob from connection
     * Clob clob = connection.createClob();
     * clob.setString(1, largeTextContent);
     * query.setClob("content", clob);
     * 
     * // Setting existing Clob
     * Clob existingClob = resultSet.getClob("description");
     * query.setClob("textData", existingClob);
     * 
     * // Setting null
     * query.setClob("notes", null);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param x the java.sql.Clob object, or {@code null} to set the parameter to SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setClob(final String parameterName, final java.sql.Clob x) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setClob(i + 1, x);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setClob(indexes.get(0), x);
                } else if (indexes.size() == 2) {
                    setClob(indexes.get(0), x);
                    setClob(indexes.get(1), x);
                } else if (indexes.size() == 3) {
                    setClob(indexes.get(0), x);
                    setClob(indexes.get(1), x);
                    setClob(indexes.get(2), x);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setClob(indexes.get(i), x);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets a CLOB (Character Large Object) parameter using a Reader for the specified parameter name.
     * The JDBC driver will read data from the Reader as needed until end-of-file is reached.
     * 
     * <p>This method is useful for setting large text data without loading it entirely into memory.
     * If the parameter name appears multiple times in the query, all occurrences will be set to the same value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setClob("content", new FileReader("large_text.txt"));
     * }</pre>
     *
     * @param parameterName the name of the parameter (without the ':' prefix)
     * @param x the Reader object containing the CLOB data, or {@code null} to set SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the query
     * @throws SQLException if a database access error occurs or this method is called on a closed PreparedStatement
     */
    public NamedQuery setClob(final String parameterName, final Reader x) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setClob(i + 1, x);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setClob(indexes.get(0), x);
                } else if (indexes.size() == 2) {
                    setClob(indexes.get(0), x);
                    setClob(indexes.get(1), x);
                } else if (indexes.size() == 3) {
                    setClob(indexes.get(0), x);
                    setClob(indexes.get(1), x);
                    setClob(indexes.get(2), x);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setClob(indexes.get(i), x);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets a CLOB (Character Large Object) parameter using a Reader with a specified length for the parameter name.
     * The JDBC driver will read exactly 'length' characters from the Reader.
     * 
     * <p>This method provides more control over the amount of data read from the Reader compared to 
     * {@link #setClob(String, Reader)}. If the parameter name appears multiple times in the query, 
     * all occurrences will be set to the same value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setClob("description", new StringReader(longText), longText.length());
     * }</pre>
     *
     * @param parameterName the name of the parameter (without the ':' prefix)
     * @param x the Reader object containing the CLOB data
     * @param length the number of characters in the stream
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the query
     * @throws SQLException if a database access error occurs, this method is called on a closed PreparedStatement,
     *         or the length is less than zero
     */
    public NamedQuery setClob(final String parameterName, final Reader x, final long length) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setClob(i + 1, x, length);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setClob(indexes.get(0), x, length);
                } else if (indexes.size() == 2) {
                    setClob(indexes.get(0), x, length);
                    setClob(indexes.get(1), x, length);
                } else if (indexes.size() == 3) {
                    setClob(indexes.get(0), x, length);
                    setClob(indexes.get(1), x, length);
                    setClob(indexes.get(2), x, length);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setClob(indexes.get(i), x, length);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets an NCLOB (National Character Large Object) parameter for the specified parameter name.
     * NCLOBs are used to store large amounts of national character set data.
     * 
     * <p>If the parameter name appears multiple times in the query, all occurrences will be set to the same value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * NClob nclob = connection.createNClob();
     * nclob.setString(1, unicodeText);
     * query.setNClob("unicode_content", nclob);
     * }</pre>
     *
     * @param parameterName the name of the parameter (without the ':' prefix)
     * @param x the NClob object, or {@code null} to set SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the query
     * @throws SQLException if a database access error occurs or this method is called on a closed PreparedStatement
     */
    public NamedQuery setNClob(final String parameterName, final java.sql.NClob x) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setNClob(i + 1, x);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setNClob(indexes.get(0), x);
                } else if (indexes.size() == 2) {
                    setNClob(indexes.get(0), x);
                    setNClob(indexes.get(1), x);
                } else if (indexes.size() == 3) {
                    setNClob(indexes.get(0), x);
                    setNClob(indexes.get(1), x);
                    setNClob(indexes.get(2), x);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setNClob(indexes.get(i), x);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets an NCLOB (National Character Large Object) parameter using a Reader for the specified parameter name.
     * The JDBC driver will read data from the Reader as needed until end-of-file is reached.
     * 
     * <p>This method is useful for setting large Unicode text data without loading it entirely into memory.
     * If the parameter name appears multiple times in the query, all occurrences will be set to the same value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setNClob("unicode_text", new FileReader("unicode_data.txt", StandardCharsets.UTF_8));
     * }</pre>
     *
     * @param parameterName the name of the parameter (without the ':' prefix)
     * @param x the Reader object containing the NCLOB data, or {@code null} to set SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the query
     * @throws SQLException if a database access error occurs or this method is called on a closed PreparedStatement
     */
    public NamedQuery setNClob(final String parameterName, final Reader x) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setNClob(i + 1, x);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setNClob(indexes.get(0), x);
                } else if (indexes.size() == 2) {
                    setNClob(indexes.get(0), x);
                    setNClob(indexes.get(1), x);
                } else if (indexes.size() == 3) {
                    setNClob(indexes.get(0), x);
                    setNClob(indexes.get(1), x);
                    setNClob(indexes.get(2), x);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setNClob(indexes.get(i), x);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets an NCLOB (National Character Large Object) parameter using a Reader with a specified length.
     * The JDBC driver will read exactly 'length' characters from the Reader.
     * 
     * <p>This method provides more control over the amount of Unicode data read from the Reader.
     * If the parameter name appears multiple times in the query, all occurrences will be set to the same value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String unicodeText = "Unicode content...";
     * query.setNClob("description", new StringReader(unicodeText), unicodeText.length());
     * }</pre>
     *
     * @param parameterName the name of the parameter (without the ':' prefix)
     * @param x the Reader object containing the NCLOB data
     * @param length the number of characters in the stream
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the query
     * @throws SQLException if a database access error occurs, this method is called on a closed PreparedStatement,
     *         or the length is less than zero
     */
    public NamedQuery setNClob(final String parameterName, final Reader x, final long length) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setNClob(i + 1, x, length);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setNClob(indexes.get(0), x, length);
                } else if (indexes.size() == 2) {
                    setNClob(indexes.get(0), x, length);
                    setNClob(indexes.get(1), x, length);
                } else if (indexes.size() == 3) {
                    setNClob(indexes.get(0), x, length);
                    setNClob(indexes.get(1), x, length);
                    setNClob(indexes.get(2), x, length);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setNClob(indexes.get(i), x, length);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets a URL parameter for the specified parameter name.
     * 
     * <p>The URL is stored as a DATALINK value in the database. If the parameter name appears 
     * multiple times in the query, all occurrences will be set to the same value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setURL("website", new URL("https://www.example.com"));
     * }</pre>
     *
     * @param parameterName the name of the parameter (without the ':' prefix)
     * @param x the URL object, or {@code null} to set SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the query
     * @throws SQLException if a database access error occurs or this method is called on a closed PreparedStatement
     */
    public NamedQuery setURL(final String parameterName, final URL x) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setURL(i + 1, x);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setURL(indexes.get(0), x);
                } else if (indexes.size() == 2) {
                    setURL(indexes.get(0), x);
                    setURL(indexes.get(1), x);
                } else if (indexes.size() == 3) {
                    setURL(indexes.get(0), x);
                    setURL(indexes.get(1), x);
                    setURL(indexes.get(2), x);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setURL(indexes.get(i), x);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets an SQL XML parameter for the specified parameter name.
     * 
     * <p>The SQLXML interface is used for mapping SQL XML data type. If the parameter name appears 
     * multiple times in the query, all occurrences will be set to the same value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * SQLXML xmlData = connection.createSQLXML();
     * xmlData.setString("<root><item>value</item></root>");
     * query.setSQLXML("xml_data", xmlData);
     * }</pre>
     *
     * @param parameterName the name of the parameter (without the ':' prefix)
     * @param x the SQLXML object, or {@code null} to set SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the query
     * @throws SQLException if a database access error occurs or this method is called on a closed PreparedStatement
     */
    public NamedQuery setSQLXML(final String parameterName, final java.sql.SQLXML x) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setSQLXML(i + 1, x);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setSQLXML(indexes.get(0), x);
                } else if (indexes.size() == 2) {
                    setSQLXML(indexes.get(0), x);
                    setSQLXML(indexes.get(1), x);
                } else if (indexes.size() == 3) {
                    setSQLXML(indexes.get(0), x);
                    setSQLXML(indexes.get(1), x);
                    setSQLXML(indexes.get(2), x);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setSQLXML(indexes.get(i), x);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets a RowId parameter for the specified parameter name.
     * 
     * <p>RowId is a SQL data type that represents the address of a row in a database table.
     * If the parameter name appears multiple times in the query, all occurrences will be set to the same value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * RowId rowId = resultSet.getRowId("ROWID");
     * query.setRowId("row_identifier", rowId);
     * }</pre>
     *
     * @param parameterName the name of the parameter (without the ':' prefix)
     * @param x the RowId object, or {@code null} to set SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the query
     * @throws SQLException if a database access error occurs or this method is called on a closed PreparedStatement
     */
    public NamedQuery setRowId(final String parameterName, final java.sql.RowId x) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setRowId(i + 1, x);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setRowId(indexes.get(0), x);
                } else if (indexes.size() == 2) {
                    setRowId(indexes.get(0), x);
                    setRowId(indexes.get(1), x);
                } else if (indexes.size() == 3) {
                    setRowId(indexes.get(0), x);
                    setRowId(indexes.get(1), x);
                    setRowId(indexes.get(2), x);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setRowId(indexes.get(i), x);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets a Ref (SQL REF) parameter for the specified parameter name.
     * 
     * <p>A Ref is a reference to an SQL structured type value in the database.
     * If the parameter name appears multiple times in the query, all occurrences will be set to the same value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Ref ref = resultSet.getRef("employee_ref");
     * query.setRef("manager_ref", ref);
     * }</pre>
     *
     * @param parameterName the name of the parameter (without the ':' prefix)
     * @param x the Ref object, or {@code null} to set SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the query
     * @throws SQLException if a database access error occurs or this method is called on a closed PreparedStatement
     */
    public NamedQuery setRef(final String parameterName, final java.sql.Ref x) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setRef(i + 1, x);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setRef(indexes.get(0), x);
                } else if (indexes.size() == 2) {
                    setRef(indexes.get(0), x);
                    setRef(indexes.get(1), x);
                } else if (indexes.size() == 3) {
                    setRef(indexes.get(0), x);
                    setRef(indexes.get(1), x);
                    setRef(indexes.get(2), x);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setRef(indexes.get(i), x);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets an Array parameter for the specified parameter name.
     * 
     * <p>The Array interface is the mapping for the SQL ARRAY type. If the parameter name appears 
     * multiple times in the query, all occurrences will be set to the same value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Integer[] numbers = {1, 2, 3, 4, 5};
     * Array sqlArray = connection.createArrayOf("INTEGER", numbers);
     * query.setArray("number_list", sqlArray);
     * }</pre>
     *
     * @param parameterName the name of the parameter (without the ':' prefix)
     * @param x the Array object, or {@code null} to set SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the query
     * @throws SQLException if a database access error occurs or this method is called on a closed PreparedStatement
     */
    public NamedQuery setArray(final String parameterName, final java.sql.Array x) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setArray(i + 1, x);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setArray(indexes.get(0), x);
                } else if (indexes.size() == 2) {
                    setArray(indexes.get(0), x);
                    setArray(indexes.get(1), x);
                } else if (indexes.size() == 3) {
                    setArray(indexes.get(0), x);
                    setArray(indexes.get(1), x);
                    setArray(indexes.get(2), x);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setArray(indexes.get(i), x);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets an Object parameter for the specified parameter name, using the default SQL type mapping.
     * 
     * <p>This is a generic method that can handle any Java object. The JDBC driver will attempt to 
     * convert the Java object to the appropriate SQL type. Common mappings include:
     * <ul>
     * <li>String → VARCHAR/CHAR</li>
     * <li>Integer/Long → INTEGER/BIGINT</li>
     * <li>BigDecimal → NUMERIC/DECIMAL</li>
     * <li>Date/Timestamp → DATE/TIMESTAMP</li>
     * <li>Boolean → BOOLEAN/BIT</li>
     * <li>byte[] → BINARY/VARBINARY</li>
     * </ul>
     * 
     * <p>If the parameter name appears multiple times in the query, all occurrences will be set to the same value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setObject("user_id", 12345)
     *      .setObject("created_date", new Timestamp(System.currentTimeMillis()))
     *      .setObject("is_active", true);
     * }</pre>
     *
     * @param parameterName the name of the parameter (without the ':' prefix)
     * @param x the object containing the parameter value, or {@code null} to set SQL NULL
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the query
     * @throws SQLException if a database access error occurs, this method is called on a closed PreparedStatement,
     *         or the given object cannot be converted to a SQL type
     */
    public NamedQuery setObject(final String parameterName, final Object x) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setObject(i + 1, x);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setObject(indexes.get(0), x);
                } else if (indexes.size() == 2) {
                    setObject(indexes.get(0), x);
                    setObject(indexes.get(1), x);
                } else if (indexes.size() == 3) {
                    setObject(indexes.get(0), x);
                    setObject(indexes.get(1), x);
                    setObject(indexes.get(2), x);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setObject(indexes.get(i), x);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets an Object parameter with a specified SQL type for the parameter name.
     * 
     * <p>This method allows explicit control over the SQL type used when setting the parameter value.
     * Use this when the default type mapping is not sufficient or when you need to ensure a specific SQL type.
     * If the parameter name appears multiple times in the query, all occurrences will be set to the same value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Set a string as CHAR type instead of VARCHAR
     * query.setObject("code", "ABC", Types.CHAR);
     * // Set a number as DECIMAL with specific type
     * query.setObject("price", new BigDecimal("99.99"), Types.DECIMAL);
     * }</pre>
     *
     * @param parameterName the name of the parameter (without the ':' prefix)
     * @param x the object containing the parameter value, or {@code null} to set SQL NULL
     * @param sqlType the SQL type (from java.sql.Types) to be used
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the query
     * @throws SQLException if a database access error occurs, this method is called on a closed PreparedStatement,
     *         or the object cannot be converted to the specified SQL type
     * @see java.sql.Types
     */
    public NamedQuery setObject(final String parameterName, final Object x, final int sqlType) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setObject(i + 1, x, sqlType);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setObject(indexes.get(0), x, sqlType);
                } else if (indexes.size() == 2) {
                    setObject(indexes.get(0), x, sqlType);
                    setObject(indexes.get(1), x, sqlType);
                } else if (indexes.size() == 3) {
                    setObject(indexes.get(0), x, sqlType);
                    setObject(indexes.get(1), x, sqlType);
                    setObject(indexes.get(2), x, sqlType);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setObject(indexes.get(i), x, sqlType);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets an Object parameter with a specified SQL type and scale/length for the parameter name.
     * 
     * <p>This method provides the most control over parameter setting, allowing specification of both 
     * SQL type and additional type-specific information:
     * <ul>
     * <li>For numeric types (DECIMAL, NUMERIC): scaleOrLength represents the number of digits after the decimal point</li>
     * <li>For character types (CHAR, VARCHAR): scaleOrLength represents the length of the string</li>
     * <li>For binary types (BINARY, VARBINARY): scaleOrLength represents the length in bytes</li>
     * </ul>
     * 
     * <p>If the parameter name appears multiple times in the query, all occurrences will be set to the same value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Set a decimal with 2 decimal places
     * query.setObject("amount", new BigDecimal("123.45"), Types.DECIMAL, 2);
     * // Set a fixed-length character string
     * query.setObject("country_code", "US", Types.CHAR, 2);
     * }</pre>
     *
     * @param parameterName the name of the parameter (without the ':' prefix)
     * @param x the object containing the parameter value
     * @param sqlType the SQL type (from java.sql.Types) to be used
     * @param scaleOrLength for numeric types, the number of digits after the decimal point;
     *        for character/binary types, the length
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the query
     * @throws SQLException if a database access error occurs, this method is called on a closed PreparedStatement,
     *         or the object cannot be converted to the specified SQL type
     * @see java.sql.Types
     */
    public NamedQuery setObject(final String parameterName, final Object x, final int sqlType, final int scaleOrLength)
            throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setObject(i + 1, x, sqlType, scaleOrLength);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setObject(indexes.get(0), x, sqlType, scaleOrLength);
                } else if (indexes.size() == 2) {
                    setObject(indexes.get(0), x, sqlType, scaleOrLength);
                    setObject(indexes.get(1), x, sqlType, scaleOrLength);
                } else if (indexes.size() == 3) {
                    setObject(indexes.get(0), x, sqlType, scaleOrLength);
                    setObject(indexes.get(1), x, sqlType, scaleOrLength);
                    setObject(indexes.get(2), x, sqlType, scaleOrLength);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setObject(indexes.get(i), x, sqlType, scaleOrLength);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets an Object parameter with a specified SQLType for the parameter name.
     * 
     * <p>This method uses the JDBC 4.2 SQLType interface for type specification, providing better 
     * type safety and vendor-specific type support compared to using int constants from java.sql.Types.
     * If the parameter name appears multiple times in the query, all occurrences will be set to the same value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * 
     * query.setObject("user_id", 12345, JDBCType.INTEGER)
     *      .setObject("email", "user@example.com", JDBCType.VARCHAR)
     *      .setObject("balance", new BigDecimal("1000.00"), JDBCType.DECIMAL);
     * }</pre>
     *
     * @param parameterName the name of the parameter (without the ':' prefix)
     * @param x the object containing the parameter value, or {@code null} to set SQL NULL
     * @param sqlType the SQLType to be used
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the query
     * @throws SQLException if a database access error occurs, this method is called on a closed PreparedStatement,
     *         or the object cannot be converted to the specified SQL type
     */
    public NamedQuery setObject(final String parameterName, final Object x, final SQLType sqlType) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setObject(i + 1, x, sqlType);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setObject(indexes.get(0), x, sqlType);
                } else if (indexes.size() == 2) {
                    setObject(indexes.get(0), x, sqlType);
                    setObject(indexes.get(1), x, sqlType);
                } else if (indexes.size() == 3) {
                    setObject(indexes.get(0), x, sqlType);
                    setObject(indexes.get(1), x, sqlType);
                    setObject(indexes.get(2), x, sqlType);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setObject(indexes.get(i), x, sqlType);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets an Object parameter with a specified SQLType and scale/length for the parameter name.
     * 
     * <p>This method provides the most control when using JDBC 4.2 SQLType, allowing specification 
     * of both SQL type and additional type-specific information. The meaning of scaleOrLength depends 
     * on the SQL type being used.
     * 
     * <p>If the parameter name appears multiple times in the query, all occurrences will be set to the same value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * 
     * // Set a decimal with specific scale
     * query.setObject("price", new BigDecimal("99.999"), JDBCType.DECIMAL, 2);
     * // Set a varchar with maximum length
     * query.setObject("description", "Product description", JDBCType.VARCHAR, 255);
     * }</pre>
     *
     * @param parameterName the name of the parameter (without the ':' prefix)
     * @param x the object containing the parameter value
     * @param sqlType the SQLType to be used
     * @param scaleOrLength for numeric types, the number of digits after the decimal point;
     *        for character/binary types, the length
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the query
     * @throws SQLException if a database access error occurs, this method is called on a closed PreparedStatement,
     *         or the object cannot be converted to the specified SQL type
     */
    public NamedQuery setObject(final String parameterName, final Object x, final SQLType sqlType, final int scaleOrLength)
            throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setObject(i + 1, x, sqlType, scaleOrLength);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setObject(indexes.get(0), x, sqlType, scaleOrLength);
                } else if (indexes.size() == 2) {
                    setObject(indexes.get(0), x, sqlType, scaleOrLength);
                    setObject(indexes.get(1), x, sqlType, scaleOrLength);
                } else if (indexes.size() == 3) {
                    setObject(indexes.get(0), x, sqlType, scaleOrLength);
                    setObject(indexes.get(1), x, sqlType, scaleOrLength);
                    setObject(indexes.get(2), x, sqlType, scaleOrLength);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setObject(indexes.get(i), x, sqlType, scaleOrLength);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets an Object parameter using a custom Type handler for the parameter name.
     * 
     * <p>This method allows the use of custom type handlers from the Abacus framework, providing 
     * complete control over how Java objects are converted to SQL values. This is particularly 
     * useful for complex type mappings or when working with custom data types.
     * 
     * <p>If the parameter name appears multiple times in the query, all occurrences will be set to the same value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Using a custom JSON type handler
     * Type<UserPreferences> jsonType = TypeFactory.getType(UserPreferences.class);
     * query.setObject("preferences", userPrefs, jsonType);
     * 
     * // Using an enum type handler
     * Type<Status> enumType = TypeFactory.getType(Status.class);
     * query.setObject("status", Status.ACTIVE, enumType);
     * }</pre>
     *
     * @param <T> parameter value type
     * @param parameterName the name of the parameter (without the ':' prefix)
     * @param x the object containing the parameter value
     * @param type the Type handler to use for setting the parameter
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the query
     * @throws SQLException if a database access error occurs or this method is called on a closed PreparedStatement
     */
    public <T> NamedQuery setObject(final String parameterName, final T x, final Type<T> type) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    type.set(stmt, i + 1, x);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Not found named parameter: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    type.set(stmt, indexes.get(0), x);
                } else if (indexes.size() == 2) {
                    type.set(stmt, indexes.get(0), x);
                    type.set(stmt, indexes.get(1), x);
                } else if (indexes.size() == 3) {
                    type.set(stmt, indexes.get(0), x);
                    type.set(stmt, indexes.get(1), x);
                    type.set(stmt, indexes.get(2), x);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        type.set(stmt, indexes.get(i), x);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets multiple parameters from a Map containing parameter names and their values.
     * 
     * <p>This method provides a convenient way to set multiple parameters at once. Only parameters 
     * that exist in both the map and the query will be set. Parameters in the query that are not 
     * present in the map will remain unset.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Map<String, Object> params = new HashMap<>();
     * params.put("name", "John Doe");
     * params.put("age", 30);
     * params.put("email", "john@example.com");
     * 
     * query.setParameters(params);
     * }</pre>
     *
     * @param parameters a map containing parameter names (without ':' prefix) as keys and their values
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameters map is null
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setParameters(final Map<String, ?> parameters) throws IllegalArgumentException, SQLException {
        checkArgNotNull(parameters, cs.parameters);

        for (final String paramName : parameterNames) {
            if (parameters.containsKey(paramName)) {
                setObject(paramName, parameters.get(paramName));
            }
        }

        return this;
    }

    /**
     * Sets parameters for the named query using the provided EntityId.
     * This method is used internally and is not typically called directly by users.
     *
     * @param entityId the EntityId containing parameter values
     * @throws SQLException if a database access error occurs
     */
    void setParameters(final EntityId entityId) throws SQLException {
        for (final String paramName : parameterNames) {
            if (entityId.containsKey(paramName)) {
                setObject(paramName, entityId.get(paramName));
            }
        }
    }

    /**
     * Sets parameters from various types of objects including beans, maps, collections, arrays, or single values.
     * 
     * <p>This flexible method accepts different parameter sources:
     * <ul>
     * <li><b>Bean/Entity objects</b>: Properties matching parameter names will be used</li>
     * <li><b>Map</b>: Entries with keys matching parameter names will be used</li>
     * <li><b>Collection/Array</b>: Elements will be assigned to parameters in positional order</li>
     * <li><b>EntityId</b>: Values with keys matching parameter names will be used</li>
     * <li><b>Single value</b>: Used only if the query has exactly one parameter</li>
     * </ul>
     * 
     * <p><b>Usage Examples:</b>
     * <pre>{@code
     * // Using a bean
     * User user = new User("John", 30, "john@example.com");
     * query.setParameters(user);
     * 
     * // Using a Map
     * Map<String, Object> params = Map.of("name", "John", "age", 30);
     * query.setParameters(params);
     * 
     * // Using an array (parameters set by position)
     * query.setParameters(new Object[] {"John", 30, "john@example.com"});
     * 
     * // Using a single value (for queries with one parameter)
     * query.setParameters("John");
     * }</pre>
     *
     * @param parameters an object containing the parameters (bean, map, collection, array, or single value)
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if parameters is {@code null} or of an unsupported type
     * @throws SQLException if a database access error occurs
     * @see JdbcUtil#getNamedParameters(String)
     */
    @SuppressWarnings("rawtypes")
    public NamedQuery setParameters(final Object parameters) throws IllegalArgumentException, SQLException {
        checkArgNotNull(parameters, cs.parameters);

        final Class<?> cls = parameters.getClass();

        if (Beans.isBeanClass(cls)) {
            final BeanInfo entityInfo = ParserUtil.getBeanInfo(cls);
            PropInfo propInfo = null;

            for (int i = 0; i < parameterCount; i++) {
                propInfo = entityInfo.getPropInfo(parameterNames.get(i));

                if (propInfo != null) {
                    propInfo.dbType.set(stmt, i + 1, propInfo.getPropValue(parameters));
                }
            }
        } else if (parameters instanceof Map) {
            return setParameters((Map<String, ?>) parameters);
        } else if (parameters instanceof Collection) {
            return setParameters((Collection) parameters);
        } else if (parameters instanceof Object[]) {
            return setParameters((Object[]) parameters);
        } else if (parameters instanceof EntityId) {
            setParameters((EntityId) parameters);
        } else if (parameterCount == 1) {
            setObject(1, parameters);
        } else {
            close();
            throw new IllegalArgumentException("Unsupported named parameter type: " + parameters.getClass() + " for named sql: " + namedSql.sql());
        }

        return this;
    }

    /**
     * Sets specific parameters from an entity object using only the specified parameter names.
     * 
     * <p>This method provides fine-grained control over which properties from an entity are used 
     * to set query parameters. Only properties with names matching those in the parameterNames 
     * collection will be used.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User();
     * user.setId(1);
     * user.setName("John");
     * user.setEmail("john@example.com");
     * user.setAge(30);
     * 
     * // Only set name and email parameters, ignore id and age
     * query.setParameters(user, Arrays.asList("name", "email"));
     * }</pre>
     *
     * @param entity the entity object containing the parameter values
     * @param parameterNames a collection of parameter names to be set from the entity
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if entity or parameterNames is {@code null}, if entity is not a bean class,
     *         if a property is not found in the entity, or if a parameter name is not found in the query
     * @throws SQLException if a database access error occurs
     * @see Beans#getPropNameList(Class)
     * @see Beans#getPropNames(Class, Collection)
     * @see JdbcUtil#getNamedParameters(String)
     */
    public NamedQuery setParameters(final Object entity, final Collection<String> parameterNames) throws IllegalArgumentException, SQLException {
        checkArgNotNull(entity, cs.entity);
        checkArgNotNull(parameterNames, cs.parameterNames);

        if (paramNameIndexMap == null) {
            initParamNameIndexMap();
        }

        final Class<?> cls = entity.getClass();
        if (Beans.isBeanClass(cls)) {
            final BeanInfo entityInfo = ParserUtil.getBeanInfo(cls);
            PropInfo propInfo = null;
            Object propValue = null;
            Type<Object> dbType = null;
            IntList indexes = null;

            for (final String parameterName : parameterNames) {
                propInfo = entityInfo.getPropInfo(parameterName);

                if (propInfo == null) {
                    close();
                    throw new IllegalArgumentException("No property found with name: " + parameterName + " in class: " + ClassUtil.getCanonicalClassName(cls));
                }

                propValue = propInfo.getPropValue(entity);
                dbType = propInfo.dbType;

                indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        dbType.set(stmt, indexes.get(0), propValue);
                    } else if (indexes.size() == 2) {
                        dbType.set(stmt, indexes.get(0), propValue);
                        dbType.set(stmt, indexes.get(1), propValue);
                    } else if (indexes.size() == 3) {
                        dbType.set(stmt, indexes.get(0), propValue);
                        dbType.set(stmt, indexes.get(1), propValue);
                        dbType.set(stmt, indexes.get(2), propValue);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            dbType.set(stmt, indexes.get(i), propValue);
                        }
                    }
                }
            }
        } else {
            close();
            throw new IllegalArgumentException(
                    "Unsupported parameter type: " + ClassUtil.getCanonicalClassName(cls) + ". Only Entity/Record types are supported here");
        }

        return this;
    }

    /**
     * Sets parameters using a custom parameter setter function.
     * 
     * <p>This method provides maximum flexibility by allowing you to define custom logic for 
     * setting parameters. The setter function receives the parsed SQL, this NamedQuery instance, 
     * and your parameters object.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * UserSearchCriteria criteria = new UserSearchCriteria();
     * criteria.setMinAge(25);
     * criteria.setMaxAge(35);
     * criteria.setActive(true);
     * 
     * query.setParameters(criteria, (sql, q, c) -> {
     *     q.setInt("min_age", c.getMinAge());
     *     q.setInt("max_age", c.getMaxAge());
     *     if (c.isActive() != null) {
     *         q.setBoolean("is_active", c.isActive());
     *     }
     * });
     * }</pre>
     *
     * @param <T> parameters object type
     * @param parameters the parameters object to pass to the setter
     * @param paramsSetter a function that sets parameters on the query
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if paramsSetter is null
     * @throws SQLException if a database access error occurs 
     */
    public <T> NamedQuery setParameters(final T parameters, final Jdbc.TriParametersSetter<? super NamedQuery, ? super T> paramsSetter)
            throws IllegalArgumentException, SQLException {
        checkArgNotNull(paramsSetter, cs.paramsSetter);

        boolean noException = false;

        try {
            paramsSetter.accept(namedSql, this, parameters);

            noException = true;
        } finally {
            if (!noException) {
                close();
            }
        }

        return this;
    }

    /**
     * Adds a collection of parameter sets for batch execution.
     *
     * <p>This method adds multiple sets of parameters to be executed as a batch operation.
     * Each element in the collection should be a parameter object compatible with 
     * {@link #setParameters(Object)}, such as:
     * <ul>
     * <li>Bean objects with properties matching parameter names</li>
     * <li>Maps with keys matching parameter names</li>
     * <li>Arrays or Collections for positional parameters</li>
     * </ul>
     *
     * <p>After adding batch parameters, call {@code executeBatch()} to execute the batch.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = Arrays.asList(
     *     new User("John", 30, "john@example.com"),
     *     new User("Jane", 28, "jane@example.com"),
     *     new User("Bob", 35, "bob@example.com")
     * );
     *
     * String sql = "INSERT INTO users (name, age, email) VALUES (:name, :age, :email)";
     * try (NamedQuery query = JdbcUtil.prepareNamedQuery(connection, sql)) {
     *     query.addBatchParameters(users)
     *          .executeBatch();
     * }
     *
     * // Using maps
     * List<Map<String, Object>> paramMaps = new ArrayList<>();
     * paramMaps.add(Map.of("id", 1, "status", "ACTIVE"));
     * paramMaps.add(Map.of("id", 2, "status", "INACTIVE"));
     * 
     * query.addBatchParameters(paramMaps)
     *      .executeBatch();
     * }</pre>
     *
     * @param batchParameters a collection of parameter objects for batch processing
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if batchParameters is {@code null} or contains invalid parameter objects
     * @throws SQLException if a database access error occurs or this method is called on a closed PreparedStatement
     * @see #setParameters(Object)
     * @see #addBatch()
     */
    @Override
    public NamedQuery addBatchParameters(final Collection<?> batchParameters) throws IllegalArgumentException, SQLException {
        checkArgNotNull(batchParameters, cs.batchParameters);

        if (N.isEmpty(batchParameters)) {
            return this;
        }

        return addBatchParameters(batchParameters.iterator());
    }

    /**
     * Adds a batch of parameters from an iterator for batch execution.
     *
     * <p>This method allows streaming of parameter sets for batch operations without loading
     * all data into memory at once. Each element provided by the iterator should be a parameter
     * object compatible with {@link #setParameters(Object)}, such as:
     * <ul>
     * <li>Bean objects with properties matching parameter names</li>
     * <li>Maps with keys matching parameter names</li>
     * <li>Arrays or Collections for positional parameters</li>
     * </ul>
     *
     * <p>After adding batch parameters, call {@code executeBatch()} to execute the batch.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Using an iterator from a database result
     * Iterator<User> userIterator = getUsersFromSource();
     * query.addBatchParameters(userIterator)
     *      .executeBatch();
     *
     * // Using a stream for large data sets
     * query.addBatchParameters(
     *     userRepository.findAllActive()
     *                  .stream()
     *                  .filter(u -> u.getLastLogin().isAfter(oneMonthAgo))
     *                  .iterator()
     * ).executeBatch();
     *
     * // Processing data in chunks to avoid memory issues
     * try (ResultSet rs = JdbcUtil.executeQuery(largeDataQuery)) {
     *     DataIterator dataIterator = new DataIterator(rs);
     *     insertQuery.addBatchParameters(dataIterator)
     *               .executeBatch();
     * }
     * }</pre>
     *
     * @param batchParameters an iterator providing parameter objects for batch processing
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if batchParameters is {@code null} or contains invalid parameter objects
     * @throws SQLException if a database access error occurs or this method is called on a closed PreparedStatement
     * @see #setParameters(Object)
     * @see #addBatchParameters(Collection)
     * @see #addBatch()
     */
    @Beta
    @Override
    @SuppressWarnings("rawtypes")
    public NamedQuery addBatchParameters(final Iterator<?> batchParameters) throws IllegalArgumentException, SQLException {
        checkArgNotNull(batchParameters, cs.batchParameters);

        @SuppressWarnings("UnnecessaryLocalVariable")
        final Iterator<?> iter = batchParameters;
        boolean noException = false;

        try {
            if (!iter.hasNext()) {
                return this;
            }

            final Object first = iter.next();

            if (first == null) {
                if (parameterCount != 1) {
                    throw new IllegalArgumentException("Unsupported named parameter type: null for named sql: " + namedSql.sql());
                }

                stmt.setObject(1, first);
                addBatch();

                while (iter.hasNext()) {
                    stmt.setObject(1, iter.next());
                    addBatch();
                }
            } else {
                final Class<?> cls = first.getClass();

                if (Beans.isBeanClass(cls)) {
                    final BeanInfo entityInfo = ParserUtil.getBeanInfo(cls);
                    final PropInfo[] propInfos = new PropInfo[parameterCount];

                    for (int i = 0; i < parameterCount; i++) {
                        propInfos[i] = entityInfo.getPropInfo(parameterNames.get(i));
                    }

                    PropInfo propInfo = null;

                    for (int i = 0; i < parameterCount; i++) {
                        propInfo = propInfos[i];

                        if (propInfo != null) {
                            propInfo.dbType.set(stmt, i + 1, propInfo.getPropValue(first));
                        }
                    }

                    addBatch();

                    Object params = null;
                    while (iter.hasNext()) {
                        params = iter.next();

                        for (int i = 0; i < parameterCount; i++) {
                            propInfo = propInfos[i];

                            if (propInfo != null) {
                                propInfo.dbType.set(stmt, i + 1, propInfo.getPropValue(params));
                            }
                        }

                        addBatch();
                    }
                } else if (Map.class.isAssignableFrom(cls)) {
                    setParameters((Map<String, ?>) first);
                    addBatch();

                    Map<String, ?> params = null;

                    while (iter.hasNext()) {
                        params = (Map<String, ?>) iter.next();
                        setParameters(params);
                        addBatch();
                    }
                } else if (first instanceof Collection) {
                    setParameters((Collection) first);
                    addBatch();

                    Collection params = null;
                    while (iter.hasNext()) {
                        params = (Collection) iter.next();
                        setParameters(params);
                        addBatch();
                    }
                } else if (first instanceof Object[]) {
                    setParameters((Object[]) first);
                    addBatch();

                    Object[] params = null;
                    while (iter.hasNext()) {
                        params = (Object[]) iter.next();
                        setParameters(params);
                        addBatch();
                    }
                } else if (first instanceof EntityId) {
                    setParameters((EntityId) first);
                    addBatch();

                    EntityId params = null;
                    while (iter.hasNext()) {
                        params = (EntityId) iter.next();
                        setParameters(params);
                        addBatch();
                    }
                } else if (parameterCount == 1) {
                    stmt.setObject(1, first);
                    addBatch();

                    while (iter.hasNext()) {
                        stmt.setObject(1, iter.next());
                        addBatch();
                    }
                } else {
                    throw new IllegalArgumentException("Unsupported named parameter type: " + cls + " for named sql: " + namedSql.sql());
                }
            }

            noException = true;
        } finally {
            if (!noException) {
                close();
            }
        }

        return this;
    }

    //        /**
    //         *
    //         * @param batchParameters
    //         * @return
    //         * @throws SQLException the SQL exception
    //         */
    //        @Override
    //        public NamedQuery addSingleBatchParameters(final Collection<?> batchParameters) throws SQLException {
    //            checkArgNotNull(batchParameters, "batchParameters");
    //
    //            if (parameterCount != 1) {
    //                try {
    //                    close();
    //                } catch (Exception e) {
    //                    JdbcUtil.logger.error("Failed to close PreparedQuery", e);
    //                }
    //
    //                throw new IllegalArgumentException("isSingleParameter is true but the count of parameters in query is: " + parameterCount);
    //            }
    //
    //            if (N.isEmpty(batchParameters)) {
    //                return this;
    //            }
    //
    //            boolean noException = false;
    //
    //            try {
    //                for (Object obj : batchParameters) {
    //                    setObject(1, obj);
    //
    //                    addBatch();
    //                }
    //
    //                isBatch = batchParameters.size() > 0;
    //
    //                noException = true;
    //            } finally {
    //                if (noException == false) {
    //                    close();
    //                }
    //            }
    //
    //            return this;
    //        }
    //
    //        /**
    //         *
    //         * @param batchParameters
    //         * @return
    //         * @throws SQLException the SQL exception
    //         */
    //        @Override
    //        public NamedQuery addSingleBatchParameters(final Iterator<?> batchParameters) throws SQLException {
    //            checkArgNotNull(batchParameters, "batchParameters");
    //
    //            return addSingleBatchParameters(Iterators.toList(batchParameters));
    //        }
}
