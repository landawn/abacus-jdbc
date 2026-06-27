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
 * A JDBC wrapper class that provides named parameter support for SQL queries, similar to Spring's
 * {@code NamedParameterJdbcTemplate}. This class wraps a {@link PreparedStatement} and lets you bind
 * values using named parameters (e.g. {@code :name}, {@code :age}) instead of positional placeholders
 * ({@code ?}). The same named parameter may appear multiple times in the SQL; every occurrence is
 * bound to the same value.
 *
 * <p>If a name passed to a by-name {@code setXxx(String, ...)} setter (or to
 * {@link #setObject(String, Object)} and its overloads) does not match any named parameter declared
 * in the SQL, the backing {@code PreparedStatement} is closed and an {@link IllegalArgumentException}
 * is thrown. This differs from {@link #setParameters(Map)}, which silently ignores map entries whose
 * keys are not named parameters.
 *
 * <p>The backing {@code PreparedStatement} is closed by default
 * after any execution methods (such as {@code query}, {@code queryForInt}, {@code queryForLong},
 * {@code findFirst}, {@code findOnlyOne}, {@code list}, {@code execute}, and similar),
 * unless the {@code closeAfterExecution} flag is set to {@code false} by calling {@link #closeAfterExecution(boolean)}.
 *
 * <p>In general, do not cache or reuse the instance of this class,
 * unless the {@code closeAfterExecution} flag is set to {@code false} by calling {@link #closeAfterExecution(boolean)}.
 *
 * <p>The {@code ResultSet} returned by query will always be closed after execution, even if {@code closeAfterExecution} flag is set to {@code false}.
 *
 * <p>Remember: when using positional methods inherited from {@link AbstractQuery}, parameter/column
 * indexes in {@link PreparedStatement}/{@link java.sql.ResultSet} start from 1, not 0.
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * String sql = "SELECT * FROM users WHERE name = :name AND age > :age";
 * try (NamedQuery query = JdbcUtil.prepareNamedQuery(connection, sql)) {
 *     List<User> users = query.setString("name", "John")
 *                             .setInt("age", 25)
 *                             .query(Jdbc.ResultExtractor.toList(User.class));
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
        parameterNames = namedSql.namedParameters();
        parameterCount = namedSql.parameterCount();

        if (N.size(parameterNames) != parameterCount) {
            throw new IllegalArgumentException("Invalid named SQL: " + namedSql.originalSql());
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
     * Sets the specified named parameter to SQL {@code NULL}.
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
    public NamedQuery setNull(final String parameterName, final int sqlType) throws IllegalArgumentException, SQLException {
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
                throw new IllegalArgumentException("Named parameter not found: " + parameterName); //NOSONAR
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
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
     * Sets the specified named parameter to SQL {@code NULL} with a specified SQL type and type name.
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
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
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
     * @param value the boolean value to set
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setBoolean(final String parameterName, final boolean value) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setBoolean(i + 1, value);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setBoolean(indexes.get(0), value);
                } else if (indexes.size() == 2) {
                    setBoolean(indexes.get(0), value);
                    setBoolean(indexes.get(1), value);
                } else if (indexes.size() == 3) {
                    setBoolean(indexes.get(0), value);
                    setBoolean(indexes.get(1), value);
                    setBoolean(indexes.get(2), value);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setBoolean(indexes.get(i), value);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a Boolean value.
     * If the value is {@code null}, the parameter will be set to SQL {@code NULL}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Boolean isVerified = getUserVerificationStatus();   // might return null
     * query.setBoolean("isVerified", isVerified);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param value the Boolean value to set, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setBoolean(final String parameterName, final Boolean value) throws IllegalArgumentException, SQLException {
        if (value == null) {
            setNull(parameterName, java.sql.Types.BOOLEAN);
        } else {
            setBoolean(parameterName, value.booleanValue());
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
     * @param value the byte value to set
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setByte(final String parameterName, final byte value) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setByte(i + 1, value);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setByte(indexes.get(0), value);
                } else if (indexes.size() == 2) {
                    setByte(indexes.get(0), value);
                    setByte(indexes.get(1), value);
                } else if (indexes.size() == 3) {
                    setByte(indexes.get(0), value);
                    setByte(indexes.get(1), value);
                    setByte(indexes.get(2), value);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setByte(indexes.get(i), value);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a Byte value.
     * If the value is {@code null}, the parameter will be set to SQL {@code NULL}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Byte priorityLevel = getPriorityLevel();   // might return null
     * query.setByte("priority", priorityLevel);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param value the Byte value to set, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setByte(final String parameterName, final Byte value) throws IllegalArgumentException, SQLException {
        if (value == null) {
            setNull(parameterName, java.sql.Types.TINYINT);
        } else {
            setByte(parameterName, value.byteValue());
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
     * @param value the short value to set
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setShort(final String parameterName, final short value) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setShort(i + 1, value);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setShort(indexes.get(0), value);
                } else if (indexes.size() == 2) {
                    setShort(indexes.get(0), value);
                    setShort(indexes.get(1), value);
                } else if (indexes.size() == 3) {
                    setShort(indexes.get(0), value);
                    setShort(indexes.get(1), value);
                    setShort(indexes.get(2), value);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setShort(indexes.get(i), value);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a Short value.
     * If the value is {@code null}, the parameter will be set to SQL {@code NULL}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Short categoryId = getCategoryId();   // might return null
     * query.setShort("categoryId", categoryId);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param value the Short value to set, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setShort(final String parameterName, final Short value) throws IllegalArgumentException, SQLException {
        if (value == null) {
            setNull(parameterName, java.sql.Types.SMALLINT);
        } else {
            setShort(parameterName, value.shortValue());
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
     * @param value the int value to set
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setInt(final String parameterName, final int value) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setInt(i + 1, value);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setInt(indexes.get(0), value);
                } else if (indexes.size() == 2) {
                    setInt(indexes.get(0), value);
                    setInt(indexes.get(1), value);
                } else if (indexes.size() == 3) {
                    setInt(indexes.get(0), value);
                    setInt(indexes.get(1), value);
                    setInt(indexes.get(2), value);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setInt(indexes.get(i), value);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets the specified named parameter to an Integer value.
     * If the value is {@code null}, the parameter will be set to SQL {@code NULL}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Integer age = getAge();   // might return null
     * query.setInt("age", age);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param value the Integer value to set, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setInt(final String parameterName, final Integer value) throws IllegalArgumentException, SQLException {
        if (value == null) {
            setNull(parameterName, java.sql.Types.INTEGER);
        } else {
            setInt(parameterName, value.intValue());
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
     * query.setInt("grade", 'A');   // stores 65 (Unicode code point value of 'A')
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param value the char value to set
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     * @see #setString(String, char)
     * @deprecated Generally {@code char} should be saved as {@code String} in DB. Use {@link #setString(String, char)} instead.
     */
    @Deprecated
    public NamedQuery setInt(final String parameterName, final char value) throws IllegalArgumentException, SQLException {
        return setInt(parameterName, (int) value);
    }

    /**
     * Sets the specified named parameter to a Character value by converting it to an int.
     *
     * <p>This method handles {@code null} values by setting the parameter to SQL {@code NULL} if the provided value is {@code null}.
     * Otherwise, it converts the char to its numeric value and stores it as an integer.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Character grade = getGrade();   // might return null
     * query.setInt("grade", grade);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param value the Character value to set, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     * @see #setString(String, Character)
     * @deprecated Generally {@code char} should be saved as {@code String} in DB. Use {@link #setString(String, Character)} instead.
     */
    @Deprecated
    public NamedQuery setInt(final String parameterName, final Character value) throws IllegalArgumentException, SQLException {
        if (value == null) {
            setNull(parameterName, java.sql.Types.INTEGER);
        } else {
            setInt(parameterName, value.charValue());
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
     * @param value the long value to set
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setLong(final String parameterName, final long value) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setLong(i + 1, value);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setLong(indexes.get(0), value);
                } else if (indexes.size() == 2) {
                    setLong(indexes.get(0), value);
                    setLong(indexes.get(1), value);
                } else if (indexes.size() == 3) {
                    setLong(indexes.get(0), value);
                    setLong(indexes.get(1), value);
                    setLong(indexes.get(2), value);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setLong(indexes.get(i), value);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a Long value.
     * If the value is {@code null}, the parameter will be set to SQL {@code NULL}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Long accountId = getAccountId();   // might return null
     * query.setLong("accountId", accountId);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param value the Long value to set, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setLong(final String parameterName, final Long value) throws IllegalArgumentException, SQLException {
        if (value == null) {
            setNull(parameterName, java.sql.Types.BIGINT);
        } else {
            setLong(parameterName, value.longValue());
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a {@code long} converted from the given {@link BigInteger}.
     *
     * <p>This method converts the {@code BigInteger} to a {@code long} using
     * {@link BigInteger#longValueExact()}. If the value lies outside the range of {@code long}
     * (less than {@link Long#MIN_VALUE} or greater than {@link Long#MAX_VALUE}), an
     * {@link ArithmeticException} is thrown. Consider using
     * {@link #setBigDecimal(String, BigInteger)} or {@link #setBigIntegerAsString(String, BigInteger)}
     * for values that may exceed the {@code long} range.
     *
     * <p>If {@code value} is {@code null}, the parameter is set to SQL {@code NULL} with type
     * {@link Types#BIGINT}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * BigInteger accountBalance = new BigInteger("1000000");
     * query.setLong("balance", accountBalance);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param value the BigInteger value to set, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws ArithmeticException if the BigInteger value will not fit in a {@code long}
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setLong(final String parameterName, final BigInteger value) throws IllegalArgumentException, SQLException {
        if (value == null) {
            setNull(parameterName, java.sql.Types.BIGINT);
        } else {
            boolean noException = false;
            try {
                setLong(parameterName, value.longValueExact());
                noException = true;
            } finally {
                if (!noException) {
                    close();
                }
            }
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
     * @param value the float value to set
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setFloat(final String parameterName, final float value) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setFloat(i + 1, value);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setFloat(indexes.get(0), value);
                } else if (indexes.size() == 2) {
                    setFloat(indexes.get(0), value);
                    setFloat(indexes.get(1), value);
                } else if (indexes.size() == 3) {
                    setFloat(indexes.get(0), value);
                    setFloat(indexes.get(1), value);
                    setFloat(indexes.get(2), value);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setFloat(indexes.get(i), value);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a Float value.
     * If the value is {@code null}, the parameter will be set to SQL {@code NULL}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Float discount = getDiscount();   // might return null
     * query.setFloat("discount", discount);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param value the Float value to set, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setFloat(final String parameterName, final Float value) throws IllegalArgumentException, SQLException {
        if (value == null) {
            // Per JDBC spec Appendix B.4, Java float maps to SQL REAL (not Types.FLOAT, which is
            // an alias for Types.DOUBLE). Matches AbstractQuery.setFloat(int, Float) fix.
            setNull(parameterName, java.sql.Types.REAL);
        } else {
            setFloat(parameterName, value.floatValue());
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
     * @param value the double value to set
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setDouble(final String parameterName, final double value) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setDouble(i + 1, value);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setDouble(indexes.get(0), value);
                } else if (indexes.size() == 2) {
                    setDouble(indexes.get(0), value);
                    setDouble(indexes.get(1), value);
                } else if (indexes.size() == 3) {
                    setDouble(indexes.get(0), value);
                    setDouble(indexes.get(1), value);
                    setDouble(indexes.get(2), value);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setDouble(indexes.get(i), value);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a Double value.
     * If the value is {@code null}, the parameter will be set to SQL {@code NULL}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Double balance = getAccountBalance();   // might return null
     * query.setDouble("balance", balance);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param value the Double value to set, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setDouble(final String parameterName, final Double value) throws IllegalArgumentException, SQLException {
        if (value == null) {
            setNull(parameterName, java.sql.Types.DOUBLE);
        } else {
            setDouble(parameterName, value.doubleValue());
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
     * @param value the BigDecimal value to set, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setBigDecimal(final String parameterName, final BigDecimal value) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setBigDecimal(i + 1, value);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setBigDecimal(indexes.get(0), value);
                } else if (indexes.size() == 2) {
                    setBigDecimal(indexes.get(0), value);
                    setBigDecimal(indexes.get(1), value);
                } else if (indexes.size() == 3) {
                    setBigDecimal(indexes.get(0), value);
                    setBigDecimal(indexes.get(1), value);
                    setBigDecimal(indexes.get(2), value);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setBigDecimal(indexes.get(i), value);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets the specified named parameter to the value of the given {@link BigInteger}, stored as a {@link BigDecimal}.
     *
     * <p>This method wraps the BigInteger into a BigDecimal (with scale 0) and binds it as a SQL {@code DECIMAL}.
     * If {@code value} is {@code null}, the parameter is set to SQL {@code NULL} with type {@link Types#DECIMAL}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * BigInteger bigInt = new BigInteger("123456789012345678901234567890");
     * query.setBigDecimal("largeValue", bigInt);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param value the BigInteger value to set, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setBigDecimal(final String parameterName, final BigInteger value) throws IllegalArgumentException, SQLException {
        if (value == null) {
            return setNull(parameterName, Types.DECIMAL);
        } else {
            return setBigDecimal(parameterName, new BigDecimal(value));
        }
    }

    /**
     * Sets the specified named parameter to a BigInteger value, stored as its base-10 String representation.
     *
     * <p>This method delegates to {@link #setString(String, BigInteger)}, storing very large integer
     * values as strings in the database. This is useful when the numeric value exceeds the range of
     * standard numeric types. If {@code value} is {@code null}, the parameter is set to SQL {@code NULL}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * BigInteger veryLargeNumber = new BigInteger("99999999999999999999999999999999");
     * query.setBigIntegerAsString("bigNumber", veryLargeNumber);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param value the BigInteger value to set, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     * @see #setString(String, BigInteger)
     * @see #setBigDecimal(String, BigInteger)
     * @see #setLong(String, BigInteger)
     */
    @Beta
    public NamedQuery setBigIntegerAsString(final String parameterName, final BigInteger value) throws IllegalArgumentException, SQLException {
        return setString(parameterName, value);
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
     * @param value the String value to set, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setString(final String parameterName, final String value) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setString(i + 1, value);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setString(indexes.get(0), value);
                } else if (indexes.size() == 2) {
                    setString(indexes.get(0), value);
                    setString(indexes.get(1), value);
                } else if (indexes.size() == 3) {
                    setString(indexes.get(0), value);
                    setString(indexes.get(1), value);
                    setString(indexes.get(2), value);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setString(indexes.get(i), value);
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
     * @param value the CharSequence value to set, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setString(final String parameterName, final CharSequence value) throws IllegalArgumentException, SQLException {
        return setString(parameterName, value == null ? (String) null : value.toString()); //NOSONAR
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
     * @param value the char value to set
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setString(final String parameterName, final char value) throws IllegalArgumentException, SQLException {
        return setString(parameterName, String.valueOf(value));
    }

    /**
     * Sets the specified named parameter to a Character value.
     *
     * <p>This method converts the Character to a String and sets the parameter.
     * Null values are handled appropriately.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Character initial = getMiddleInitial();   // might return null
     * query.setString("middleInitial", initial);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param value the Character value to set, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setString(final String parameterName, final Character value) throws IllegalArgumentException, SQLException {
        return setString(parameterName, value == null ? (String) null : value.toString()); //NOSONAR
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
     * @param value the BigInteger value to set, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setString(final String parameterName, final BigInteger value) throws IllegalArgumentException, SQLException {
        if (value == null) {
            return setNull(parameterName, Types.VARCHAR);
        } else {
            return setString(parameterName, value.toString(10));
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
     * @param value the String value to set, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setNString(final String parameterName, final String value) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setNString(i + 1, value);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setNString(indexes.get(0), value);
                } else if (indexes.size() == 2) {
                    setNString(indexes.get(0), value);
                    setNString(indexes.get(1), value);
                } else if (indexes.size() == 3) {
                    setNString(indexes.get(0), value);
                    setNString(indexes.get(1), value);
                    setNString(indexes.get(2), value);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setNString(indexes.get(i), value);
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
     * @param value the CharSequence value to set, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setNString(final String parameterName, final CharSequence value) throws IllegalArgumentException, SQLException {
        return setNString(parameterName, value == null ? (String) null : value.toString()); //NOSONAR
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
     * @param value the java.sql.Date value to set, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setDate(final String parameterName, final java.sql.Date value) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setDate(i + 1, value);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setDate(indexes.get(0), value);
                } else if (indexes.size() == 2) {
                    setDate(indexes.get(0), value);
                    setDate(indexes.get(1), value);
                } else if (indexes.size() == 3) {
                    setDate(indexes.get(0), value);
                    setDate(indexes.get(1), value);
                    setDate(indexes.get(2), value);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setDate(indexes.get(i), value);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a java.util.Date value.
     *
     * <p>This method converts {@code java.util.Date} to {@code java.sql.Date} (when the input is not already a {@code java.sql.Date}).
     * For preserving time information, use {@link #setTimestamp(String, java.util.Date)} instead, since SQL {@code DATE}
     * columns typically store only the date portion.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * java.util.Date utilDate = new java.util.Date();
     * query.setDate("createdDate", utilDate);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param value the java.util.Date value to set, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setDate(final String parameterName, final java.util.Date value) throws IllegalArgumentException, SQLException {
        setDate(parameterName, value == null ? null : value instanceof java.sql.Date ? (java.sql.Date) value : new java.sql.Date(value.getTime()));

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
     * @param value the LocalDate value to set, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setDate(final String parameterName, final LocalDate value) throws IllegalArgumentException, SQLException {
        setDate(parameterName, value == null ? null : java.sql.Date.valueOf(value));

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
     * @param value the java.sql.Time value to set, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setTime(final String parameterName, final java.sql.Time value) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setTime(i + 1, value);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setTime(indexes.get(0), value);
                } else if (indexes.size() == 2) {
                    setTime(indexes.get(0), value);
                    setTime(indexes.get(1), value);
                } else if (indexes.size() == 3) {
                    setTime(indexes.get(0), value);
                    setTime(indexes.get(1), value);
                    setTime(indexes.get(2), value);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setTime(indexes.get(i), value);
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
     * java.util.Date date = new java.util.Date();   // Current date and time
     * query.setTime("startTime", date);             // Only time portion is used
     *
     * // Using Calendar
     * Calendar cal = Calendar.getInstance();
     * cal.set(Calendar.HOUR_OF_DAY, 14);
     * cal.set(Calendar.MINUTE, 30);
     * query.setTime("startTime", cal.getTime());
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param value the java.util.Date value containing the time to set, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setTime(final String parameterName, final java.util.Date value) throws IllegalArgumentException, SQLException {
        setTime(parameterName, value == null ? null : value instanceof java.sql.Time ? (java.sql.Time) value : new java.sql.Time(value.getTime()));

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
     * @param value the LocalTime value to set, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setTime(final String parameterName, final LocalTime value) throws IllegalArgumentException, SQLException {
        setTime(parameterName, value == null ? null : java.sql.Time.valueOf(value));

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
     * @param value the java.sql.Timestamp value to set, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setTimestamp(final String parameterName, final java.sql.Timestamp value) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setTimestamp(i + 1, value);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setTimestamp(indexes.get(0), value);
                } else if (indexes.size() == 2) {
                    setTimestamp(indexes.get(0), value);
                    setTimestamp(indexes.get(1), value);
                } else if (indexes.size() == 3) {
                    setTimestamp(indexes.get(0), value);
                    setTimestamp(indexes.get(1), value);
                    setTimestamp(indexes.get(2), value);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setTimestamp(indexes.get(i), value);
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
     * java.util.Date now = new java.util.Date();
     * query.setTimestamp("lastLogin", now);
     *
     * // Using Calendar
     * Calendar cal = Calendar.getInstance();
     * cal.add(Calendar.DAY_OF_MONTH, -7);   // 7 days ago
     * query.setTimestamp("weekAgo", cal.getTime());
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param value the java.util.Date value to convert and set, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setTimestamp(final String parameterName, final java.util.Date value) throws IllegalArgumentException, SQLException {
        setTimestamp(parameterName,
                value == null ? null : value instanceof java.sql.Timestamp ? (java.sql.Timestamp) value : new java.sql.Timestamp(value.getTime()));

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
     * @param value the LocalDateTime value to set, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setTimestamp(final String parameterName, final LocalDateTime value) throws IllegalArgumentException, SQLException {
        setTimestamp(parameterName, value == null ? null : Timestamp.valueOf(value));

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
     * @param value the ZonedDateTime value to set, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setTimestamp(final String parameterName, final ZonedDateTime value) throws IllegalArgumentException, SQLException {
        setTimestamp(parameterName, value == null ? null : Timestamp.from(value.toInstant()));

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
     * @param value the OffsetDateTime value to set, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setTimestamp(final String parameterName, final OffsetDateTime value) throws IllegalArgumentException, SQLException {
        setTimestamp(parameterName, value == null ? null : Timestamp.from(value.toInstant()));

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
     * @param value the Instant value to set, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setTimestamp(final String parameterName, final Instant value) throws IllegalArgumentException, SQLException {
        setTimestamp(parameterName, value == null ? null : Timestamp.from(value));

        return this;
    }

    /**
     * Sets the specified named parameter to a byte array value.
     *
     * <p>This method is typically used for storing binary data such as images, files,
     * or serialized objects in {@code BINARY}/{@code VARBINARY} columns (use {@link #setBlob(String, java.sql.Blob)}
     * for {@code BLOB} columns). The entire byte array is sent to the database.
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
     * @param value the byte array to set, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setBytes(final String parameterName, final byte[] value) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setBytes(i + 1, value);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setBytes(indexes.get(0), value);
                } else if (indexes.size() == 2) {
                    setBytes(indexes.get(0), value);
                    setBytes(indexes.get(1), value);
                } else if (indexes.size() == 3) {
                    setBytes(indexes.get(0), value);
                    setBytes(indexes.get(1), value);
                    setBytes(indexes.get(2), value);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setBytes(indexes.get(i), value);
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
     * @param value the InputStream containing ASCII data, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     * @see #setAsciiStream(String, InputStream, long)
     */
    public NamedQuery setAsciiStream(final String parameterName, final InputStream value) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setAsciiStream(i + 1, value);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setAsciiStream(indexes.get(0), value);
                } else if (indexes.size() == 2) {
                    setAsciiStream(indexes.get(0), value);
                    setAsciiStream(indexes.get(1), value);
                } else if (indexes.size() == 3) {
                    setAsciiStream(indexes.get(0), value);
                    setAsciiStream(indexes.get(1), value);
                    setAsciiStream(indexes.get(2), value);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setAsciiStream(indexes.get(i), value);
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
     * @param value the InputStream containing ASCII data, or {@code null} to set SQL {@code NULL}
     * @param length the number of bytes in the stream
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     * @see #setAsciiStream(String, InputStream)
     */
    public NamedQuery setAsciiStream(final String parameterName, final InputStream value, final long length) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setAsciiStream(i + 1, value, length);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setAsciiStream(indexes.get(0), value, length);
                } else if (indexes.size() == 2) {
                    setAsciiStream(indexes.get(0), value, length);
                    setAsciiStream(indexes.get(1), value, length);
                } else if (indexes.size() == 3) {
                    setAsciiStream(indexes.get(0), value, length);
                    setAsciiStream(indexes.get(1), value, length);
                    setAsciiStream(indexes.get(2), value, length);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setAsciiStream(indexes.get(i), value, length);
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
     * @param value the InputStream containing binary data, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     * @see #setBinaryStream(String, InputStream, long)
     */
    public NamedQuery setBinaryStream(final String parameterName, final InputStream value) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setBinaryStream(i + 1, value);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setBinaryStream(indexes.get(0), value);
                } else if (indexes.size() == 2) {
                    setBinaryStream(indexes.get(0), value);
                    setBinaryStream(indexes.get(1), value);
                } else if (indexes.size() == 3) {
                    setBinaryStream(indexes.get(0), value);
                    setBinaryStream(indexes.get(1), value);
                    setBinaryStream(indexes.get(2), value);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setBinaryStream(indexes.get(i), value);
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
     * @param value the InputStream containing binary data, or {@code null} to set SQL {@code NULL}
     * @param length the number of bytes in the stream
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     * @see #setBinaryStream(String, InputStream)
     */
    public NamedQuery setBinaryStream(final String parameterName, final InputStream value, final long length) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setBinaryStream(i + 1, value, length);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setBinaryStream(indexes.get(0), value, length);
                } else if (indexes.size() == 2) {
                    setBinaryStream(indexes.get(0), value, length);
                    setBinaryStream(indexes.get(1), value, length);
                } else if (indexes.size() == 3) {
                    setBinaryStream(indexes.get(0), value, length);
                    setBinaryStream(indexes.get(1), value, length);
                    setBinaryStream(indexes.get(2), value, length);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setBinaryStream(indexes.get(i), value, length);
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
     * FileReader value = new FileReader("large_text.txt", StandardCharsets.UTF_8);
     * query.setCharacterStream("content", value);
     *
     * // From string
     * String largeText = getLargeTextContent();
     * StringReader stringReader = new StringReader(largeText);
     * query.setCharacterStream("description", stringReader);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param value the Reader containing character data, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     * @see #setCharacterStream(String, Reader, long)
     */
    public NamedQuery setCharacterStream(final String parameterName, final Reader value) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setCharacterStream(i + 1, value);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setCharacterStream(indexes.get(0), value);
                } else if (indexes.size() == 2) {
                    setCharacterStream(indexes.get(0), value);
                    setCharacterStream(indexes.get(1), value);
                } else if (indexes.size() == 3) {
                    setCharacterStream(indexes.get(0), value);
                    setCharacterStream(indexes.get(1), value);
                    setCharacterStream(indexes.get(2), value);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setCharacterStream(indexes.get(i), value);
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
     * StringReader value = new StringReader(content);
     * query.setCharacterStream("largeText", value, content.length());
     *
     * // Limited portion of text
     * String fullText = getFullText();
     * StringReader value = new StringReader(fullText);
     * query.setCharacterStream("summary", value, 1000);   // First 1000 characters
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param value the Reader containing character data, or {@code null} to set SQL {@code NULL}
     * @param length the number of characters in the stream
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     * @see #setCharacterStream(String, Reader)
     */
    public NamedQuery setCharacterStream(final String parameterName, final Reader value, final long length) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setCharacterStream(i + 1, value, length);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setCharacterStream(indexes.get(0), value, length);
                } else if (indexes.size() == 2) {
                    setCharacterStream(indexes.get(0), value, length);
                    setCharacterStream(indexes.get(1), value, length);
                } else if (indexes.size() == 3) {
                    setCharacterStream(indexes.get(0), value, length);
                    setCharacterStream(indexes.get(1), value, length);
                    setCharacterStream(indexes.get(2), value, length);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setCharacterStream(indexes.get(i), value, length);
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
     * StringReader value = new StringReader(unicodeText);
     * query.setNCharacterStream("unicodeContent", value);
     *
     * // From file containing unicode data
     * FileReader fileReader = new FileReader("unicode_text.txt", StandardCharsets.UTF_8);
     * query.setNCharacterStream("content", fileReader);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param value the Reader containing national character data, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     * @see #setNCharacterStream(String, Reader, long)
     */
    public NamedQuery setNCharacterStream(final String parameterName, final Reader value) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setNCharacterStream(i + 1, value);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setNCharacterStream(indexes.get(0), value);
                } else if (indexes.size() == 2) {
                    setNCharacterStream(indexes.get(0), value);
                    setNCharacterStream(indexes.get(1), value);
                } else if (indexes.size() == 3) {
                    setNCharacterStream(indexes.get(0), value);
                    setNCharacterStream(indexes.get(1), value);
                    setNCharacterStream(indexes.get(2), value);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setNCharacterStream(indexes.get(i), value);
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
     * StringReader value = new StringReader(unicodeContent);
     * query.setNCharacterStream("description", value, unicodeContent.length());
     *
     * // Partial content
     * String fullText = "Large unicode text with emojis 😀😃😄...";
     * query.setNCharacterStream("preview", new StringReader(fullText), 100);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param value the Reader containing national character data, or {@code null} to set SQL {@code NULL}
     * @param length the number of characters in the stream
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     * @see #setNCharacterStream(String, Reader)
     */
    public NamedQuery setNCharacterStream(final String parameterName, final Reader value, final long length) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setNCharacterStream(i + 1, value, length);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setNCharacterStream(indexes.get(0), value, length);
                } else if (indexes.size() == 2) {
                    setNCharacterStream(indexes.get(0), value, length);
                    setNCharacterStream(indexes.get(1), value, length);
                } else if (indexes.size() == 3) {
                    setNCharacterStream(indexes.get(0), value, length);
                    setNCharacterStream(indexes.get(1), value, length);
                    setNCharacterStream(indexes.get(2), value, length);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setNCharacterStream(indexes.get(i), value, length);
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
     * @param value the java.sql.Blob object, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setBlob(final String parameterName, final java.sql.Blob value) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setBlob(i + 1, value);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setBlob(indexes.get(0), value);
                } else if (indexes.size() == 2) {
                    setBlob(indexes.get(0), value);
                    setBlob(indexes.get(1), value);
                } else if (indexes.size() == 3) {
                    setBlob(indexes.get(0), value);
                    setBlob(indexes.get(1), value);
                    setBlob(indexes.get(2), value);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setBlob(indexes.get(i), value);
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
     * @param value the InputStream containing the BLOB data, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setBlob(final String parameterName, final InputStream value) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setBlob(i + 1, value);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setBlob(indexes.get(0), value);
                } else if (indexes.size() == 2) {
                    setBlob(indexes.get(0), value);
                    setBlob(indexes.get(1), value);
                } else if (indexes.size() == 3) {
                    setBlob(indexes.get(0), value);
                    setBlob(indexes.get(1), value);
                    setBlob(indexes.get(2), value);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setBlob(indexes.get(i), value);
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
     * query.setBlob("preview", bais, 1024);   // First 1KB only
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param value the InputStream containing the BLOB data, or {@code null} to set SQL {@code NULL}
     * @param length the number of bytes to read from the stream
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setBlob(final String parameterName, final InputStream value, final long length) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setBlob(i + 1, value, length);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setBlob(indexes.get(0), value, length);
                } else if (indexes.size() == 2) {
                    setBlob(indexes.get(0), value, length);
                    setBlob(indexes.get(1), value, length);
                } else if (indexes.size() == 3) {
                    setBlob(indexes.get(0), value, length);
                    setBlob(indexes.get(1), value, length);
                    setBlob(indexes.get(2), value, length);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setBlob(indexes.get(i), value, length);
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
     * @param value the java.sql.Clob object, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setClob(final String parameterName, final java.sql.Clob value) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setClob(i + 1, value);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setClob(indexes.get(0), value);
                } else if (indexes.size() == 2) {
                    setClob(indexes.get(0), value);
                    setClob(indexes.get(1), value);
                } else if (indexes.size() == 3) {
                    setClob(indexes.get(0), value);
                    setClob(indexes.get(1), value);
                    setClob(indexes.get(2), value);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setClob(indexes.get(i), value);
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
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param value the Reader object containing the CLOB data, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs or this method is called on a closed PreparedStatement
     */
    public NamedQuery setClob(final String parameterName, final Reader value) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setClob(i + 1, value);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setClob(indexes.get(0), value);
                } else if (indexes.size() == 2) {
                    setClob(indexes.get(0), value);
                    setClob(indexes.get(1), value);
                } else if (indexes.size() == 3) {
                    setClob(indexes.get(0), value);
                    setClob(indexes.get(1), value);
                    setClob(indexes.get(2), value);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setClob(indexes.get(i), value);
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
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param value the Reader object containing the CLOB data, or {@code null} to set SQL {@code NULL}
     * @param length the number of characters in the stream
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs, this method is called on a closed PreparedStatement,
     *         or the length is less than zero
     */
    public NamedQuery setClob(final String parameterName, final Reader value, final long length) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setClob(i + 1, value, length);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setClob(indexes.get(0), value, length);
                } else if (indexes.size() == 2) {
                    setClob(indexes.get(0), value, length);
                    setClob(indexes.get(1), value, length);
                } else if (indexes.size() == 3) {
                    setClob(indexes.get(0), value, length);
                    setClob(indexes.get(1), value, length);
                    setClob(indexes.get(2), value, length);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setClob(indexes.get(i), value, length);
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
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param value the NClob object, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs or this method is called on a closed PreparedStatement
     */
    public NamedQuery setNClob(final String parameterName, final java.sql.NClob value) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setNClob(i + 1, value);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setNClob(indexes.get(0), value);
                } else if (indexes.size() == 2) {
                    setNClob(indexes.get(0), value);
                    setNClob(indexes.get(1), value);
                } else if (indexes.size() == 3) {
                    setNClob(indexes.get(0), value);
                    setNClob(indexes.get(1), value);
                    setNClob(indexes.get(2), value);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setNClob(indexes.get(i), value);
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
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param value the Reader object containing the NCLOB data, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs or this method is called on a closed PreparedStatement
     */
    public NamedQuery setNClob(final String parameterName, final Reader value) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setNClob(i + 1, value);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setNClob(indexes.get(0), value);
                } else if (indexes.size() == 2) {
                    setNClob(indexes.get(0), value);
                    setNClob(indexes.get(1), value);
                } else if (indexes.size() == 3) {
                    setNClob(indexes.get(0), value);
                    setNClob(indexes.get(1), value);
                    setNClob(indexes.get(2), value);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setNClob(indexes.get(i), value);
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
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param value the Reader object containing the NCLOB data, or {@code null} to set SQL {@code NULL}
     * @param length the number of characters in the stream
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs, this method is called on a closed PreparedStatement,
     *         or the length is less than zero
     */
    public NamedQuery setNClob(final String parameterName, final Reader value, final long length) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setNClob(i + 1, value, length);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setNClob(indexes.get(0), value, length);
                } else if (indexes.size() == 2) {
                    setNClob(indexes.get(0), value, length);
                    setNClob(indexes.get(1), value, length);
                } else if (indexes.size() == 3) {
                    setNClob(indexes.get(0), value, length);
                    setNClob(indexes.get(1), value, length);
                    setNClob(indexes.get(2), value, length);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setNClob(indexes.get(i), value, length);
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
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param value the URL object, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs or this method is called on a closed PreparedStatement
     */
    public NamedQuery setURL(final String parameterName, final URL value) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setURL(i + 1, value);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setURL(indexes.get(0), value);
                } else if (indexes.size() == 2) {
                    setURL(indexes.get(0), value);
                    setURL(indexes.get(1), value);
                } else if (indexes.size() == 3) {
                    setURL(indexes.get(0), value);
                    setURL(indexes.get(1), value);
                    setURL(indexes.get(2), value);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setURL(indexes.get(i), value);
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
     * java.io.Writer writer = xmlData.setCharacterStream();
     * writer.write("<root><item>value</item></root>");
     * writer.close();
     * query.setSQLXML("xml_data", xmlData);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param value the SQLXML object, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs or this method is called on a closed PreparedStatement
     */
    public NamedQuery setSQLXML(final String parameterName, final java.sql.SQLXML value) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setSQLXML(i + 1, value);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setSQLXML(indexes.get(0), value);
                } else if (indexes.size() == 2) {
                    setSQLXML(indexes.get(0), value);
                    setSQLXML(indexes.get(1), value);
                } else if (indexes.size() == 3) {
                    setSQLXML(indexes.get(0), value);
                    setSQLXML(indexes.get(1), value);
                    setSQLXML(indexes.get(2), value);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setSQLXML(indexes.get(i), value);
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
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param value the RowId object, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs or this method is called on a closed PreparedStatement
     */
    public NamedQuery setRowId(final String parameterName, final java.sql.RowId value) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setRowId(i + 1, value);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setRowId(indexes.get(0), value);
                } else if (indexes.size() == 2) {
                    setRowId(indexes.get(0), value);
                    setRowId(indexes.get(1), value);
                } else if (indexes.size() == 3) {
                    setRowId(indexes.get(0), value);
                    setRowId(indexes.get(1), value);
                    setRowId(indexes.get(2), value);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setRowId(indexes.get(i), value);
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
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param value the Ref object, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs or this method is called on a closed PreparedStatement
     */
    public NamedQuery setRef(final String parameterName, final java.sql.Ref value) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setRef(i + 1, value);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setRef(indexes.get(0), value);
                } else if (indexes.size() == 2) {
                    setRef(indexes.get(0), value);
                    setRef(indexes.get(1), value);
                } else if (indexes.size() == 3) {
                    setRef(indexes.get(0), value);
                    setRef(indexes.get(1), value);
                    setRef(indexes.get(2), value);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setRef(indexes.get(i), value);
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
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param value the Array object, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs or this method is called on a closed PreparedStatement
     */
    public NamedQuery setArray(final String parameterName, final java.sql.Array value) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setArray(i + 1, value);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setArray(indexes.get(0), value);
                } else if (indexes.size() == 2) {
                    setArray(indexes.get(0), value);
                    setArray(indexes.get(1), value);
                } else if (indexes.size() == 3) {
                    setArray(indexes.get(0), value);
                    setArray(indexes.get(1), value);
                    setArray(indexes.get(2), value);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setArray(indexes.get(i), value);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets an Object parameter for the specified parameter name, using the default SQL type mapping.
     *
     * <p>This is a generic method that can handle any Java object. The Abacus type system (and
     * the underlying JDBC driver) will convert the Java object to an appropriate SQL value.
     * Common mappings include:
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
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param value the object containing the parameter value, or {@code null} to set SQL {@code NULL}
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs, this method is called on a closed PreparedStatement,
     *         or the given object cannot be converted to a SQL type
     */
    public NamedQuery setObject(final String parameterName, final Object value) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setObject(i + 1, value);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setObject(indexes.get(0), value);
                } else if (indexes.size() == 2) {
                    setObject(indexes.get(0), value);
                    setObject(indexes.get(1), value);
                } else if (indexes.size() == 3) {
                    setObject(indexes.get(0), value);
                    setObject(indexes.get(1), value);
                    setObject(indexes.get(2), value);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setObject(indexes.get(i), value);
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
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param value the object containing the parameter value, or {@code null} to set SQL {@code NULL}
     * @param sqlType the SQL type (from java.sql.Types) to be used
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs, this method is called on a closed PreparedStatement,
     *         or the object cannot be converted to the specified SQL type
     * @see java.sql.Types
     */
    public NamedQuery setObject(final String parameterName, final Object value, final int sqlType) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setObject(i + 1, value, sqlType);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setObject(indexes.get(0), value, sqlType);
                } else if (indexes.size() == 2) {
                    setObject(indexes.get(0), value, sqlType);
                    setObject(indexes.get(1), value, sqlType);
                } else if (indexes.size() == 3) {
                    setObject(indexes.get(0), value, sqlType);
                    setObject(indexes.get(1), value, sqlType);
                    setObject(indexes.get(2), value, sqlType);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setObject(indexes.get(i), value, sqlType);
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
     * <li>For Java types {@link java.io.InputStream} and {@link java.io.Reader}: scaleOrLength represents the length of the data in the stream/reader</li>
     * <li>For all other types: this value is ignored (per the JDBC specification)</li>
     * </ul>
     *
     * <p>If the parameter name appears multiple times in the query, all occurrences will be set to the same value.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Set a decimal with 2 decimal places
     * query.setObject("amount", new BigDecimal("123.45"), Types.DECIMAL, 2);
     * // Set an InputStream as BLOB with known length
     * query.setObject("data", value, Types.BLOB, contentLength);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param value the object containing the parameter value, or {@code null} to set SQL {@code NULL}
     * @param sqlType the SQL type (from java.sql.Types) to be used
     * @param scaleOrLength for numeric types, the number of digits after the decimal point;
     *        for {@link java.io.InputStream}/{@link java.io.Reader}, the stream length; otherwise ignored
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs, this method is called on a closed PreparedStatement,
     *         or the object cannot be converted to the specified SQL type
     * @see java.sql.Types
     */
    public NamedQuery setObject(final String parameterName, final Object value, final int sqlType, final int scaleOrLength)
            throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setObject(i + 1, value, sqlType, scaleOrLength);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setObject(indexes.get(0), value, sqlType, scaleOrLength);
                } else if (indexes.size() == 2) {
                    setObject(indexes.get(0), value, sqlType, scaleOrLength);
                    setObject(indexes.get(1), value, sqlType, scaleOrLength);
                } else if (indexes.size() == 3) {
                    setObject(indexes.get(0), value, sqlType, scaleOrLength);
                    setObject(indexes.get(1), value, sqlType, scaleOrLength);
                    setObject(indexes.get(2), value, sqlType, scaleOrLength);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setObject(indexes.get(i), value, sqlType, scaleOrLength);
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
     * query.setObject("user_id", 12345, JDBCType.INTEGER)
     *      .setObject("email", "user@example.com", JDBCType.VARCHAR)
     *      .setObject("balance", new BigDecimal("1000.00"), JDBCType.DECIMAL);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param value the object containing the parameter value, or {@code null} to set SQL {@code NULL}
     * @param sqlType the SQLType to be used
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs, this method is called on a closed PreparedStatement,
     *         or the object cannot be converted to the specified SQL type
     */
    public NamedQuery setObject(final String parameterName, final Object value, final SQLType sqlType) throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setObject(i + 1, value, sqlType);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setObject(indexes.get(0), value, sqlType);
                } else if (indexes.size() == 2) {
                    setObject(indexes.get(0), value, sqlType);
                    setObject(indexes.get(1), value, sqlType);
                } else if (indexes.size() == 3) {
                    setObject(indexes.get(0), value, sqlType);
                    setObject(indexes.get(1), value, sqlType);
                    setObject(indexes.get(2), value, sqlType);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setObject(indexes.get(i), value, sqlType);
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
     * on the SQL type being used: for numeric types it is the scale, for {@link java.io.InputStream}/{@link java.io.Reader}
     * it is the stream length, and for other types it is ignored (per the JDBC specification).
     *
     * <p>If the parameter name appears multiple times in the query, all occurrences will be set to the same value.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Set a decimal with specific scale
     * query.setObject("price", new BigDecimal("99.999"), JDBCType.DECIMAL, 2);
     * // Set an InputStream as BLOB with known length
     * query.setObject("data", value, JDBCType.BLOB, contentLength);
     * }</pre>
     *
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param value the object containing the parameter value, or {@code null} to set SQL {@code NULL}
     * @param sqlType the SQLType to be used
     * @param scaleOrLength for numeric types, the number of digits after the decimal point;
     *        for {@link java.io.InputStream}/{@link java.io.Reader}, the stream length; otherwise ignored
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs, this method is called on a closed PreparedStatement,
     *         or the object cannot be converted to the specified SQL type
     */
    public NamedQuery setObject(final String parameterName, final Object value, final SQLType sqlType, final int scaleOrLength)
            throws IllegalArgumentException, SQLException {
        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    setObject(i + 1, value, sqlType, scaleOrLength);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    setObject(indexes.get(0), value, sqlType, scaleOrLength);
                } else if (indexes.size() == 2) {
                    setObject(indexes.get(0), value, sqlType, scaleOrLength);
                    setObject(indexes.get(1), value, sqlType, scaleOrLength);
                } else if (indexes.size() == 3) {
                    setObject(indexes.get(0), value, sqlType, scaleOrLength);
                    setObject(indexes.get(1), value, sqlType, scaleOrLength);
                    setObject(indexes.get(2), value, sqlType, scaleOrLength);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        setObject(indexes.get(i), value, sqlType, scaleOrLength);
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
     * @param parameterName the name of the parameter to be set (without the ':' prefix)
     * @param value the object containing the parameter value, or {@code null} to set SQL {@code NULL}
     * @param type the Type handler to use for setting the parameter. Must not be {@code null}.
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if {@code type} is {@code null}, or the parameter name is not found in the SQL query
     * @throws SQLException if a database access error occurs or this method is called on a closed PreparedStatement
     */
    public <T> NamedQuery setObject(final String parameterName, final T value, final Type<T> type) throws IllegalArgumentException, SQLException {
        checkArgNotNull(type, cs.type);

        if (parameterCount < MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP) {
            int cnt = 0;

            for (int i = 0; i < parameterCount; i++) {
                if (parameterNames.get(i).equals(parameterName)) {
                    type.set(stmt, i + 1, value);
                    cnt++;
                }
            }

            if (cnt == 0) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            }
        } else {
            if (paramNameIndexMap == null) {
                initParamNameIndexMap();
            }

            final IntList indexes = paramNameIndexMap.get(parameterName);

            if (indexes == null) {
                close();
                throw new IllegalArgumentException("Named parameter not found: " + parameterName);
            } else {
                if (indexes.size() == 1) {
                    type.set(stmt, indexes.get(0), value);
                } else if (indexes.size() == 2) {
                    type.set(stmt, indexes.get(0), value);
                    type.set(stmt, indexes.get(1), value);
                } else if (indexes.size() == 3) {
                    type.set(stmt, indexes.get(0), value);
                    type.set(stmt, indexes.get(1), value);
                    type.set(stmt, indexes.get(2), value);
                } else {
                    for (int i = 0, size = indexes.size(); i < size; i++) {
                        type.set(stmt, indexes.get(i), value);
                    }
                }
            }
        }

        return this;
    }

    /**
     * Sets multiple parameters from a Map containing parameter names and their values.
     *
     * <p>For each named parameter declared in the SQL, this method looks the name up in the map;
     * if the map contains a matching key, {@link #setObject(String, Object)} is called for that
     * parameter. Map entries whose keys do not correspond to named parameters in the SQL are
     * ignored. Named parameters that are absent from the map are left unbound and must be bound
     * before the query is executed.
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
     * @param parameters a map containing parameter names (without the ':' prefix) as keys and their values
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if {@code parameters} is {@code null}
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setParameters(final Map<String, ?> parameters) throws IllegalArgumentException, SQLException {
        checkArgNotNull(parameters, cs.parameters);

        try {
            for (final String paramName : parameterNames) {
                if (parameters.containsKey(paramName)) {
                    setObject(paramName, parameters.get(paramName));
                }
            }
        } catch (final Exception e) {
            close();
            throw e;
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
        try {
            for (final String paramName : parameterNames) {
                if (entityId.containsKey(paramName)) {
                    setObject(paramName, entityId.get(paramName));
                }
            }
        } catch (final Exception e) {
            close();
            throw e;
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
     * <p><b>Usage Examples:</b></p>
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
     * @throws IllegalArgumentException if {@code parameters} is {@code null}, is of an unsupported type, or is a
     *         bean that lacks a property matching one of the named parameters in the SQL
     * @throws SQLException if a database access error occurs
     * @see JdbcUtil#namedParameters(String)
     */
    @SuppressWarnings("rawtypes")
    public NamedQuery setParameters(final Object parameters) throws IllegalArgumentException, SQLException {
        checkArgNotNull(parameters, cs.parameters);

        final Class<?> cls = parameters.getClass();

        if (Beans.isBeanClass(cls)) {
            final BeanInfo entityInfo = ParserUtil.getBeanInfo(cls);
            PropInfo propInfo = null;

            try {
                for (int i = 0; i < parameterCount; i++) {
                    propInfo = entityInfo.getPropInfo(parameterNames.get(i));

                    if (propInfo == null) {
                        if (!JdbcUtil.SYS_DATE_TIME_NAME_SET.contains(parameterNames.get(i))) {
                            close();
                            throw new IllegalArgumentException(
                                    "No property found with name: " + parameterNames.get(i) + " in class: " + ClassUtil.getCanonicalClassName(cls));
                        }
                    } else {
                        propInfo.dbType.set(stmt, i + 1, propInfo.getPropValue(parameters));
                    }
                }
            } catch (final Exception e) {
                close();
                throw e;
            }
        } else if (parameters instanceof Map) {
            return setParameters((Map<String, ?>) parameters);
        } else if (parameters instanceof Collection) {
            try {
                return setParameters((Collection) parameters);
            } catch (final Exception e) {
                close();
                throw e;
            }
        } else if (parameters instanceof Object[]) {
            try {
                return setParameters((Object[]) parameters);
            } catch (final Exception e) {
                close();
                throw e;
            }
        } else if (parameters instanceof EntityId) {
            setParameters((EntityId) parameters);
        } else if (parameterCount == 1) {
            try {
                setObject(1, parameters);
            } catch (final Exception e) {
                close();
                throw e;
            }
        } else {
            close();
            throw new IllegalArgumentException("Unsupported named parameter type: " + parameters.getClass() + " for SQL: " + namedSql.originalSql());
        }

        return this;
    }

    /**
     * Sets the specified named parameters from an entity (bean/record) by reading the matching properties.
     *
     * <p>For each name in {@code parameterNames}, the entity must expose a property of the same name
     * (otherwise an {@link IllegalArgumentException} is thrown). The value of that property is bound
     * to every occurrence of the named parameter in the SQL. Named parameters in the SQL that are
     * not listed in {@code parameterNames} are left unbound by this call — bind them separately
     * before executing the query.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // SQL: UPDATE users SET name = :name, email = :email WHERE id = :id
     * User user = new User();
     * user.setId(1);
     * user.setName("John");
     * user.setEmail("john@example.com");
     *
     * // Bind only the name and email properties; bind id separately.
     * query.setParameters(user, Arrays.asList("name", "email"))
     *      .setLong("id", user.getId());
     * }</pre>
     *
     * @param entity the bean or record whose properties supply the parameter values
     * @param parameterNames the names of the parameters (and matching property names) to bind
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if {@code entity} or {@code parameterNames} is {@code null}, if
     *         {@code entity} is not a bean/record class, if a listed property does not exist on the
     *         entity, or if a listed name is not a parameter in the SQL query
     * @throws SQLException if a database access error occurs
     * @see Beans#getPropNameList(Class)
     * @see Beans#getPropNames(Class, Collection)
     * @see JdbcUtil#namedParameters(String)
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

            try {
                for (final String parameterName : parameterNames) {
                    propInfo = entityInfo.getPropInfo(parameterName);

                    if (propInfo == null) {
                        throw new IllegalArgumentException(
                                "No property found with name: " + parameterName + " in class: " + ClassUtil.getCanonicalClassName(cls));
                    }

                    propValue = propInfo.getPropValue(entity);
                    dbType = propInfo.dbType;

                    indexes = paramNameIndexMap.get(parameterName);

                    if (indexes == null) {
                        throw new IllegalArgumentException("Named parameter not found: " + parameterName);
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
            } catch (final Exception e) {
                close();
                throw e;
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
     * and your parameters object. If the setter throws, the backing statement is closed and the
     * exception propagates to the caller.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * UserSearchCriteria criteria = new UserSearchCriteria();
     * criteria.setMinAge(25);
     * criteria.setMaxAge(35);
     * criteria.setActive(Boolean.TRUE);   // nullable Boolean for optional filter
     *
     * query.setParameters(criteria, (sql, q, c) -> {
     *     q.setInt("min_age", c.getMinAge());
     *     q.setInt("max_age", c.getMaxAge());
     *     if (c.getActive() != null) {
     *         q.setBoolean("is_active", c.getActive());
     *     }
     * });
     * }</pre>
     *
     * @param <T> the type of the parameters object
     * @param parameters the parameters object to pass to the setter
     * @param parametersSetter a tri-consumer that receives the parsed SQL, this NamedQuery instance, and the parameters object
     * @return this NamedQuery instance for method chaining
     * @throws IllegalArgumentException if {@code parametersSetter} is {@code null}
     * @throws SQLException if a database access error occurs (e.g. propagated from {@code parametersSetter})
     */
    public <T> NamedQuery setParameters(final T parameters, final Jdbc.TriParametersSetter<? super NamedQuery, ? super T> parametersSetter)
            throws IllegalArgumentException, SQLException {
        checkArgNotNull(parametersSetter, cs.parametersSetter);

        boolean noException = false;

        try {
            parametersSetter.accept(namedSql, this, parameters);

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
     * <p>All elements are interpreted in the same way as the first element (see {@link #addBatchParameters(Iterator)}).
     * If {@code batchParameters} is empty, this is a no-op and no batch is added.
     *
     * <p>After adding batch parameters, call {@link #batchUpdate()} or {@link #batchInsert()} to execute the batch.
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
     *          .batchUpdate();
     * }
     *
     * // Using maps
     * List<Map<String, Object>> paramMaps = new ArrayList<>();
     * paramMaps.add(Map.of("id", 1, "status", "ACTIVE"));
     * paramMaps.add(Map.of("id", 2, "status", "INACTIVE"));
     *
     * query.addBatchParameters(paramMaps)
     *      .batchUpdate();
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
     * <p>The runtime type of the <i>first</i> element determines how every element is interpreted; all
     * remaining elements are assumed to be of the same kind. If the iterator is empty, this is a no-op
     * and no batch is added. A {@code null} element is only supported when the SQL has exactly one named
     * parameter (it is bound as SQL {@code NULL}); otherwise an {@link IllegalArgumentException} is thrown.
     *
     * <p>After adding batch parameters, call {@link #batchUpdate()} or {@link #batchInsert()} to execute the batch.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Using an iterator from a database result
     * Iterator<User> userIterator = getUsersFromSource();
     * query.addBatchParameters(userIterator)
     *      .batchUpdate();
     *
     * // Using a stream for large data sets
     * query.addBatchParameters(
     *     userRepository.findAllActive()
     *                  .stream()
     *                  .filter(u -> u.getLastLogin().isAfter(oneMonthAgo))
     *                  .iterator()
     * ).batchUpdate();
     *
     * // Processing data in chunks to avoid loading everything into memory
     * insertQuery.addBatchParameters(streamOfRecords.iterator())
     *            .batchUpdate();
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
                noException = true;
                return this;
            }

            final Object first = iter.next();

            if (first == null) {
                if (parameterCount != 1) {
                    throw new IllegalArgumentException("Unsupported named parameter type: null for SQL: " + namedSql.originalSql());
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

                        if (propInfos[i] == null && !JdbcUtil.SYS_DATE_TIME_NAME_SET.contains(parameterNames.get(i))) {
                            throw new IllegalArgumentException(
                                    "No property found with name: " + parameterNames.get(i) + " in class: " + ClassUtil.getCanonicalClassName(cls));
                        }
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
                        stmt.clearParameters();
                        setParameters(params);
                        addBatch();
                    }
                } else if (first instanceof Collection) {
                    setParameters((Collection) first);
                    addBatch();

                    Collection params = null;
                    while (iter.hasNext()) {
                        params = (Collection) iter.next();
                        stmt.clearParameters();
                        setParameters(params);
                        addBatch();
                    }
                } else if (first instanceof Object[]) {
                    setParameters((Object[]) first);
                    addBatch();

                    Object[] params = null;
                    while (iter.hasNext()) {
                        params = (Object[]) iter.next();
                        stmt.clearParameters();
                        setParameters(params);
                        addBatch();
                    }
                } else if (first instanceof EntityId) {
                    setParameters((EntityId) first);
                    addBatch();

                    EntityId params = null;
                    while (iter.hasNext()) {
                        params = (EntityId) iter.next();
                        stmt.clearParameters();
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
                    throw new IllegalArgumentException("Unsupported named parameter type: " + cls + " for SQL: " + namedSql.originalSql());
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

}
