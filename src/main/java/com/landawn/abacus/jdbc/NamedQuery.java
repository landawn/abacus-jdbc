/*
 * Copyright (c) 2020, Haiyang Li.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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
import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.ClassUtil;
import com.landawn.abacus.util.EntityId;
import com.landawn.abacus.util.IntList;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.ParsedSql;

/**
 * The backed {@code PreparedStatement/CallableStatement} will be closed by default
 * after any execution methods(which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/findOnlyOne/list/execute/...).
 * except the {@code 'closeAfterExecution'} flag is set to {@code false} by calling {@code #closeAfterExecution(false)}.
 *
 * <br />
 * Generally, don't cache or reuse the instance of this class,
 * except the {@code 'closeAfterExecution'} flag is set to {@code false} by calling {@code #closeAfterExecution(false)}.
 *
 * <br />
 * The {@code ResultSet} returned by query will always be closed after execution, even {@code 'closeAfterExecution'} flag is set to {@code false}.
 *
 * <br />
 * Remember: parameter/column index in {@code PreparedStatement/ResultSet} starts from 1, not 0.
 *
 *
 * @see {@link com.landawn.abacus.annotation.ReadOnly}
 * @see {@link com.landawn.abacus.annotation.ReadOnlyId}
 * @see {@link com.landawn.abacus.annotation.NonUpdatable}
 * @see {@link com.landawn.abacus.annotation.Transient}
 * @see {@link com.landawn.abacus.annotation.Table}
 * @see {@link com.landawn.abacus.annotation.Column}
 *
 * @see <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Connection.html">Connection</a>
 * @see <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Statement.html">Statement</a>
 * @see <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/PreparedStatement.html">PreparedStatement</a>
 * @see <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/ResultSet.html">ResultSet</a>
 */
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
     * Sets the specified parameter to SQL NULL.
     *
     * @param parameterName the name of the parameter to be set to NULL
     * @param sqlType the SQL type code defined in {@link java.sql.Types}
     * @return the current instance of {@code NamedQuery}
     * @throw IllegalArgumentException if the parameter name is not found.
     * @throws SQLException if a database access error occurs.
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
     * Sets the specified parameter to SQL NULL with a specified SQL type and type name.
     *
     * @param parameterName the name of the parameter to be set to NULL
     * @param sqlType the SQL type code defined in {@link java.sql.Types}
     * @param typeName the SQL type name
     * @return the current instance of {@code NamedQuery}
     * @throw IllegalArgumentException if the parameter name is not found.
     * @throws SQLException if a database access error occurs.
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
     * Sets the specified parameter to a boolean value.
     *
     * @param parameterName the name of the parameter to be set
     * @param x the boolean value to set the parameter to
     * @return the current instance of {@code NamedQuery}
     * @throw IllegalArgumentException if the parameter name is not found.
     * @throws SQLException if a database access error occurs.
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
     * Sets the specified parameter to a boolean value.
     *
     * @param parameterName the name of the parameter to be set
     * @param x the boolean value to set the parameter to, or {@code null} to set the parameter to SQL NULL
     * @return the current instance of {@code NamedQuery}
     * @throws IllegalArgumentException if the parameter name is not found
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
     * Sets the specified parameter to a byte value.
     *
     * @param parameterName the name of the parameter to be set
     * @param x the byte value to set the parameter to
     * @return the current instance of {@code NamedQuery}
     * @throws IllegalArgumentException if the parameter name is not found
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
     * Sets the specified parameter to a byte value.
     *
     * @param parameterName the name of the parameter to be set
     * @param x the byte value to set the parameter to, or {@code null} to set the parameter to SQL NULL
     * @return the current instance of {@code NamedQuery}
     * @throws IllegalArgumentException if the parameter name is not found
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
     * Sets the specified parameter to a short value.
     *
     * @param parameterName the name of the parameter to be set
     * @param x the short value to set the parameter to
     * @return the current instance of {@code NamedQuery}
     * @throws IllegalArgumentException if the parameter name is not found
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
     * Sets the specified parameter to a short value.
     *
     * @param parameterName the name of the parameter to be set
     * @param x the short value to set the parameter to, or {@code null} to set the parameter to SQL NULL
     * @return the current instance of {@code NamedQuery}
     * @throws IllegalArgumentException if the parameter name is not found
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
     * Sets the int.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
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
     * Sets the int.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
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
     *
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException
     * @see #setString(String, char)
     * @deprecated generally {@code char} should be saved as {@code String} in db.
     */
    @Deprecated
    public NamedQuery setInt(final String parameterName, final char x) throws IllegalArgumentException, SQLException {
        return setInt(parameterName, (int) x);
    }

    /**
     *
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException
     * @see #setString(String, Character)
     * @deprecated generally {@code char} should be saved as {@code String} in db.
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
     * Sets the long.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
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
     * Sets the long.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
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
     * Sets the long.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
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
     * Sets the float.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
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
     * Sets the float.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
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
     * Sets the double.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
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
     * Sets the double.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
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
     * Sets the big decimal.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
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
     *
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
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
     * Sets the BigInteger.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
     * @throws SQLException if a database access error occurs
     * @see {@link #setString(String, BigInteger)}
     * @see {@link #setBigDecimal(String, BigInteger)}
     * @see {@link #setLong(String, BigInteger)}
     */
    @Beta
    public NamedQuery setBigIntegerAsString(final String parameterName, final BigInteger x) throws IllegalArgumentException, SQLException {
        return setString(parameterName, x);
    }

    /**
     * Sets the string.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
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
     *
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setString(final String parameterName, final CharSequence x) throws IllegalArgumentException, SQLException {
        return setString(parameterName, x == null ? (String) null : x.toString()); //NOSONAR
    }

    /**
     *
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setString(final String parameterName, final char x) throws IllegalArgumentException, SQLException {
        return setString(parameterName, String.valueOf(x));
    }

    /**
     *
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setString(final String parameterName, final Character x) throws IllegalArgumentException, SQLException {
        return setString(parameterName, x == null ? (String) null : x.toString()); //NOSONAR
    }

    /**
     *
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
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
     * Sets the string.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
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
     * Sets the string.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
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
     * Sets the date.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
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
     * Sets the date.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setDate(final String parameterName, final java.util.Date x) throws IllegalArgumentException, SQLException {
        setDate(parameterName, x == null ? null : x instanceof java.sql.Date ? (java.sql.Date) x : new java.sql.Date(x.getTime()));

        return this;
    }

    /**
     * Sets the date.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setDate(final String parameterName, final LocalDate x) throws IllegalArgumentException, SQLException {
        setDate(parameterName, x == null ? null : java.sql.Date.valueOf(x));

        return this;
    }

    /**
     * Sets the time.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
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
     * Sets the time.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setTime(final String parameterName, final java.util.Date x) throws IllegalArgumentException, SQLException {
        setTime(parameterName, x == null ? null : x instanceof java.sql.Time ? (java.sql.Time) x : new java.sql.Time(x.getTime()));

        return this;
    }

    /**
     * Sets the time.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setTime(final String parameterName, final LocalTime x) throws IllegalArgumentException, SQLException {
        setTime(parameterName, x == null ? null : java.sql.Time.valueOf(x));

        return this;
    }

    /**
     * Sets the timestamp.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
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
     * Sets the timestamp.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setTimestamp(final String parameterName, final java.util.Date x) throws IllegalArgumentException, SQLException {
        setTimestamp(parameterName, x == null ? null : x instanceof java.sql.Timestamp ? (java.sql.Timestamp) x : new java.sql.Timestamp(x.getTime()));

        return this;
    }

    /**
     * Sets the timestamp.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setTimestamp(final String parameterName, final LocalDateTime x) throws IllegalArgumentException, SQLException {
        setTimestamp(parameterName, x == null ? null : Timestamp.valueOf(x));

        return this;
    }

    /**
     * Sets the timestamp.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setTimestamp(final String parameterName, final ZonedDateTime x) throws IllegalArgumentException, SQLException {
        setTimestamp(parameterName, x == null ? null : Timestamp.from(x.toInstant()));

        return this;
    }

    /**
     * Sets the timestamp.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setTimestamp(final String parameterName, final OffsetDateTime x) throws IllegalArgumentException, SQLException {
        setTimestamp(parameterName, x == null ? null : Timestamp.from(x.toInstant()));

        return this;
    }

    /**
     * Sets the timestamp.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setTimestamp(final String parameterName, final Instant x) throws IllegalArgumentException, SQLException {
        setTimestamp(parameterName, x == null ? null : Timestamp.from(x));

        return this;
    }

    /**
     * Sets the bytes.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
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
     * Sets the ascii stream.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
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
     * Sets the ascii stream.
     *
     * @param parameterName
     * @param x
     * @param length
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
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
     * Sets the binary stream.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
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
     * Sets the binary stream.
     *
     * @param parameterName
     * @param x
     * @param length
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
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
     * Sets the character stream.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
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
     * Sets the character stream.
     *
     * @param parameterName
     * @param x
     * @param length
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
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
     * Sets the N character stream.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
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
     * Sets the N character stream.
     *
     * @param parameterName
     * @param x
     * @param length
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
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
     * Sets the blob.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
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
     * Sets the blob.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
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
     * Sets the blob.
     *
     * @param parameterName
     * @param x
     * @param length
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
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
     * Sets the clob.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
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
     * Sets the clob.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
     * @throws SQLException if a database access error occurs
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
     * Sets the clob.
     *
     * @param parameterName
     * @param x
     * @param length
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
     * @throws SQLException if a database access error occurs
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
     * Sets the NClob.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
     * @throws SQLException if a database access error occurs
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
     * Sets the NClob.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
     * @throws SQLException if a database access error occurs
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
     * Sets the NClob.
     *
     * @param parameterName
     * @param x
     * @param length
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
     * @throws SQLException if a database access error occurs
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
     *
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
     * @throws SQLException if a database access error occurs
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
     *
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
     * @throws SQLException if a database access error occurs
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
     *
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
     * @throws SQLException if a database access error occurs
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
     *
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
     * @throws SQLException if a database access error occurs
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
     *
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
     * @throws SQLException if a database access error occurs
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
     * Sets the object.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
     * @throws SQLException if a database access error occurs
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
     * Sets the object.
     *
     * @param parameterName
     * @param x
     * @param sqlType
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
     * @throws SQLException if a database access error occurs
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
     * Sets the object.
     *
     * @param parameterName
     * @param x
     * @param sqlType
     * @param scaleOrLength
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
     * @throws SQLException if a database access error occurs
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
     * Sets the object.
     *
     * @param parameterName
     * @param x
     * @param sqlType
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
     * @throws SQLException if a database access error occurs
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
     * Sets the object.
     *
     * @param parameterName
     * @param x
     * @param sqlType
     * @param scaleOrLength
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
     * @throws SQLException if a database access error occurs
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
     * Sets the object.
     *
     * @param parameterName
     * @param x
     * @param type
     * @return
     * @throws IllegalArgumentException if the parameter name is not found
     * @throws SQLException if a database access error occurs
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
     * Sets the parameters for the named query.
     *
     * @param parameters a map containing the parameter names and their corresponding values
     * @return the current instance of {@code NamedQuery}
     * @throws SQLException if a database access error occurs
     */
    public NamedQuery setParameters(final Map<String, ?> parameters) throws IllegalArgumentException, SQLException {
        checkArgNotNull(parameters, s.parameters);

        for (final String paramName : parameterNames) {
            if (parameters.containsKey(paramName)) {
                setObject(paramName, parameters.get(paramName));
            }
        }

        return this;
    }

    void setParameters(final EntityId entityId) throws SQLException {
        for (final String paramName : parameterNames) {
            if (entityId.containsKey(paramName)) {
                setObject(paramName, entityId.get(paramName));
            }
        }
    }

    /**
     * Sets the parameters for the named query.
     *
     * @param parameters an object containing the parameters. It could be a bean, a map, a collection, an array or a single value.
     * @return the current instance of {@code NamedQuery}
     * @throws IllegalArgumentException if the parameters object is {@code null}.
     * @throws SQLException if a database access error occurs
     * @see JdbcUtil#getNamedParameters(String)
     */
    @SuppressWarnings("rawtypes")
    public NamedQuery setParameters(final Object parameters) throws IllegalArgumentException, SQLException {
        checkArgNotNull(parameters, s.parameters);

        final Class<?> cls = parameters.getClass();

        if (ClassUtil.isBeanClass(cls)) {
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
     * Sets the parameters for the named query.
     *
     * @param entity the entity object containing the parameters
     * @param parameterNames a collection of parameter names to be set
     * @return the current instance of {@code NamedQuery}
     * @throws IllegalArgumentException if the entity or parameter names are null
     * @throws SQLException if a database access error occurs
     * @see ClassUtil#getPropNameList(Class)
     * @see ClassUtil#getPropNames(Class, Collection)
     * @see JdbcUtil#getNamedParameters(String)
     */
    public NamedQuery setParameters(final Object entity, final Collection<String> parameterNames) throws IllegalArgumentException, SQLException {
        checkArgNotNull(entity, s.entity);
        checkArgNotNull(parameterNames, s.parameterNames);

        if (paramNameIndexMap == null) {
            initParamNameIndexMap();
        }

        final Class<?> cls = entity.getClass();
        if (ClassUtil.isBeanClass(cls)) {
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
     * Sets the parameters for the named query using a custom parameters setter.
     *
     * @param <T> the type of the parameters object
     * @param parameters the parameters object
     * @param paramsSetter a custom parameters setter
     * @return the current instance of {@code NamedQuery}
     * @throws IllegalArgumentException if the parameters setter is null
     * @throws SQLException if a database access error occurs
     */
    public <T> NamedQuery setParameters(final T parameters, final Jdbc.TriParametersSetter<? super NamedQuery, ? super T> paramsSetter)
            throws IllegalArgumentException, SQLException {
        checkArgNotNull(paramsSetter, s.paramsSetter);

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
     * Adds a batch of parameters to the named query.
     *
     * @param batchParameters a collection of batch parameters to be added
     * @return the current instance of {@code NamedQuery}
     * @throws IllegalArgumentException if the batch parameters are null
     * @throws SQLException if a database access error occurs
     */
    @Override
    public NamedQuery addBatchParameters(final Collection<?> batchParameters) throws IllegalArgumentException, SQLException {
        checkArgNotNull(batchParameters, s.batchParameters);

        if (N.isEmpty(batchParameters)) {
            return this;
        }

        return addBatchParameters(batchParameters.iterator());
    }

    /**
     * Adds a batch of parameters to the named query.
     *
     * @param batchParameters an iterator of batch parameters to be added
     * @return the current instance of {@code NamedQuery}
     * @throws IllegalArgumentException if the batch parameters are null
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @Override
    @SuppressWarnings("rawtypes")
    public NamedQuery addBatchParameters(final Iterator<?> batchParameters) throws IllegalArgumentException, SQLException {
        checkArgNotNull(batchParameters, s.batchParameters);

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

                if (ClassUtil.isBeanClass(cls)) {
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
