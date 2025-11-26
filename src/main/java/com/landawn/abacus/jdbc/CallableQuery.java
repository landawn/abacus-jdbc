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
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.jdbc.Jdbc.BiResultExtractor;
import com.landawn.abacus.jdbc.Jdbc.BiRowFilter;
import com.landawn.abacus.jdbc.Jdbc.BiRowMapper;
import com.landawn.abacus.jdbc.Jdbc.ResultExtractor;
import com.landawn.abacus.jdbc.Jdbc.RowFilter;
import com.landawn.abacus.jdbc.Jdbc.RowMapper;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.BeanInfo;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.util.Beans;
import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Throwables;
import com.landawn.abacus.util.Tuple;
import com.landawn.abacus.util.Tuple.Tuple2;
import com.landawn.abacus.util.Tuple.Tuple3;
import com.landawn.abacus.util.Tuple.Tuple4;
import com.landawn.abacus.util.stream.ObjIteratorEx;

/**
 * A wrapper class for {@link CallableStatement} that provides a fluent API for executing stored procedures
 * and handling OUT parameters. This class extends {@link AbstractQuery} and provides comprehensive support
 * for calling database stored procedures with both IN and OUT parameters.
 * 
 * <p>The backed {@code CallableStatement} will be closed by default after any execution methods
 * (which will trigger the backed {@code CallableStatement} to be executed, for example,
 * get/query/queryForInt/Long/../findFirst/findOnlyOne/list/execute/..). 
 * Except the {@code 'closeAfterExecution'} flag is set to {@code false} by calling {@code #closeAfterExecution(false)}.</p>
 *
 * <p>Generally, don't cache or reuse the instance of this class, except the {@code 'closeAfterExecution'} 
 * flag is set to {@code false} by calling {@code #closeAfterExecution(false)}.</p>
 *
 * <p>The {@code ResultSet} returned by query will always be closed after execution, 
 * even {@code 'closeAfterExecution'} flag is set to {@code false}.</p>
 *
 * <p>Remember: parameter/column index in {@code PreparedStatement/ResultSet} starts from 1, not 0.</p>
 * 
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Example retrieving only OUT parameters
 * try (CallableQuery query = JdbcUtil.prepareCallableQuery(connection, "{call get_employee_info(?, ?, ?)}")) {
 *     query.setInt(1, 1001)  // IN parameter: employee ID
 *          .registerOutParameter(2, Types.VARCHAR)  // OUT parameter: employee name
 *          .registerOutParameter(3, Types.DECIMAL)  // OUT parameter: salary
 *          .closeAfterExecution(false);
 *
 *     Jdbc.OutParamResult outParams = query.executeAndGetOutParameters();
 *     String name = outParams.getOutParamValue(2);
 *     BigDecimal salary = outParams.getOutParamValue(3);
 * }
 *
 * // Example with result set and OUT parameters
 * try (CallableQuery query = JdbcUtil.prepareCallableQuery(connection, "{call get_department_stats(?, ?, ?)}")) {
 *     query.setString("departmentName", "Sales")
 *          .registerOutParameter("totalEmployees", Types.INTEGER)
 *          .registerOutParameter("avgSalary", Types.DECIMAL);
 *
 *     Tuple2<List<Employee>, Jdbc.OutParamResult> result =
 *         query.listAndGetOutParameters(Employee.class);
 *
 *     List<Employee> employees = result._1;
 *     int totalEmployees = result._2.getOutParamValue("totalEmployees");
 *     BigDecimal avgSalary = result._2.getOutParamValue("avgSalary");
 * }
 * }</pre>
 *
 * @see CallableStatement
 * @see AbstractQuery
 * @see PreparedStatement
 * @see ResultSet
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
@SuppressWarnings({ "java:S1192", "resource" })
public final class CallableQuery extends AbstractQuery<CallableStatement, CallableQuery> {

    final CallableStatement cstmt;
    List<Jdbc.OutParam> outParams;

    CallableQuery(final CallableStatement stmt) {
        super(stmt);
        cstmt = stmt;
    }

    /**
     * Sets the specified named parameter to SQL {@code NULL}.
     *
     * <p><b>Note:</b> You must specify the SQL type of the parameter being set to {@code null}.
     * This method is used for stored procedures that accept named parameters.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Set a VARCHAR named parameter to NULL
     * query.setNull("employeeName", java.sql.Types.VARCHAR);
     *
     * // Set an INTEGER named parameter to NULL
     * query.setNull("managerId", java.sql.Types.INTEGER);
     * }</pre>
     *
     * @param parameterName the name of the parameter to set to {@code NULL}.
     * @param sqlType the SQL type code defined in {@link java.sql.Types}.
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     * @see java.sql.Types
     */
    public CallableQuery setNull(final String parameterName, final int sqlType) throws SQLException {
        cstmt.setNull(parameterName, sqlType);

        return this;
    }

    /**
     * Sets the specified named parameter to SQL {@code NULL}. This version is used for user-defined types (UDTs)
     * or {@code REF} types, where the type name is required by the database.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Setting a user-defined STRUCT type to NULL by name
     * query.setNull("addressParam", java.sql.Types.STRUCT, "MY_ADDRESS_TYPE");
     *
     * // Setting a REF type to NULL by name
     * query.setNull("refParam", java.sql.Types.REF, "MY_REF_TYPE");
     * }</pre>
     *
     * @param parameterName the name of the parameter to set to {@code NULL}.
     * @param sqlType the SQL type code from {@link java.sql.Types} (e.g., {@code STRUCT}, {@code REF}).
     * @param typeName the fully-qualified name of the SQL user-defined type.
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     * @see java.sql.Types
     */
    public CallableQuery setNull(final String parameterName, final int sqlType, final String typeName) throws SQLException {
        cstmt.setNull(parameterName, sqlType, typeName);

        return this;
    }

    /**
     * Sets the specified named parameter to a boolean value.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setBoolean("isActive", true);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the boolean value to set
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setBoolean(final String parameterName, final boolean x) throws SQLException {
        cstmt.setBoolean(parameterName, x);

        return this;
    }

    /**
     * Sets the specified named parameter to a Boolean value.
     * If the value is {@code null}, the parameter will be set to SQL NULL.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Boolean isManager = getManagerStatus(); // might return null
     * query.setBoolean("isManager", isManager);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the Boolean value to set, or {@code null} to set SQL NULL
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setBoolean(final String parameterName, final Boolean x) throws SQLException {
        if (x == null) {
            cstmt.setNull(parameterName, java.sql.Types.BOOLEAN);
        } else {
            cstmt.setBoolean(parameterName, x);
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a byte value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setByte("statusCode", (byte) 1);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the byte value to set
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setByte(final String parameterName, final byte x) throws SQLException {
        cstmt.setByte(parameterName, x);

        return this;
    }

    /**
     * Sets the specified named parameter to a Byte value.
     * If the value is {@code null}, the parameter will be set to SQL NULL.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Byte level = getUserLevel(); // might return null
     * query.setByte("userLevel", level);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the Byte value to set, or {@code null} to set SQL NULL
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setByte(final String parameterName, final Byte x) throws SQLException {
        if (x == null) {
            cstmt.setNull(parameterName, java.sql.Types.TINYINT);
        } else {
            cstmt.setByte(parameterName, x);
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a short value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setShort("departmentId", (short) 100);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the short value to set
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setShort(final String parameterName, final short x) throws SQLException {
        cstmt.setShort(parameterName, x);

        return this;
    }

    /**
     * Sets the specified named parameter to a Short value.
     * If the value is {@code null}, the parameter will be set to SQL NULL.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Short quantity = getOrderQuantity(); // might return null
     * query.setShort("quantity", quantity);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the Short value to set, or {@code null} to set SQL NULL
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setShort(final String parameterName, final Short x) throws SQLException {
        if (x == null) {
            cstmt.setNull(parameterName, java.sql.Types.SMALLINT);
        } else {
            cstmt.setShort(parameterName, x);
        }

        return this;
    }

    /**
     * Sets the specified named parameter to an int value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setInt("employeeId", 1001);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the int value to set
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setInt(final String parameterName, final int x) throws SQLException {
        cstmt.setInt(parameterName, x);

        return this;
    }

    /**
     * Sets the specified named parameter to an Integer value.
     * If the value is {@code null}, the parameter will be set to SQL NULL.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Integer managerId = getManagerId(); // might return {@code null} for CEO
     * query.setInt("managerId", managerId);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the Integer value to set, or {@code null} to set SQL NULL
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setInt(final String parameterName, final Integer x) throws SQLException {
        if (x == null) {
            cstmt.setNull(parameterName, java.sql.Types.INTEGER);
        } else {
            cstmt.setInt(parameterName, x);
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a long value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setLong("accountNumber", 1234567890L);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the long value to set
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setLong(final String parameterName, final long x) throws SQLException {
        cstmt.setLong(parameterName, x);

        return this;
    }

    /**
     * Sets the specified named parameter to a Long value.
     * If the value is {@code null}, the parameter will be set to SQL NULL.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Long transactionId = getTransactionId(); // might return null
     * query.setLong("transactionId", transactionId);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the Long value to set, or {@code null} to set SQL NULL
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setLong(final String parameterName, final Long x) throws SQLException {
        if (x == null) {
            cstmt.setNull(parameterName, java.sql.Types.BIGINT);
        } else {
            cstmt.setLong(parameterName, x);
        }

        return this;
    }

    /**
     * Sets a BigInteger value as a long for the specified parameter.
     * The BigInteger must be within the range of a long value.
     * If the value is {@code null}, the parameter will be set to SQL NULL.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * BigInteger bigId = new BigInteger("9876543210");
     * query.setLong("bigId", bigId);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the BigInteger value to set, or {@code null} to set SQL NULL
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     * @throws ArithmeticException if the BigInteger value is outside the range of a long
     */
    public CallableQuery setLong(final String parameterName, final BigInteger x) throws SQLException {
        if (x == null) {
            cstmt.setNull(parameterName, Types.BIGINT);
        } else {
            cstmt.setLong(parameterName, x.longValueExact());
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a float value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setFloat("discountRate", 0.15f);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the float value to set
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setFloat(final String parameterName, final float x) throws SQLException {
        cstmt.setFloat(parameterName, x);

        return this;
    }

    /**
     * Sets the specified named parameter to a Float value.
     * If the value is {@code null}, the parameter will be set to SQL NULL.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Float temperature = getTemperature(); // might return null
     * query.setFloat("temperature", temperature);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the Float value to set, or {@code null} to set SQL NULL
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setFloat(final String parameterName, final Float x) throws SQLException {
        if (x == null) {
            cstmt.setNull(parameterName, java.sql.Types.FLOAT);
        } else {
            cstmt.setFloat(parameterName, x);
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a double value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setDouble("salary", 75000.50);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the double value to set
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setDouble(final String parameterName, final double x) throws SQLException {
        cstmt.setDouble(parameterName, x);

        return this;
    }

    /**
     * Sets the specified named parameter to a Double value.
     * If the value is {@code null}, the parameter will be set to SQL NULL.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Double price = getProductPrice(); // might return null
     * query.setDouble("price", price);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the Double value to set, or {@code null} to set SQL NULL
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setDouble(final String parameterName, final Double x) throws SQLException {
        if (x == null) {
            cstmt.setNull(parameterName, java.sql.Types.DOUBLE);
        } else {
            cstmt.setDouble(parameterName, x);
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a BigDecimal value.
     * This method is typically used for precise decimal values like currency amounts.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * BigDecimal amount = new BigDecimal("123.45");
     * query.setBigDecimal("totalAmount", amount);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the BigDecimal value to set, or {@code null} to set SQL NULL
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setBigDecimal(final String parameterName, final BigDecimal x) throws SQLException {
        cstmt.setBigDecimal(parameterName, x);

        return this;
    }

    /**
     * Sets a BigInteger value as a BigDecimal for the specified parameter.
     * If the value is {@code null}, the parameter will be set to SQL NULL.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * BigInteger bigValue = new BigInteger("12345678901234567890");
     * query.setBigDecimal("bigValue", bigValue);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the BigInteger value to set, or {@code null} to set SQL NULL
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setBigDecimal(final String parameterName, final BigInteger x) throws SQLException {
        if (x == null) {
            cstmt.setNull(parameterName, Types.DECIMAL);
        } else {
            cstmt.setBigDecimal(parameterName, new BigDecimal(x));
        }

        return this;
    }

    /**
     * Sets a BigInteger value as a String for the specified parameter.
     * This method converts the BigInteger to its string representation.
     * If the value is {@code null}, the parameter will be set to SQL NULL.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * BigInteger bigNumber = new BigInteger("99999999999999999999");
     * query.setBigIntegerAsString("bigNumberStr", bigNumber);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the BigInteger value to set as string, or {@code null} to set SQL NULL
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     * @see #setString(String, BigInteger)
     * @see #setBigDecimal(String, BigInteger)
     * @see #setLong(String, BigInteger)
     */
    @Beta
    public CallableQuery setBigIntegerAsString(final String parameterName, final BigInteger x) throws SQLException {
        return setString(parameterName, x);
    }

    /**
     * Sets the specified named parameter to a String value.
     *
     * <p><b>Null Handling:</b> If the {@code x} parameter is {@code null},
     * the database parameter will be set to SQL NULL.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Setting a non-null string value
     * query.setString("firstName", "John");
     *
     * // Setting NULL when value is absent
     * String middleName = getMiddleName(); // might return null
     * query.setString("middleName", middleName);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the String value to set, or {@code null} to set SQL NULL
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setString(final String parameterName, final String x) throws SQLException {
        cstmt.setString(parameterName, x);

        return this;
    }

    /**
     * Sets a CharSequence value as a String for the specified parameter.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * StringBuilder sb = new StringBuilder("Hello World");
     * query.setString("message", sb);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the CharSequence value to set, or {@code null} to set SQL NULL
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setString(final String parameterName, final CharSequence x) throws SQLException {
        return setString(parameterName, x == null ? null : x.toString());
    }

    /**
     * Sets a char value as a String for the specified parameter.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setString("grade", 'A');
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the char value to set
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setString(final String parameterName, final char x) throws SQLException {
        return setString(parameterName, String.valueOf(x));
    }

    /**
     * Sets a Character value as a String for the specified parameter.
     * If the value is {@code null}, the parameter will be set to SQL NULL.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Character initial = getMiddleInitial(); // might return null
     * query.setString("middleInitial", initial);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the Character value to set, or {@code null} to set SQL NULL
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setString(final String parameterName, final Character x) throws SQLException {
        return setString(parameterName, x == null ? (String) null : x.toString()); //NOSONAR
    }

    /**
     * Sets a BigInteger value as a String for the specified parameter.
     * The BigInteger is converted to its decimal string representation.
     * If the value is {@code null}, the parameter will be set to SQL NULL.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * BigInteger serialNumber = new BigInteger("123456789012345");
     * query.setString("serialNumber", serialNumber);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the BigInteger value to set as string, or {@code null} to set SQL NULL
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setString(final String parameterName, final BigInteger x) throws SQLException {
        if (x == null) {
            cstmt.setNull(parameterName, Types.VARCHAR);
        } else {
            cstmt.setString(parameterName, x.toString(10));
        }

        return this;
    }

    /**
     * Sets the specified named parameter to a national character string value.
     * This method is used for NCHAR, NVARCHAR, and LONGNVARCHAR parameters.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setNString("unicodeName", "名前");
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the String value to set, or {@code null} to set SQL NULL
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setNString(final String parameterName, final String x) throws SQLException {
        cstmt.setNString(parameterName, x);

        return this;
    }

    /**
     * Sets the specified named parameter to a java.sql.Date value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * java.sql.Date birthDate = java.sql.Date.valueOf("1990-01-15");
     * query.setDate("birthDate", birthDate);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the java.sql.Date value to set, or {@code null} to set SQL NULL
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setDate(final String parameterName, final java.sql.Date x) throws SQLException {
        cstmt.setDate(parameterName, x);

        return this;
    }

    /**
     * Sets a java.util.Date value as a java.sql.Date for the specified parameter.
     * The time portion of the date will be truncated.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * java.util.Date utilDate = new java.util.Date();
     * query.setDate("createdDate", utilDate);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the java.util.Date value to set, or {@code null} to set SQL NULL
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setDate(final String parameterName, final java.util.Date x) throws SQLException {
        cstmt.setDate(parameterName, x == null ? null : x instanceof java.sql.Date ? (java.sql.Date) x : new java.sql.Date(x.getTime()));

        return this;
    }

    /**
     * Sets the specified named parameter to a LocalDate value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * LocalDate today = LocalDate.now();
     * query.setDate("reportDate", today);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the LocalDate value to set, or {@code null} to set SQL NULL
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setDate(final String parameterName, final LocalDate x) throws SQLException {
        setDate(parameterName, x == null ? null : java.sql.Date.valueOf(x));

        return this;
    }

    /**
     * Sets the specified named parameter to a java.sql.Time value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * java.sql.Time startTime = java.sql.Time.valueOf("09:00:00");
     * query.setTime("startTime", startTime);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the java.sql.Time value to set, or {@code null} to set SQL NULL
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setTime(final String parameterName, final java.sql.Time x) throws SQLException {
        cstmt.setTime(parameterName, x);

        return this;
    }

    /**
     * Sets a java.util.Date value as a java.sql.Time for the specified parameter.
     * Only the time portion of the date will be used.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * java.util.Date utilTime = new java.util.Date();
     * query.setTime("checkInTime", utilTime);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the java.util.Date value to set, or {@code null} to set SQL NULL
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setTime(final String parameterName, final java.util.Date x) throws SQLException {
        cstmt.setTime(parameterName, x == null ? null : x instanceof java.sql.Time ? (java.sql.Time) x : new java.sql.Time(x.getTime()));

        return this;
    }

    /**
     * Sets the specified named parameter to a LocalTime value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * LocalTime meetingTime = LocalTime.of(14, 30);
     * query.setTime("meetingTime", meetingTime);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the LocalTime value to set, or {@code null} to set SQL NULL
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setTime(final String parameterName, final LocalTime x) throws SQLException {
        setTime(parameterName, x == null ? null : java.sql.Time.valueOf(x));

        return this;
    }

    /**
     * Sets the specified named parameter to a java.sql.Timestamp value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * java.sql.Timestamp timestamp = new java.sql.Timestamp(System.currentTimeMillis());
     * query.setTimestamp("lastModified", timestamp);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the java.sql.Timestamp value to set, or {@code null} to set SQL NULL
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setTimestamp(final String parameterName, final java.sql.Timestamp x) throws SQLException {
        cstmt.setTimestamp(parameterName, x);

        return this;
    }

    /**
     * Sets a java.util.Date value as a java.sql.Timestamp for the specified parameter.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * java.util.Date now = new java.util.Date();
     * query.setTimestamp("createdAt", now);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the java.util.Date value to set, or {@code null} to set SQL NULL
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setTimestamp(final String parameterName, final java.util.Date x) throws SQLException {
        cstmt.setTimestamp(parameterName, x == null ? null : x instanceof java.sql.Timestamp ? (java.sql.Timestamp) x : new java.sql.Timestamp(x.getTime()));

        return this;
    }

    /**
     * Sets the specified named parameter to a LocalDateTime value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * LocalDateTime eventTime = LocalDateTime.now();
     * query.setTimestamp("eventTime", eventTime);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the LocalDateTime value to set, or {@code null} to set SQL NULL
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setTimestamp(final String parameterName, final LocalDateTime x) throws SQLException {
        setTimestamp(parameterName, x == null ? null : Timestamp.valueOf(x));

        return this;
    }

    /**
     * Sets the specified named parameter to a ZonedDateTime value.
     * The ZonedDateTime is converted to an Instant and then to a Timestamp.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ZonedDateTime zonedTime = ZonedDateTime.now();
     * query.setTimestamp("scheduledTime", zonedTime);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the ZonedDateTime value to set, or {@code null} to set SQL NULL
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setTimestamp(final String parameterName, final ZonedDateTime x) throws SQLException {
        setTimestamp(parameterName, x == null ? null : Timestamp.from(x.toInstant()));

        return this;
    }

    /**
     * Sets the specified named parameter to an OffsetDateTime value.
     * The OffsetDateTime is converted to an Instant and then to a Timestamp.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OffsetDateTime offsetTime = OffsetDateTime.now();
     * query.setTimestamp("recordedTime", offsetTime);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the OffsetDateTime value to set, or {@code null} to set SQL NULL
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setTimestamp(final String parameterName, final OffsetDateTime x) throws SQLException {
        setTimestamp(parameterName, x == null ? null : Timestamp.from(x.toInstant()));

        return this;
    }

    /**
     * Sets the specified named parameter to an Instant value.
     * The Instant is converted to a Timestamp.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Instant instant = Instant.now();
     * query.setTimestamp("processedAt", instant);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the Instant value to set, or {@code null} to set SQL NULL
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setTimestamp(final String parameterName, final Instant x) throws SQLException {
        setTimestamp(parameterName, x == null ? null : Timestamp.from(x));

        return this;
    }

    /**
     * Sets a byte array for the specified parameter.
     * This method is typically used for BINARY, VARBINARY, or LONGVARBINARY data.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * byte[] imageData = loadImageData();
     * query.setBytes("profileImage", imageData);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the byte array to set, or {@code null} to set SQL NULL
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setBytes(final String parameterName, final byte[] x) throws SQLException {
        cstmt.setBytes(parameterName, x);

        return this;
    }

    /**
     * Sets an ASCII stream for the specified parameter.
     * The JDBC driver will read the data from the stream as needed until end-of-file is reached.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * InputStream asciiStream = new FileInputStream("data.txt");
     * query.setAsciiStream("textData", asciiStream);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param inputStream the {@code InputStream} object containing the ASCII parameter value
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setAsciiStream(final String parameterName, final InputStream inputStream) throws SQLException {
        cstmt.setAsciiStream(parameterName, inputStream);

        return this;
    }

    /**
     * Sets an ASCII stream for the specified parameter with a specified length.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * InputStream asciiStream = new FileInputStream("data.txt");
     * long fileLength = new File("data.txt").length();
     * query.setAsciiStream("textData", asciiStream, fileLength);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param inputStream the {@code InputStream} object containing the ASCII parameter value
     * @param length the number of bytes in the stream
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setAsciiStream(final String parameterName, final InputStream inputStream, final long length) throws SQLException {
        cstmt.setAsciiStream(parameterName, inputStream, length);

        return this;
    }

    /**
     * Sets a binary stream for the specified parameter.
     * The JDBC driver will read the data from the stream as needed until end-of-file is reached.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * InputStream binaryStream = new FileInputStream("image.jpg");
     * query.setBinaryStream("imageData", binaryStream);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param inputStream the {@code InputStream} object containing the binary parameter value
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setBinaryStream(final String parameterName, final InputStream inputStream) throws SQLException {
        cstmt.setBinaryStream(parameterName, inputStream);

        return this;
    }

    /**
     * Sets a binary stream for the specified parameter with a specified length.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * InputStream binaryStream = new FileInputStream("document.pdf");
     * long fileLength = new File("document.pdf").length();
     * query.setBinaryStream("documentData", binaryStream, fileLength);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param inputStream the {@code InputStream} object containing the binary parameter value
     * @param length the number of bytes in the stream
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setBinaryStream(final String parameterName, final InputStream inputStream, final long length) throws SQLException {
        cstmt.setBinaryStream(parameterName, inputStream, length);

        return this;
    }

    /**
     * Sets a character stream for the specified parameter.
     * The JDBC driver will read the data from the stream as needed until end-of-file is reached.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Reader reader = new FileReader("article.txt");
     * query.setCharacterStream("articleContent", reader);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param reader the {@code Reader} object containing the Unicode data
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setCharacterStream(final String parameterName, final Reader reader) throws SQLException {
        cstmt.setCharacterStream(parameterName, reader);

        return this;
    }

    /**
     * Sets a character stream for the specified parameter with a specified length.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Reader reader = new StringReader("Large text content...");
     * query.setCharacterStream("description", reader, 1000);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param reader the {@code Reader} object containing the Unicode data
     * @param length the number of characters in the stream
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setCharacterStream(final String parameterName, final Reader reader, final long length) throws SQLException {
        cstmt.setCharacterStream(parameterName, reader, length);

        return this;
    }

    /**
     * Sets a national character stream for the specified parameter.
     * This method is used for NCHAR, NVARCHAR, and LONGNVARCHAR columns.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Reader reader = new StringReader("Unicode text content");
     * query.setNCharacterStream("unicodeContent", reader);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param reader the {@code Reader} object containing the Unicode data
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setNCharacterStream(final String parameterName, final Reader reader) throws SQLException {
        cstmt.setNCharacterStream(parameterName, reader);

        return this;
    }

    /**
     * Sets a national character stream for the specified parameter with a specified length.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Reader reader = new StringReader("Unicode text with special characters");
     * query.setNCharacterStream("unicodeText", reader, 100);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param reader the {@code Reader} object containing the Unicode data
     * @param length the number of characters in the stream
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setNCharacterStream(final String parameterName, final Reader reader, final long length) throws SQLException {
        cstmt.setNCharacterStream(parameterName, reader, length);

        return this;
    }

    /**
     * Sets a Blob object for the specified parameter.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Blob blob = connection.createBlob();
     * blob.setBytes(1, imageBytes);
     * query.setBlob("photo", blob);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x a Blob object that maps to a SQL BLOB value
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setBlob(final String parameterName, final java.sql.Blob x) throws SQLException {
        cstmt.setBlob(parameterName, x);

        return this;
    }

    /**
     * Sets a Blob value using an InputStream for the specified parameter.
     * The data will be read from the stream as needed until end-of-file is reached.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * InputStream imageStream = new FileInputStream("photo.jpg");
     * query.setBlob("photo", imageStream);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param inputStream the {@code InputStream} object containing the data to set
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setBlob(final String parameterName, final InputStream inputStream) throws SQLException {
        cstmt.setBlob(parameterName, inputStream);

        return this;
    }

    /**
     * Sets a Blob value using an InputStream with a specified length for the specified parameter.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * File file = new File("document.pdf");
     * InputStream stream = new FileInputStream(file);
     * query.setBlob("document", stream, file.length());
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param inputStream the {@code InputStream} object containing the data to set
     * @param length the number of bytes in the parameter data
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setBlob(final String parameterName, final InputStream inputStream, final long length) throws SQLException {
        cstmt.setBlob(parameterName, inputStream, length);

        return this;
    }

    /**
     * Sets a Clob object for the specified parameter.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Clob clob = connection.createClob();
     * clob.setString(1, "Large text content...");
     * query.setClob("content", clob);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x a Clob object that maps to a SQL CLOB value
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setClob(final String parameterName, final java.sql.Clob x) throws SQLException {
        cstmt.setClob(parameterName, x);

        return this;
    }

    /**
     * Sets a Clob value using a Reader for the specified parameter.
     * The data will be read from the Reader as needed until end-of-file is reached.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Reader reader = new FileReader("article.txt");
     * query.setClob("articleText", reader);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param reader the {@code Reader} object containing the data to set
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setClob(final String parameterName, final Reader reader) throws SQLException {
        cstmt.setClob(parameterName, reader);

        return this;
    }

    /**
     * Sets a Clob value using a Reader with a specified length for the specified parameter.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String content = "Long article content...";
     * Reader reader = new StringReader(content);
     * query.setClob("article", reader, content.length());
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param reader the {@code Reader} object containing the data to set
     * @param length the number of characters in the parameter data
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setClob(final String parameterName, final Reader reader, final long length) throws SQLException {
        cstmt.setClob(parameterName, reader, length);

        return this;
    }

    /**
     * Sets an NClob object for the specified parameter.
     * NClob is used for storing national character large objects.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * NClob nclob = connection.createNClob();
     * nclob.setString(1, "Unicode large text");
     * query.setNClob("unicodeContent", nclob);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x an NClob object that maps to a SQL NCLOB value
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setNClob(final String parameterName, final java.sql.NClob x) throws SQLException {
        cstmt.setNClob(parameterName, x);

        return this;
    }

    /**
     * Sets an NClob value using a Reader for the specified parameter.
     * The data will be read from the Reader as needed until end-of-file is reached.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Reader reader = new StringReader("Unicode text content");
     * query.setNClob("unicodeText", reader);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param reader the {@code Reader} object containing the Unicode data to set
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setNClob(final String parameterName, final Reader reader) throws SQLException {
        cstmt.setNClob(parameterName, reader);

        return this;
    }

    /**
     * Sets an NClob value using a Reader with a specified length for the specified parameter.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String unicodeContent = "Unicode content with special characters";
     * Reader reader = new StringReader(unicodeContent);
     * query.setNClob("content", reader, unicodeContent.length());
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param reader the {@code Reader} object containing the Unicode data to set
     * @param length the number of characters in the parameter data
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setNClob(final String parameterName, final Reader reader, final long length) throws SQLException {
        cstmt.setNClob(parameterName, reader, length);

        return this;
    }

    /**
     * Sets the specified named parameter to a URL value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * URL website = new URL("https://www.example.com");
     * query.setURL("websiteUrl", website);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the java.net.URL object to be set
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setURL(final String parameterName, final URL x) throws SQLException {
        cstmt.setURL(parameterName, x);

        return this;
    }

    /**
     * Sets an SQLXML object for the specified parameter.
     * SQLXML is used for storing XML data in the database.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * SQLXML xmlData = connection.createSQLXML();
     * xmlData.setString("<root><data>value</data></root>");
     * query.setSQLXML("xmlContent", xmlData);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x an SQLXML object that maps to a SQL XML value
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setSQLXML(final String parameterName, final java.sql.SQLXML x) throws SQLException {
        cstmt.setSQLXML(parameterName, x);

        return this;
    }

    /**
     * Sets a RowId object for the specified parameter.
     * RowId represents the address of a row in a database table.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * RowId rowId = resultSet.getRowId("ROWID");
     * query.setRowId("targetRowId", rowId);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the RowId object to be set
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setRowId(final String parameterName, final java.sql.RowId x) throws SQLException {
        cstmt.setRowId(parameterName, x);

        return this;
    }

    /**
     * Sets the specified named parameter to an object value.
     * The JDBC driver will attempt to map the object to the appropriate SQL type.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setObject("value", 123);  // Integer
     * query.setObject("name", "John");  // String
     * query.setObject("data", customObject);  // Custom object
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the object containing the input parameter value
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setObject(final String parameterName, final Object x) throws SQLException {
        if (x == null) {
            cstmt.setObject(parameterName, x);
        } else {
            N.typeOf(x.getClass()).set(cstmt, parameterName, x);
        }

        return this;
    }

    /**
     * Sets an object value for the specified parameter with a specified SQL type.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setObject("amount", 123.45, Types.DECIMAL);
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the object containing the input parameter value
     * @param sqlType the SQL type code defined in {@link java.sql.Types}
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     * @see java.sql.Types
     */
    public CallableQuery setObject(final String parameterName, final Object x, final int sqlType) throws SQLException {
        cstmt.setObject(parameterName, x, sqlType);

        return this;
    }

    /**
     * Sets an object value for the specified parameter with a specified SQL type and scale.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setObject("price", 123.456789, Types.DECIMAL, 2);  // Scale to 2 decimal places
     * }</pre>
     *
     * @param parameterName the name of the parameter
     * @param x the object containing the input parameter value
     * @param sqlType the SQL type code defined in {@link java.sql.Types}
     * @param scaleOrLength for {@code java.sql.Types.DECIMAL} or {@code java.sql.Types.NUMERIC} types,
     *                      this is the number of digits after the decimal point. For all other types,
     *                      this value will be ignored
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     * @see java.sql.Types
     */
    public CallableQuery setObject(final String parameterName, final Object x, final int sqlType, final int scaleOrLength) throws SQLException {
        cstmt.setObject(parameterName, x, sqlType, scaleOrLength);

        return this;
    }

    /**
     * Sets multiple parameters from a Map where keys are parameter names and values are parameter values.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Map<String, Object> params = new HashMap<>();
     * params.put("employeeId", 1001);
     * params.put("department", "Sales");
     * params.put("startDate", LocalDate.now());
     * query.setParameters(params);
     * }</pre>
     *
     * @param parameters a map containing parameter names as keys and their corresponding values
     * @return this CallableQuery instance for method chaining
     * @throws IllegalArgumentException if the parameters map is {@code null}
     * @throws SQLException if a database access error occurs or any parameter name is not valid
     */
    public CallableQuery setParameters(final Map<String, ?> parameters) throws IllegalArgumentException, SQLException {
        checkArgNotNull(parameters, cs.parameters);

        for (final Map.Entry<String, ?> entry : parameters.entrySet()) {
            setObject(entry.getKey(), entry.getValue());
        }

        return this;
    }

    /**
     * Sets multiple parameters for this CallableQuery by extracting values from an entity object.
     * This method uses reflection to retrieve property values from the entity based on the specified
     * parameter names, making it convenient for mapping entity properties to stored procedure parameters.
     * 
     * <p>The method uses the bean information of the entity class to extract property values.
     * Each parameter name in the list should correspond to a property name in the entity object.
     * The appropriate database type is automatically determined based on the property type.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Employee employee = new Employee();
     * employee.setId(1001);
     * employee.setName("John Doe");
     * employee.setDepartment("Sales");
     * 
     * List<String> paramNames = Arrays.asList("id", "name", "department");
     * query.setParameters(employee, paramNames)
     *      .execute();
     * }</pre>
     *
     * @param entity the entity object containing the parameter values. Must not be {@code null}.
     * @param parameterNames a list of parameter names corresponding to properties in the entity.
     *                       Each name should match a property name in the entity class.
     * @return this CallableQuery instance for method chaining
     * @throws IllegalArgumentException if the entity or parameterNames is null
     * @throws SQLException if a database access error occurs or if a parameter name doesn't
     *                      correspond to a valid property in the entity
     * @see Beans#getPropNameList(Class)
     * @see Beans#getPropNames(Class, Collection)
     * @see JdbcUtil#getNamedParameters(String)
     */
    public CallableQuery setParameters(final Object entity, final List<String> parameterNames) throws IllegalArgumentException, SQLException {
        checkArgNotNull(entity, cs.entity);
        checkArgNotNull(parameterNames, cs.parameterNames);

        final Class<?> cls = entity.getClass();
        final BeanInfo entityInfo = ParserUtil.getBeanInfo(cls);
        PropInfo propInfo = null;

        for (final String parameterName : parameterNames) {
            propInfo = entityInfo.getPropInfo(parameterName);
            propInfo.dbType.set(cstmt, parameterName, propInfo.getPropValue(entity));
        }

        return this;
    }

    /**
     * Registers a parameter as an OUT parameter with the specified SQL type.
     * This method is used to indicate that a parameter in the stored procedure is an OUTPUT
     * parameter that will return a value after execution.
     * 
     * <p>OUT parameters must be registered before the statement is executed. The SQL type
     * specified should match the type of data the stored procedure will return for this parameter.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setInt(1, 100)  // IN parameter
     *      .registerOutParameter(2, Types.VARCHAR)  // OUT parameter
     *      .registerOutParameter(3, Types.INTEGER)  // OUT parameter
     *      .execute();
     * 
     * String result = query.getString(2);
     * int count = query.getInt(3);
     * }</pre>
     *
     * @param parameterIndex the index of the parameter (starts from 1, not 0)
     * @param sqlType the SQL type code as defined in {@link java.sql.Types}
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs or if parameterIndex is invalid
     * @see java.sql.CallableStatement#registerOutParameter(int, int)
     * @see java.sql.Types
     */
    public CallableQuery registerOutParameter(final int parameterIndex, final int sqlType) throws SQLException {
        cstmt.registerOutParameter(parameterIndex, sqlType);

        addOutParameters(new Jdbc.OutParam(parameterIndex, null, sqlType, null, -1));

        return this;
    }

    /**
     * Registers a parameter as an OUT parameter with the specified SQL type and scale.
     * This method is typically used for numeric types (like DECIMAL or NUMERIC) where you need
     * to specify the number of digits after the decimal point.
     * 
     * <p>The scale parameter is particularly important for fixed-point numeric types to ensure
     * proper precision when retrieving the OUT parameter value.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setInt(1, 1001)
     *      .registerOutParameter(2, Types.DECIMAL, 2)  // For monetary values
     *      .execute();
     * 
     * BigDecimal price = query.getBigDecimal(2);  // e.g., 123.45
     * }</pre>
     *
     * @param parameterIndex the index of the parameter (starts from 1, not 0)
     * @param sqlType the SQL type code as defined in {@link java.sql.Types}
     * @param scale the number of digits to the right of the decimal point.
     *              Used for DECIMAL and NUMERIC types.
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs or if parameterIndex is invalid
     * @see java.sql.CallableStatement#registerOutParameter(int, int, int)
     * @see java.sql.Types#DECIMAL
     * @see java.sql.Types#NUMERIC
     */
    public CallableQuery registerOutParameter(final int parameterIndex, final int sqlType, final int scale) throws SQLException {
        cstmt.registerOutParameter(parameterIndex, sqlType, scale);

        addOutParameters(new Jdbc.OutParam(parameterIndex, null, sqlType, null, scale));

        return this;
    }

    /**
     * Registers a parameter as an OUT parameter with a user-defined SQL type name.
     * This method is used for database-specific types, user-defined types (UDTs), or
     * when you need to specify the exact SQL type name for proper type mapping.
     * 
     * <p>The typeName parameter should be the fully-qualified SQL type name, which may
     * include the schema name if required by the database.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // For a user-defined type
     * query.registerOutParameter(1, Types.STRUCT, "SCHEMA.ADDRESS_TYPE")
     *      .execute();
     * 
     * // For database-specific types
     * query.registerOutParameter(2, Types.OTHER, "XMLTYPE")
     *      .execute();
     * }</pre>
     *
     * @param parameterIndex the index of the parameter (starts from 1, not 0)
     * @param sqlType the SQL type code as defined in {@link java.sql.Types}
     * @param typeName the fully-qualified SQL type name. For user-defined types,
     *                 this should include the schema name if required.
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs or if parameterIndex is invalid
     * @see java.sql.CallableStatement#registerOutParameter(int, int, String)
     * @see java.sql.Types#STRUCT
     * @see java.sql.Types#DISTINCT
     * @see java.sql.Types#JAVA_OBJECT
     */
    public CallableQuery registerOutParameter(final int parameterIndex, final int sqlType, final String typeName) throws SQLException {
        cstmt.registerOutParameter(parameterIndex, sqlType, typeName);

        addOutParameters(new Jdbc.OutParam(parameterIndex, null, sqlType, typeName, -1));

        return this;
    }

    /**
     * Registers a named parameter as an OUT parameter with the specified SQL type.
     * This method is used with stored procedures that have named parameters, providing
     * better code readability compared to index-based parameter registration.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setString("employeeName", "John Doe")
     *      .registerOutParameter("employeeId", Types.INTEGER)
     *      .registerOutParameter("salary", Types.DECIMAL)
     *      .execute();
     * 
     * int id = query.getInt("employeeId");
     * BigDecimal salary = query.getBigDecimal("salary");
     * }</pre>
     *
     * @param parameterName the name of the parameter as defined in the stored procedure
     * @param sqlType the SQL type code as defined in {@link java.sql.Types}
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs or if parameterName is invalid
     * @see java.sql.CallableStatement#registerOutParameter(String, int)
     * @see java.sql.Types
     */
    public CallableQuery registerOutParameter(final String parameterName, final int sqlType) throws SQLException {
        cstmt.registerOutParameter(parameterName, sqlType);

        addOutParameters(new Jdbc.OutParam(-1, parameterName, sqlType, null, -1));

        return this;
    }

    /**
     * Registers a named parameter as an OUT parameter with the specified SQL type and scale.
     * This method combines the benefits of named parameters with scale specification for
     * numeric types that require precision control.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setInt("productId", 100)
     *      .registerOutParameter("price", Types.DECIMAL, 2)
     *      .registerOutParameter("taxAmount", Types.DECIMAL, 4)
     *      .execute();
     * 
     * BigDecimal price = query.getBigDecimal("price");
     * BigDecimal tax = query.getBigDecimal("taxAmount");
     * }</pre>
     *
     * @param parameterName the name of the parameter as defined in the stored procedure
     * @param sqlType the SQL type code as defined in {@link java.sql.Types}
     * @param scale the number of digits to the right of the decimal point
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs or if parameterName is invalid
     * @see java.sql.CallableStatement#registerOutParameter(String, int, int)
     * @see java.sql.Types#DECIMAL
     * @see java.sql.Types#NUMERIC
     */
    public CallableQuery registerOutParameter(final String parameterName, final int sqlType, final int scale) throws SQLException {
        cstmt.registerOutParameter(parameterName, sqlType, scale);

        addOutParameters(new Jdbc.OutParam(-1, parameterName, sqlType, null, scale));

        return this;
    }

    /**
     * Registers a named parameter as an OUT parameter with a user-defined SQL type name.
     * This method is ideal for working with complex database types using named parameters
     * for better code maintainability.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // For Oracle object types
     * query.registerOutParameter("address", Types.STRUCT, "HR.ADDRESS_TYPE")
     *      .execute();
     * 
     * // For SQL Server XML type
     * query.registerOutParameter("xmlData", Types.SQLXML, "XML")
     *      .execute();
     * }</pre>
     *
     * @param parameterName the name of the parameter as defined in the stored procedure
     * @param sqlType the SQL type code as defined in {@link java.sql.Types}
     * @param typeName the fully-qualified SQL type name
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs or if parameterName is invalid
     * @see java.sql.CallableStatement#registerOutParameter(String, int, String)
     * @see java.sql.Types#STRUCT
     * @see java.sql.Types#DISTINCT
     */
    public CallableQuery registerOutParameter(final String parameterName, final int sqlType, final String typeName) throws SQLException {
        cstmt.registerOutParameter(parameterName, sqlType, typeName);

        addOutParameters(new Jdbc.OutParam(-1, parameterName, sqlType, typeName, -1));

        return this;
    }

    /**
     * Registers a parameter as an OUT parameter using the JDBC 4.2 {@link SQLType} interface.
     * This method provides type-safe parameter registration using the standard SQL type enumeration.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * 
     * query.setString(1, "input_value")
     *      .registerOutParameter(2, JDBCType.VARCHAR)
     *      .registerOutParameter(3, JDBCType.TIMESTAMP)
     *      .execute();
     * }</pre>
     *
     * @param parameterIndex the index of the parameter (starts from 1, not 0)
     * @param sqlType the SQL type from {@link java.sql.JDBCType} or vendor-specific implementation
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs or if parameterIndex is invalid
     * @see java.sql.CallableStatement#registerOutParameter(int, java.sql.SQLType)
     * @see java.sql.JDBCType
     */
    public CallableQuery registerOutParameter(final int parameterIndex, final SQLType sqlType) throws SQLException {
        cstmt.registerOutParameter(parameterIndex, sqlType);

        addOutParameters(new Jdbc.OutParam(parameterIndex, null, sqlType.getVendorTypeNumber(), null, -1));

        return this;
    }

    /**
     * Registers a parameter as an OUT parameter using {@link SQLType} with scale specification.
     * This method combines the type safety of SQLType with scale control for numeric types.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * 
     * query.registerOutParameter(1, JDBCType.DECIMAL, 2)  // For money
     *      .registerOutParameter(2, JDBCType.NUMERIC, 4)   // For precise calculations
     *      .execute();
     * }</pre>
     *
     * @param parameterIndex the index of the parameter (starts from 1, not 0)
     * @param sqlType the SQL type from {@link java.sql.JDBCType} or vendor-specific implementation
     * @param scale the number of digits to the right of the decimal point
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs or if parameterIndex is invalid
     * @see java.sql.CallableStatement#registerOutParameter(int, java.sql.SQLType, int)
     * @see java.sql.JDBCType#DECIMAL
     * @see java.sql.JDBCType#NUMERIC
     */
    public CallableQuery registerOutParameter(final int parameterIndex, final SQLType sqlType, final int scale) throws SQLException {
        cstmt.registerOutParameter(parameterIndex, sqlType, scale);

        addOutParameters(new Jdbc.OutParam(parameterIndex, null, sqlType.getVendorTypeNumber(), null, scale));

        return this;
    }

    /**
     * Registers a parameter as an OUT parameter using {@link SQLType} with a user-defined type name.
     * This method provides type-safe registration for complex or vendor-specific SQL types.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * 
     * query.registerOutParameter(1, JDBCType.STRUCT, "SCHEMA.CUSTOM_TYPE")
     *      .registerOutParameter(2, JDBCType.ARRAY, "VARCHAR_ARRAY")
     *      .execute();
     * }</pre>
     *
     * @param parameterIndex the index of the parameter (starts from 1, not 0)
     * @param sqlType the SQL type from {@link java.sql.JDBCType} or vendor-specific implementation
     * @param typeName the fully-qualified SQL type name
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs or if parameterIndex is invalid
     * @see java.sql.CallableStatement#registerOutParameter(int, java.sql.SQLType, String)
     * @see java.sql.JDBCType#STRUCT
     * @see java.sql.JDBCType#ARRAY
     */
    public CallableQuery registerOutParameter(final int parameterIndex, final SQLType sqlType, final String typeName) throws SQLException {
        cstmt.registerOutParameter(parameterIndex, sqlType, typeName);

        addOutParameters(new Jdbc.OutParam(parameterIndex, null, sqlType.getVendorTypeNumber(), typeName, -1));

        return this;
    }

    /**
     * Registers a named parameter as an OUT parameter using the JDBC 4.2 {@link SQLType} interface.
     * This method combines the readability of named parameters with type-safe SQL type specification.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * 
     * query.setString("inputParam", "value")
     *      .registerOutParameter("resultCode", JDBCType.INTEGER)
     *      .registerOutParameter("message", JDBCType.VARCHAR)
     *      .execute();
     * }</pre>
     *
     * @param parameterName the name of the parameter as defined in the stored procedure
     * @param sqlType the SQL type from {@link java.sql.JDBCType} or vendor-specific implementation
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs or if parameterName is invalid
     * @see java.sql.CallableStatement#registerOutParameter(String, java.sql.SQLType)
     * @see java.sql.JDBCType
     */
    public CallableQuery registerOutParameter(final String parameterName, final SQLType sqlType) throws SQLException {
        cstmt.registerOutParameter(parameterName, sqlType);

        addOutParameters(new Jdbc.OutParam(-1, parameterName, sqlType.getVendorTypeNumber(), null, -1));

        return this;
    }

    /**
     * Registers a named parameter as an OUT parameter using {@link SQLType} with scale specification.
     * This method is ideal for numeric types that require both name-based access and precision control.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * 
     * query.setInt("orderId", 12345)
     *      .registerOutParameter("totalAmount", JDBCType.DECIMAL, 2)
     *      .registerOutParameter("taxRate", JDBCType.NUMERIC, 4)
     *      .execute();
     * }</pre>
     *
     * @param parameterName the name of the parameter as defined in the stored procedure
     * @param sqlType the SQL type from {@link java.sql.JDBCType} or vendor-specific implementation
     * @param scale the number of digits to the right of the decimal point
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs or if parameterName is invalid
     * @see java.sql.CallableStatement#registerOutParameter(String, java.sql.SQLType, int)
     * @see java.sql.JDBCType#DECIMAL
     */
    public CallableQuery registerOutParameter(final String parameterName, final SQLType sqlType, final int scale) throws SQLException {
        cstmt.registerOutParameter(parameterName, sqlType, scale);

        addOutParameters(new Jdbc.OutParam(-1, parameterName, sqlType.getVendorTypeNumber(), null, scale));

        return this;
    }

    /**
     * Registers a named parameter as an OUT parameter using {@link SQLType} with a user-defined type name.
     * This method provides the most flexible parameter registration, combining named parameters,
     * type-safe SQL types, and custom type names.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * 
     * query.setInt("customerId", 100)
     *      .registerOutParameter("customerData", JDBCType.STRUCT, "CUSTOMER_TYPE")
     *      .registerOutParameter("orderHistory", JDBCType.ARRAY, "ORDER_ARRAY")
     *      .execute();
     * }</pre>
     *
     * @param parameterName the name of the parameter as defined in the stored procedure
     * @param sqlType the SQL type from {@link java.sql.JDBCType} or vendor-specific implementation
     * @param typeName the fully-qualified SQL type name
     * @return this CallableQuery instance for method chaining
     * @throws SQLException if a database access error occurs or if parameterName is invalid
     * @see java.sql.CallableStatement#registerOutParameter(String, java.sql.SQLType, String)
     * @see java.sql.JDBCType#STRUCT
     */
    public CallableQuery registerOutParameter(final String parameterName, final SQLType sqlType, final String typeName) throws SQLException {
        cstmt.registerOutParameter(parameterName, sqlType, typeName);

        addOutParameters(new Jdbc.OutParam(-1, parameterName, sqlType.getVendorTypeNumber(), typeName, -1));

        return this;
    }

    /**
     * Registers multiple OUT parameters using a functional interface approach.
     * This method allows for more complex registration logic and is useful when you need
     * to register multiple parameters based on dynamic conditions.
     * 
     * <p>The provided {@link Jdbc.ParametersSetter} receives this CallableQuery instance,
     * allowing it to call multiple {@code registerOutParameter} methods. If an exception
     * occurs during registration, the statement is automatically closed.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.registerOutParameters(q -> {
     *     q.registerOutParameter(1, Types.INTEGER);
     *     q.registerOutParameter(2, Types.VARCHAR);
     *     if (includeDetails) {
     *         q.registerOutParameter(3, Types.CLOB);
     *     }
     * }).execute();
     * }</pre>
     *
     * @param register the {@link Jdbc.ParametersSetter} that will register the OUT parameters.
     *                 Must not be {@code null}.
     * @return this CallableQuery instance for method chaining
     * @throws IllegalArgumentException if register is null
     * @throws SQLException if a database access error occurs during parameter registration
     */
    public CallableQuery registerOutParameters(final Jdbc.ParametersSetter<? super CallableQuery> register) throws IllegalArgumentException, SQLException {
        checkArgNotNull(register, cs.register);

        boolean noException = false;

        try {
            register.accept(this);

            noException = true;
        } finally {
            if (!noException) {
                close();
            }
        }

        return this;
    }

    /**
     * Registers multiple OUT parameters using a bi-functional interface and an additional context object.
     * This method provides flexibility for dynamic registration of OUT parameters where the
     * registration logic depends on external data or configuration provided by the {@code parameter} object.
     *
     * <p>The {@link Jdbc.BiParametersSetter} receives both this {@code CallableQuery} instance
     * (allowing it to call methods like {@code registerOutParameter()}) and the supplied {@code parameter}
     * (providing context for dynamic logic).
     * If an exception occurs during registration, the statement is automatically closed.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * class ReportConfig {
     *     boolean includeTotal;
     *     boolean includeAvg;
     *     // getters
     * }
     * ReportConfig config = new ReportConfig(true, false);
     *
     * query.registerOutParameters(config, (q, cfg) -> {
     *     int paramIndex = 1;
     *     q.registerOutParameter(paramIndex++, Types.VARCHAR); // always return a status message
     *
     *     if (cfg.includeTotal()) {
     *         q.registerOutParameter(paramIndex++, Types.DECIMAL);
     *     }
     *     if (cfg.includeAvg()) {
     *         q.registerOutParameter(paramIndex++, Types.DECIMAL, 2);
     *     }
     * }).execute();
     * }</pre>
     *
     * @param <T> the type of the additional parameter object.
     * @param parameter the context object to be passed to the {@code BiParametersSetter}.
     * @param register the {@link Jdbc.BiParametersSetter} that defines the registration logic. Must not be {@code null}.
     * @return this CallableQuery instance for method chaining
     * @throws IllegalArgumentException if {@code register} is {@code null}.
     * @throws SQLException if a database access error occurs during parameter registration.
     */
    public <T> CallableQuery registerOutParameters(final T parameter, final Jdbc.BiParametersSetter<? super CallableQuery, ? super T> register)
            throws SQLException {
        checkArgNotNull(register, cs.register);

        boolean noException = false;

        try {
            register.accept(this, parameter);

            noException = true;
        } finally {
            if (!noException) {
                close();
            }
        }

        return this;
    }

    /**
     * Adds an OUT parameter to the internal tracking list.
     * This is an internal method used by all the public registerOutParameter methods.
     *
     * @param outParameter the OUT parameter metadata to track
     */
    private void addOutParameters(final Jdbc.OutParam outParameter) {
        if (outParams == null) {
            outParams = new ArrayList<>();
        }

        outParams.add(outParameter);
    }

    /**
     * Executes the query and returns the first result set if available.
     * This method handles the complexity of stored procedures that may return multiple
     * result sets, update counts, or both. It iterates through all results until it
     * finds the first result set.
     * 
     * <p>The fetch direction is set to FETCH_FORWARD if not explicitly set by the user.</p>
     *
     * @return the first {@link ResultSet} if available, or {@code null} if the stored procedure
     *         doesn't return any result sets
     * @throws SQLException if a database access error occurs
     */
    @Override
    protected ResultSet executeQuery() throws SQLException {
        if (!isFetchDirectionSet) {
            cstmt.setFetchDirection(ResultSet.FETCH_FORWARD);
        }

        boolean ret = JdbcUtil.execute(cstmt);
        int updateCount = cstmt.getUpdateCount();

        while (ret || updateCount != -1) {
            if (ret) {
                return cstmt.getResultSet();
            } else {
                ret = cstmt.getMoreResults();
                updateCount = cstmt.getUpdateCount();
            }
        }

        return null;
    }

    /**
     * Executes the stored procedure and applies the provided function to the executed CallableStatement.
     * This method provides direct access to the CallableStatement after execution, allowing for
     * custom result processing logic.
     * 
     * <p>The statement will be closed after the function is applied unless
     * {@link #closeAfterExecution(boolean)} has been called with {@code false}.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String result = query.executeThenApply(stmt -> {
     *     // Custom processing of the executed statement
     *     return stmt.getString(1);
     * });
     * }</pre>
     *
     * @param <R> the type of the result returned by the function
     * @param getter the function to apply to the executed CallableStatement
     * @return the result of applying the function
     * @throws SQLException if a database access error occurs or the function throws an exception
     * @see JdbcUtil#getOutParameters(CallableStatement, List)
     * @see JdbcUtil#streamAllResultSets(Statement, Jdbc.ResultExtractor)
     * @see JdbcUtil#streamAllResultSets(Statement, Jdbc.BiResultExtractor)
     */
    @Override
    public <R> R executeThenApply(final Throwables.Function<? super CallableStatement, ? extends R, SQLException> getter) throws SQLException { //NOSONAR
        return super.executeThenApply(getter);
    }

    /**
     * Executes the stored procedure and applies the provided bi-function to the executed CallableStatement
     * and a boolean indicating whether the first result is a ResultSet.
     * 
     * <p>This method provides more detailed control over result processing by indicating the type
     * of the first result returned by the stored procedure.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Object result = query.executeThenApply((stmt, isResultSet) -> {
     *     if (isResultSet) {
     *         ResultSet rs = stmt.getResultSet();
     *         // Process result set
     *     } else {
     *         int updateCount = stmt.getUpdateCount();
     *         // Process update count
     *     }
     *     return processedResult;
     * });
     * }</pre>
     *
     * @param <R> the type of the result returned by the function
     * @param getter the bi-function to apply. The first parameter is the executed CallableStatement,
     *               the second parameter is {@code true} if the first result is a ResultSet, {@code false} otherwise
     * @return the result of applying the bi-function
     * @throws SQLException if a database access error occurs or the function throws an exception
     * @see JdbcUtil#getOutParameters(CallableStatement, List)
     * @see JdbcUtil#streamAllResultSets(Statement, Jdbc.ResultExtractor)
     */
    @Override
    public <R> R executeThenApply(final Throwables.BiFunction<? super CallableStatement, Boolean, ? extends R, SQLException> getter) throws SQLException { //NOSONAR
        return super.executeThenApply(getter);
    }

    /**
     * Executes the stored procedure and applies the provided tri-function to process the results
     * with full access to the CallableStatement, OUT parameters, and result type information.
     * 
     * <p>This method provides the most comprehensive access to stored procedure results, including
     * the executed statement, registered OUT parameters, and information about the first result type.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Map<String, Object> results = query.executeThenApply(
     *     (stmt, outParams, isResultSet) -> {
     *         Map<String, Object> map = new HashMap<>();
     *         
     *         // Process OUT parameters
     *         for (Jdbc.OutParam param : outParams) {
     *             if (param.getParameterName() != null) {
     *                 map.put(param.getParameterName(), 
     *                         stmt.getObject(param.getParameterName()));
     *             }
     *         }
     *         
     *         // Process result set if available
     *         if (isResultSet) {
     *             ResultSet rs = stmt.getResultSet();
     *             // Add result set data to map
     *         }
     *         
     *         return map;
     *     }
     * );
     * }</pre>
     *
     * @param <R> the type of the result returned by the function
     * @param getter the tri-function to apply. Parameters are:
     *               1. The executed CallableStatement
     *               2. List of registered OUT parameters
     *               3. Boolean indicating if the first result is a ResultSet
     * @return the result of applying the tri-function
     * @throws IllegalArgumentException if getter is null
     * @throws SQLException if a database access error occurs or the function throws an exception
     * @see Jdbc.OutParam
     * @see JdbcUtil#getOutParameters(CallableStatement, List)
     */
    public <R> R executeThenApply(final Throwables.TriFunction<? super CallableStatement, List<Jdbc.OutParam>, Boolean, ? extends R, SQLException> getter)
            throws SQLException {
        checkArgNotNull(getter, cs.getter);
        assertNotClosed();

        try {
            final boolean isFirstResultSet = JdbcUtil.execute(cstmt);
            outParams = outParams == null ? N.emptyList() : outParams;

            return getter.apply(cstmt, outParams, isFirstResultSet);
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the stored procedure and applies the provided consumer to the executed CallableStatement.
     * This method is useful when you need to perform side effects with the statement but don't need
     * to return a value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.executeThenAccept(stmt -> {
     *     // Log execution details
     *     logger.info("Warnings: " + stmt.getWarnings());
     *     
     *     // Process multiple result sets
     *     boolean hasResults = true;
     *     while (hasResults) {
     *         if (stmt.getResultSet() != null) {
     *             // Process result set
     *         }
     *         hasResults = stmt.getMoreResults();
     *     }
     * });
     * }</pre>
     *
     * @param consumer the consumer to apply to the executed CallableStatement
     * @throws SQLException if a database access error occurs or the consumer throws an exception
     * @see JdbcUtil#getOutParameters(CallableStatement, List)
     * @see JdbcUtil#streamAllResultSets(Statement, Jdbc.ResultExtractor)
     */
    @Override
    public void executeThenAccept(final Throwables.Consumer<? super CallableStatement, SQLException> consumer) throws SQLException { //NOSONAR
        super.executeThenAccept(consumer);
    }

    /**
     * Executes the stored procedure and applies the provided bi-consumer to the executed CallableStatement
     * and a boolean indicating the result type.
     * 
     * <p>This method is similar to {@link #executeThenApply(Throwables.BiFunction)} but is used
     * for side effects rather than returning a value.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.executeThenAccept((stmt, isResultSet) -> {
     *     if (isResultSet) {
     *         ResultSet rs = stmt.getResultSet();
     *         while (rs.next()) {
     *             System.out.println(rs.getString(1));
     *         }
     *     } else {
     *         System.out.println("Updated rows: " + stmt.getUpdateCount());
     *     }
     * });
     * }</pre>
     *
     * @param consumer the bi-consumer to apply. The first parameter is the executed CallableStatement,
     *                 the second parameter is {@code true} if the first result is a ResultSet
     * @throws SQLException if a database access error occurs or the consumer throws an exception
     * @see JdbcUtil#getOutParameters(CallableStatement, List)
     */
    @Override
    public void executeThenAccept(final Throwables.BiConsumer<? super CallableStatement, Boolean, SQLException> consumer) throws SQLException { //NOSONAR
        super.executeThenAccept(consumer);
    }

    /**
     * Executes the stored procedure and applies the provided tri-consumer for processing with
     * full access to all execution results.
     * 
     * <p>This method provides comprehensive access to the execution results for side-effect
     * operations without returning a value.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.executeThenAccept((stmt, outParams, isResultSet) -> {
     *     // Log OUT parameters
     *     for (Jdbc.OutParam param : outParams) {
     *         if (param.getParameterName() != null) {
     *             logger.info(param.getParameterName() + ": " + 
     *                        stmt.getObject(param.getParameterName()));
     *         }
     *     }
     *     
     *     // Process first result
     *     if (isResultSet) {
     *         ResultSet rs = stmt.getResultSet();
     *         // Write results to file or external system
     *     }
     * });
     * }</pre>
     *
     * @param consumer the tri-consumer to apply. Parameters are:
     *                 1. The executed CallableStatement
     *                 2. List of registered OUT parameters  
     *                 3. Boolean indicating if the first result is a ResultSet
     * @throws IllegalArgumentException if consumer is null
     * @throws SQLException if a database access error occurs or the consumer throws an exception
     * @see Jdbc.OutParam
     */
    public void executeThenAccept(final Throwables.TriConsumer<? super CallableStatement, List<Jdbc.OutParam>, Boolean, SQLException> consumer)
            throws SQLException {
        checkArgNotNull(consumer, cs.consumer);
        assertNotClosed();

        try {
            final boolean isFirstResultSet = JdbcUtil.execute(cstmt);
            outParams = outParams == null ? N.emptyList() : outParams;

            consumer.accept(cstmt, outParams, isFirstResultSet);
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the stored procedure and retrieves all OUT parameter values.
     * This method is used when you only need the OUT parameters and don't need to process
     * any result sets returned by the procedure.
     * 
     * <p>The returned {@link Jdbc.OutParamResult} provides convenient methods to retrieve
     * OUT parameter values by index or name with appropriate type conversion.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * CallableQuery query = JdbcUtil.prepareCallableQuery(connection, "{call calculate_totals(?, ?, ?)}");
     * query.setInt(1, 2023)
     *      .registerOutParameter(2, Types.DECIMAL)
     *      .registerOutParameter(3, Types.INTEGER);
     *
     * Jdbc.OutParamResult outParams = query.executeAndGetOutParameters();
     *
     * BigDecimal totalAmount = outParams.getOutParamValue(2);
     * int recordCount = outParams.getOutParamValue(3);
     * }</pre>
     *
     * @return a {@link Jdbc.OutParamResult} containing all OUT parameter values
     * @throws IllegalStateException if this CallableQuery is closed
     * @throws SQLException if a database access error occurs
     * @see Jdbc.OutParamResult
     */
    public Jdbc.OutParamResult executeAndGetOutParameters() throws IllegalStateException, SQLException {
        assertNotClosed();

        try {
            JdbcUtil.execute(cstmt);

            return JdbcUtil.getOutParameters(cstmt, outParams);
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the stored procedure and returns both the first result set (as a Dataset) and OUT parameters.
     * This is a convenience method that uses the default Dataset result extractor.
     * 
     * <p>The result set is fully loaded into memory as a {@link Dataset}, which provides
     * a convenient API for working with tabular data.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Tuple2<Dataset, Jdbc.OutParamResult> result = query.queryAndGetOutParameters();
     *
     * Dataset dataset = result._1;
     * Jdbc.OutParamResult outParams = result._2;
     *
     * // Process the data set
     * dataset.forEach(row -> {
     *     System.out.println(row.getString("name") + ": " + row.getInt("value"));
     * });
     *
     * // Get OUT parameters
     * int totalCount = outParams.getOutParamValue("totalCount");
     * }</pre>
     *
     * @return a {@link Tuple2} containing the Dataset (first element) and OUT parameters (second element)
     * @throws SQLException if a database access error occurs
     * @see #queryAndGetOutParameters(Jdbc.ResultExtractor)
     * @see Dataset
     */
    public Tuple2<Dataset, Jdbc.OutParamResult> queryAndGetOutParameters() throws SQLException {
        return queryAndGetOutParameters(Jdbc.ResultExtractor.TO_DATA_SET);
    }

    /**
     * Executes the stored procedure and returns both the first result set and OUT parameters,
     * using a custom ResultExtractor to process the result set.
     * 
     * <p>This method provides flexibility in how the result set is processed and converted
     * to the desired type. The ResultExtractor has full access to the ResultSet for custom processing.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Extract to a custom summary object
     * Tuple2<Summary, Jdbc.OutParamResult> result = query.queryAndGetOutParameters(
     *     rs -> {
     *         Summary summary = new Summary();
     *         while (rs.next()) {
     *             summary.addItem(rs.getString("name"), rs.getDouble("value"));
     *         }
     *         return summary;
     *     }
     * );
     *
     * Summary summary = result._1;
     * Jdbc.OutParamResult outParams = result._2;
     * }</pre>
     *
     * @param <R> the type of object the result set will be converted to
     * @param resultExtractor the {@link Jdbc.ResultExtractor} to process the result set
     * @return a {@link Tuple2} containing the extracted result (first element) and OUT parameters (second element).
     *         The first element may be {@code null} if no result set is returned.
     * @throws IllegalArgumentException if resultExtractor is null
     * @throws IllegalStateException if this CallableQuery is closed
     * @throws SQLException if a database access error occurs
     * @see Jdbc.ResultExtractor
     */
    public <R> Tuple2<R, Jdbc.OutParamResult> queryAndGetOutParameters(final Jdbc.ResultExtractor<? extends R> resultExtractor)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(resultExtractor, cs.resultExtractor);
        assertNotClosed();

        try {
            R result = null;
            final ResultSet rs = executeQuery();

            if (rs != null) {
                result = JdbcUtil.extractAndCloseResultSet(rs, resultExtractor);
            }

            return Tuple.of(result, JdbcUtil.getOutParameters(cstmt, outParams));
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the stored procedure and returns both the first result set and OUT parameters,
     * using a BiResultExtractor that has access to both the ResultSet and column labels.
     * 
     * <p>The BiResultExtractor receives the column labels as a second parameter, which can be
     * useful for dynamic result processing or validation.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Tuple2<Map<String, List<Object>>, Jdbc.OutParamResult> result = 
     *     query.queryAndGetOutParameters((rs, columnLabels) -> {
     *         Map<String, List<Object>> columnData = new HashMap<>();
     *         for (String label : columnLabels) {
     *             columnData.put(label, new ArrayList<>());
     *         }
     *         
     *         while (rs.next()) {
     *             for (String label : columnLabels) {
     *                 columnData.get(label).add(rs.getObject(label));
     *             }
     *         }
     *         return columnData;
     *     });
     * }</pre>
     *
     * @param <R> the type of object the result set will be converted to
     * @param resultExtractor the {@link Jdbc.BiResultExtractor} to process the result set
     * @return a {@link Tuple2} containing the extracted result and OUT parameters
     * @throws IllegalArgumentException if resultExtractor is null
     * @throws IllegalStateException if this CallableQuery is closed
     * @throws SQLException if a database access error occurs
     * @see Jdbc.BiResultExtractor
     */
    public <R> Tuple2<R, Jdbc.OutParamResult> queryAndGetOutParameters(final Jdbc.BiResultExtractor<? extends R> resultExtractor)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(resultExtractor, cs.resultExtractor);
        assertNotClosed();

        try {
            R result = null;
            final ResultSet rs = executeQuery();

            if (rs != null) {
                result = JdbcUtil.extractAndCloseResultSet(rs, resultExtractor);
            }

            return Tuple.of(result, JdbcUtil.getOutParameters(cstmt, outParams));
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the stored procedure and returns all result sets as Datasets along with OUT parameters.
     * This method is useful for procedures that return multiple result sets.
     * 
     * <p>Each result set is converted to a {@link Dataset} and collected in a list. The method
     * processes all available result sets, not just the first one.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Tuple2<List<Dataset>, Jdbc.OutParamResult> results =
     *     query.queryAllResultsetsAndGetOutParameters();
     *
     * List<Dataset> datasets = results._1;
     * Jdbc.OutParamResult outParams = results._2;
     *
     * // Process each result set
     * for (int i = 0; i < datasets.size(); i++) {
     *     System.out.println("Result set " + (i + 1) + ":");
     *     datasets.get(i).println();
     * }
     *
     * // Get OUT parameters
     * String status = outParams.getOutParamValue("status");
     * }</pre>
     *
     * @return a {@link Tuple2} containing a list of Datasets (one per result set) and OUT parameters
     * @throws SQLException if a database access error occurs
     * @see #queryAllResultsetsAndGetOutParameters(Jdbc.ResultExtractor)
     */
    public Tuple2<List<Dataset>, Jdbc.OutParamResult> queryAllResultsetsAndGetOutParameters() throws SQLException {
        return queryAllResultsetsAndGetOutParameters(ResultExtractor.TO_DATA_SET);
    }

    /**
     * Executes the stored procedure and returns all result sets along with OUT parameters,
     * using a custom ResultExtractor to process each result set.
     * 
     * <p>This method processes all result sets returned by the stored procedure, applying
     * the same ResultExtractor to each one. The extractor should not save or return the
     * ResultSet itself as it will be closed after processing.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Extract row counts from multiple result sets
     * Tuple2<List<Integer>, Jdbc.OutParamResult> results = 
     *     query.queryAllResultsetsAndGetOutParameters(rs -> {
     *         int count = 0;
     *         while (rs.next()) {
     *             count++;
     *         }
     *         return count;
     *     });
     * 
     * List<Integer> rowCounts = results._1;
     * System.out.println("Result sets returned: " + rowCounts.size());
     * for (int i = 0; i < rowCounts.size(); i++) {
     *     System.out.println("Result set " + (i + 1) + " rows: " + rowCounts.get(i));
     * }
     * }</pre>
     *
     * @param <R> the type each result set will be converted to
     * @param resultExtractor the {@link Jdbc.ResultExtractor} to process each result set.
     *                        Must not save or return the ResultSet itself.
     * @return a {@link Tuple2} containing a list of extracted results and OUT parameters
     * @throws IllegalArgumentException if resultExtractor is null
     * @throws IllegalStateException if this CallableQuery is closed
     * @throws SQLException if a database access error occurs
     * @see Jdbc.ResultExtractor
     */
    public <R> Tuple2<List<R>, Jdbc.OutParamResult> queryAllResultsetsAndGetOutParameters(final Jdbc.ResultExtractor<? extends R> resultExtractor)
            throws SQLException {
        checkArgNotNull(resultExtractor, cs.resultExtractor);
        assertNotClosed();

        ObjIteratorEx<ResultSet> iter = null;

        try {
            final boolean isResultSet = JdbcUtil.execute(cstmt);

            iter = JdbcUtil.iterateAllResultSets(cstmt, isResultSet);

            final List<R> resultList = new ArrayList<>();

            while (iter.hasNext()) {
                resultList.add(JdbcUtil.extractAndCloseResultSet(iter.next(), resultExtractor));
            }

            return Tuple.of(resultList, JdbcUtil.getOutParameters(cstmt, outParams));
        } finally {
            try {
                if (iter != null) {
                    iter.close();
                }
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }
    }

    /**
     * Executes the stored procedure and returns all result sets along with any OUT parameters.
     * This method is designed for stored procedures that return multiple result sets, allowing
     * you to process each result set with a custom extractor while also retrieving OUT parameters.
     * 
     * <p>Each result set is processed sequentially using the provided {@code BiResultExtractor},
     * which has access to both the {@code ResultSet} and column labels. The result sets are
     * automatically closed after extraction.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Stored procedure that returns customer orders and order items as separate result sets
     * try (CallableQuery query = JdbcUtil.prepareCallableQuery(connection, "{call get_customer_order_details(?, ?)}")) {
     *     query.setInt(1, customerId)
     *          .registerOutParameter(2, Types.DECIMAL);  // total amount
     *
     *     Tuple2<List<Dataset>, Jdbc.OutParamResult> result =
     *         query.queryAllResultsetsAndGetOutParameters(
     *             (rs, columnLabels) -> JdbcUtil.extractResultSetToDataset(rs)
     *         );
     *
     *     Dataset orders = result._1.get(0);      // First result set
     *     Dataset orderItems = result._1.get(1);  // Second result set
     *     BigDecimal totalAmount = result._2.getOutParamValue(2);
     * }
     * }</pre>
     *
     * @param <R> The type of object that each result set will be converted to
     * @param resultExtractor the {@code BiResultExtractor} used to convert each result set to type R.
     *                        Must not be {@code null}. The extractor receives the ResultSet and column labels.
     *                        Warning: Do not save or return the ResultSet reference as it will be closed.
     * @return A {@code Tuple2} containing:
     *         <ul>
     *           <li>First element: List of extracted results (one per result set)</li>
     *           <li>Second element: {@code Jdbc.OutParamResult} containing all OUT parameters</li>
     *         </ul>
     * @throws IllegalArgumentException if {@code resultExtractor} is {@code null}
     * @throws IllegalStateException if this query has already been closed
     * @throws SQLException if a database access error occurs, the stored procedure fails,
     *                      or the result extraction fails
     * @see #query2ResultsetsAndGetOutParameters(BiResultExtractor, BiResultExtractor)
     * @see #listAllResultsetsAndGetOutParameters(Class)
     */
    public <R> Tuple2<List<R>, Jdbc.OutParamResult> queryAllResultsetsAndGetOutParameters(final Jdbc.BiResultExtractor<? extends R> resultExtractor)
            throws SQLException {
        checkArgNotNull(resultExtractor, cs.resultExtractor);
        assertNotClosed();

        ObjIteratorEx<ResultSet> iter = null;

        try {
            final boolean isResultSet = JdbcUtil.execute(cstmt);

            iter = JdbcUtil.iterateAllResultSets(cstmt, isResultSet);

            final List<R> resultList = new ArrayList<>();

            while (iter.hasNext()) {
                resultList.add(JdbcUtil.extractAndCloseResultSet(iter.next(), resultExtractor));
            }

            return Tuple.of(resultList, JdbcUtil.getOutParameters(cstmt, outParams));
        } finally {
            try {
                if (iter != null) {
                    iter.close();
                }
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }
    }

    /**
     * Executes the stored procedure and returns the first two result sets along with OUT parameters.
     * This method is optimized for stored procedures that return exactly two result sets,
     * allowing different extractors for each result set for type-safe processing.
     * 
     * <p>If the stored procedure returns fewer than two result sets, the corresponding
     * result values will be {@code null}. If more than two result sets are returned,
     * only the first two are processed.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Procedure returns summary and detail records as separate result sets
     * try (CallableQuery query = JdbcUtil.prepareCallableQuery(connection, "{call get_sales_report(?, ?, ?)}")) {
     *     query.setDate(1, startDate)
     *          .setDate(2, endDate)
     *          .registerOutParameter(3, Types.DECIMAL);  // grand total
     *
     *     Tuple3<SalesSummary, List<SalesDetail>, Jdbc.OutParamResult> result =
     *         query.query2ResultsetsAndGetOutParameters(
     *             (rs, labels) -> extractSalesSummary(rs),     // First result set
     *             (rs, labels) -> extractSalesDetailList(rs)   // Second result set
     *         );
     *
     *     SalesSummary summary = result._1;
     *     List<SalesDetail> details = result._2;
     *     BigDecimal grandTotal = result._3.getOutParamValue(3);
     * }
     * }</pre>
     *
     * @param <R1> The type for the first result set
     * @param <R2> The type for the second result set
     * @param resultExtractor1 the extractor for the first result set. Must not be {@code null}.
     * @param resultExtractor2 the extractor for the second result set. Must not be {@code null}.
     * @return A {@code Tuple3} containing:
     *         <ul>
     *           <li>First element: Extracted result from the first result set (or null)</li>
     *           <li>Second element: Extracted result from the second result set (or null)</li>
     *           <li>Third element: {@code Jdbc.OutParamResult} containing all OUT parameters</li>
     *         </ul>
     * @throws IllegalArgumentException if either {@code resultExtractor1} or {@code resultExtractor2} is {@code null}
     * @throws IllegalStateException if this query has already been closed
     * @throws SQLException if a database access error occurs or the stored procedure fails
     * @see #query3ResultsetsAndGetOutParameters(BiResultExtractor, BiResultExtractor, BiResultExtractor)
     * @see #queryAllResultsetsAndGetOutParameters(BiResultExtractor)
     */
    @Beta
    public <R1, R2> Tuple3<R1, R2, Jdbc.OutParamResult> query2ResultsetsAndGetOutParameters(final Jdbc.BiResultExtractor<? extends R1> resultExtractor1,
            final Jdbc.BiResultExtractor<? extends R2> resultExtractor2) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(resultExtractor1, cs.resultExtractor1);
        checkArgNotNull(resultExtractor2, cs.resultExtractor2);
        assertNotClosed();

        ObjIteratorEx<ResultSet> iter = null;

        try {
            final boolean isResultSet = JdbcUtil.execute(cstmt);

            iter = JdbcUtil.iterateAllResultSets(cstmt, isResultSet);

            R1 result1 = null;
            R2 result2 = null;

            if (iter.hasNext()) {
                result1 = JdbcUtil.extractAndCloseResultSet(iter.next(), resultExtractor1);
            }

            if (iter.hasNext()) {
                result2 = JdbcUtil.extractAndCloseResultSet(iter.next(), resultExtractor2);
            }

            return Tuple.of(result1, result2, JdbcUtil.getOutParameters(cstmt, outParams));
        } finally {
            try {
                if (iter != null) {
                    iter.close();
                }
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }
    }

    /**
     * Executes the stored procedure and returns the first three result sets along with OUT parameters.
     * This method is optimized for stored procedures that return exactly three result sets,
     * with type-safe extraction for each result set using different extractors.
     * 
     * <p>If the stored procedure returns fewer than three result sets, the corresponding
     * result values will be {@code null}. If more than three result sets are returned,
     * only the first three are processed.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Complex report procedure with multiple result sets
     * try (CallableQuery query = JdbcUtil.prepareCallableQuery(connection, "{call generate_quarterly_report(?, ?)}")) {
     *     query.setInt(1, year)
     *          .setInt(2, quarter);
     *     
     *     Tuple4<Summary, List<Revenue>, List<Expense>, Jdbc.OutParamResult> result = 
     *         query.query3ResultsetsAndGetOutParameters(
     *             (rs, labels) -> extractSummary(rs),        // Summary data
     *             (rs, labels) -> extractRevenueList(rs),    // Revenue details
     *             (rs, labels) -> extractExpenseList(rs)     // Expense details
     *         );
     *     
     *     Summary summary = result._1;
     *     List<Revenue> revenues = result._2;
     *     List<Expense> expenses = result._3;
     *     // Process the report data...
     * }
     * }</pre>
     *
     * @param <R1> The type for the first result set
     * @param <R2> The type for the second result set
     * @param <R3> The type for the third result set
     * @param resultExtractor1 the extractor for the first result set. Must not be {@code null}.
     * @param resultExtractor2 the extractor for the second result set. Must not be {@code null}.
     * @param resultExtractor3 the extractor for the third result set. Must not be {@code null}.
     * @return A {@code Tuple4} containing:
     *         <ul>
     *           <li>First element: Extracted result from the first result set (or null)</li>
     *           <li>Second element: Extracted result from the second result set (or null)</li>
     *           <li>Third element: Extracted result from the third result set (or null)</li>
     *           <li>Fourth element: {@code Jdbc.OutParamResult} containing all OUT parameters</li>
     *         </ul>
     * @throws IllegalArgumentException if any of the result extractors is {@code null}
     * @throws IllegalStateException if this query has already been closed
     * @throws SQLException if a database access error occurs or the stored procedure fails
     * @see #query2ResultsetsAndGetOutParameters(BiResultExtractor, BiResultExtractor)
     * @see #queryAllResultsetsAndGetOutParameters(BiResultExtractor)
     */
    @Beta
    public <R1, R2, R3> Tuple4<R1, R2, R3, Jdbc.OutParamResult> query3ResultsetsAndGetOutParameters(final Jdbc.BiResultExtractor<? extends R1> resultExtractor1,
            final Jdbc.BiResultExtractor<? extends R2> resultExtractor2, final Jdbc.BiResultExtractor<? extends R3> resultExtractor3)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(resultExtractor1, cs.resultExtractor1);
        checkArgNotNull(resultExtractor2, cs.resultExtractor2);
        checkArgNotNull(resultExtractor3, cs.resultExtractor3);
        assertNotClosed();

        ObjIteratorEx<ResultSet> iter = null;

        try {
            final boolean isResultSet = JdbcUtil.execute(cstmt);

            iter = JdbcUtil.iterateAllResultSets(cstmt, isResultSet);

            R1 result1 = null;
            R2 result2 = null;
            R3 result3 = null;

            if (iter.hasNext()) {
                result1 = JdbcUtil.extractAndCloseResultSet(iter.next(), resultExtractor1);
            }

            if (iter.hasNext()) {
                result2 = JdbcUtil.extractAndCloseResultSet(iter.next(), resultExtractor2);
            }

            if (iter.hasNext()) {
                result3 = JdbcUtil.extractAndCloseResultSet(iter.next(), resultExtractor3);
            }

            return Tuple.of(result1, result2, result3, JdbcUtil.getOutParameters(cstmt, outParams));
        } finally {
            try {
                if (iter != null) {
                    iter.close();
                }
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }
    }

    /**
     * Executes the stored procedure and returns the first result set as a list of objects
     * along with any OUT parameters. This is a convenience method that automatically maps
     * each row to the specified target type using reflection.
     * 
     * <p>The mapping uses the following rules:</p>
     * <ul>
     *   <li>Column names are matched to property names (case-insensitive)</li>
     *   <li>Automatic type conversion is performed where possible</li>
     *   <li>Properties annotated with {@code @Transient} are ignored</li>
     *   <li>Column name mapping can be customized using {@code @Column} annotation</li>
     * </ul>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Simple stored procedure returning employees
     * try (CallableQuery query = JdbcUtil.prepareCallableQuery(connection, "{call find_employees_by_dept(?, ?)}")) {
     *     query.setString(1, "Engineering")
     *          .registerOutParameter(2, Types.INTEGER);  // employee count
     *     
     *     Tuple2<List<Employee>, Jdbc.OutParamResult> result = 
     *         query.listAndGetOutParameters(Employee.class);
     *     
     *     List<Employee> employees = result._1;
     *     int count = result._2.getOutParamValue(2);
     *     
     *     employees.forEach(emp -> System.out.println(emp.getName()));
     *     System.out.println("Total employees: " + count);
     * }
     * }</pre>
     *
     * @param <T> The target type for row mapping
     * @param targetType the class of type T to map each row to. Must not be {@code null}.
     *                   The class must have a default constructor.
     * @return A {@code Tuple2} containing:
     *         <ul>
     *           <li>First element: List of mapped objects from the result set</li>
     *           <li>Second element: {@code Jdbc.OutParamResult} containing all OUT parameters</li>
     *         </ul>
     * @throws IllegalArgumentException if {@code targetType} is {@code null}
     * @throws SQLException if a database access error occurs, the stored procedure fails,
     *                      or the mapping fails
     * @see #listAndGetOutParameters(RowMapper)
     * @see #listAndGetOutParameters(BiRowMapper)
     */
    public <T> Tuple2<List<T>, Jdbc.OutParamResult> listAndGetOutParameters(final Class<? extends T> targetType) throws IllegalArgumentException, SQLException {
        checkArgNotNull(targetType, cs.targetType);

        return listAndGetOutParameters(Jdbc.BiRowMapper.to(targetType));
    }

    /**
     * Executes the stored procedure and returns the first result set as a list of objects
     * along with any OUT parameters. Each row is mapped using the provided {@code RowMapper},
     * giving you full control over the mapping process.
     * 
     * <p>The {@code RowMapper} receives the {@code ResultSet} positioned at each row and
     * should extract the data to create an instance of type T. This method is useful when
     * you need custom mapping logic that cannot be achieved with automatic mapping.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Custom mapping for complex types
     * try (CallableQuery query = JdbcUtil.prepareCallableQuery(connection, "{call get_product_inventory(?, ?)}")) {
     *     query.setString(1, warehouseCode)
     *          .registerOutParameter(2, Types.DECIMAL);  // total value
     *     
     *     Tuple2<List<ProductInfo>, Jdbc.OutParamResult> result = 
     *         query.listAndGetOutParameters(rs -> {
     *             ProductInfo info = new ProductInfo();
     *             info.setId(rs.getLong("product_id"));
     *             info.setName(rs.getString("product_name"));
     *             info.setQuantity(rs.getInt("quantity"));
     *             info.setLastUpdated(rs.getTimestamp("last_updated").toInstant());
     *             return info;
     *         });
     *     
     *     List<ProductInfo> products = result._1;
     *     BigDecimal totalValue = result._2.getOutParamValue(2);
     * }
     * }</pre>
     *
     * @param <T> The target type for row mapping
     * @param rowMapper the {@code RowMapper} to convert each row to type T. Must not be {@code null}.
     *                  The mapper is called once per row with the ResultSet positioned at that row.
     * @return A {@code Tuple2} containing:
     *         <ul>
     *           <li>First element: List of mapped objects from the result set</li>
     *           <li>Second element: {@code Jdbc.OutParamResult} containing all OUT parameters</li>
     *         </ul>
     * @throws IllegalArgumentException if {@code rowMapper} is {@code null}
     * @throws IllegalStateException if this query has already been closed
     * @throws SQLException if a database access error occurs, the stored procedure fails,
     *                      or the row mapper throws an exception
     * @see #listAndGetOutParameters(Class)
     * @see #listAndGetOutParameters(RowFilter, RowMapper)
     */
    public <T> Tuple2<List<T>, Jdbc.OutParamResult> listAndGetOutParameters(final Jdbc.RowMapper<? extends T> rowMapper)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowMapper, cs.rowMapper);
        assertNotClosed();

        try {
            final List<T> result = new ArrayList<>();
            final ResultSet rs = executeQuery();

            if (rs != null) {
                while (rs.next()) {
                    result.add(rowMapper.apply(rs));
                }
            }

            return Tuple.of(result, JdbcUtil.getOutParameters(cstmt, outParams));
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the stored procedure and returns a filtered result set as a list along with OUT parameters.
     * This method allows you to filter rows before mapping, which can improve performance
     * when you only need a subset of the returned data.
     * 
     * <p>The {@code RowFilter} is applied before the {@code RowMapper}, so rows that don't
     * match the filter criteria are skipped entirely, avoiding unnecessary object creation.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Filter and map only active accounts with balance > 1000
     * try (CallableQuery query = JdbcUtil.prepareCallableQuery(connection, "{call get_all_accounts(?)}")) {
     *     query.registerOutParameter(1, Types.INTEGER);  // total count
     *     
     *     Tuple2<List<Account>, Jdbc.OutParamResult> result = 
     *         query.listAndGetOutParameters(
     *             rs -> rs.getBoolean("is_active") && rs.getBigDecimal("balance").compareTo(new BigDecimal("1000")) > 0,
     *             rs -> new Account(rs.getLong("id"), rs.getString("name"), rs.getBigDecimal("balance"))
     *         );
     *     
     *     List<Account> premiumAccounts = result._1;
     *     int totalAccounts = result._2.getOutParamValue(1);
     *     
     *     System.out.println("Premium accounts: " + premiumAccounts.size() + " of " + totalAccounts);
     * }
     * }</pre>
     *
     * @param <T> The target type for row mapping
     * @param rowFilter the {@code RowFilter} to test each row. Must not be {@code null}.
     *                  Only rows where the filter returns {@code true} are mapped.
     * @param rowMapper the {@code RowMapper} to convert filtered rows to type T. Must not be {@code null}.
     * @return A {@code Tuple2} containing:
     *         <ul>
     *           <li>First element: List of mapped objects from filtered rows</li>
     *           <li>Second element: {@code Jdbc.OutParamResult} containing all OUT parameters</li>
     *         </ul>
     * @throws IllegalArgumentException if {@code rowFilter} or {@code rowMapper} is {@code null}
     * @throws IllegalStateException if this query has already been closed
     * @throws SQLException if a database access error occurs or the stored procedure fails
     * @see #listAndGetOutParameters(BiRowFilter, BiRowMapper)
     */
    public <T> Tuple2<List<T>, Jdbc.OutParamResult> listAndGetOutParameters(final Jdbc.RowFilter rowFilter, final Jdbc.RowMapper<? extends T> rowMapper)
            throws SQLException {
        checkArgNotNull(rowFilter, cs.rowFilter);
        checkArgNotNull(rowMapper, cs.rowMapper);
        assertNotClosed();

        try {
            final List<T> result = new ArrayList<>();
            final ResultSet rs = executeQuery();

            if (rs != null) {
                while (rs.next()) {
                    if (rowFilter.test(rs)) {
                        result.add(rowMapper.apply(rs));
                    }
                }
            }

            return Tuple.of(result, JdbcUtil.getOutParameters(cstmt, outParams));
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the stored procedure and returns the first result set as a list along with OUT parameters.
     * Uses a {@code BiRowMapper} that receives both the {@code ResultSet} and column labels,
     * providing more context for complex mapping scenarios.
     * 
     * <p>The column labels list is retrieved once and reused for all rows, making this method
     * efficient when you need column metadata for mapping decisions.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Dynamic mapping based on column presence
     * try (CallableQuery query = JdbcUtil.prepareCallableQuery(connection, "{call get_customer_data(?, ?)}")) {
     *     query.setInt(1, customerId)
     *          .registerOutParameter(2, Types.VARCHAR);  // status
     *     
     *     Tuple2<List<Map<String, Object>>, Jdbc.OutParamResult> result = 
     *         query.listAndGetOutParameters((rs, columnLabels) -> {
     *             Map<String, Object> row = new HashMap<>();
     *             for (String label : columnLabels) {
     *                 row.put(label, rs.getObject(label));
     *             }
     *             return row;
     *         });
     *     
     *     List<Map<String, Object>> data = result._1;
     *     String status = result._2.getOutParamValue(2);
     * }
     * }</pre>
     *
     * @param <T> The target type for row mapping
     * @param rowMapper the {@code BiRowMapper} that receives ResultSet and column labels. Must not be {@code null}.
     * @return A {@code Tuple2} containing:
     *         <ul>
     *           <li>First element: List of mapped objects from the result set</li>
     *           <li>Second element: {@code Jdbc.OutParamResult} containing all OUT parameters</li>
     *         </ul>
     * @throws IllegalArgumentException if {@code rowMapper} is {@code null}
     * @throws IllegalStateException if this query has already been closed
     * @throws SQLException if a database access error occurs or the stored procedure fails
     * @see #listAndGetOutParameters(RowMapper)
     * @see #listAndGetOutParameters(BiRowFilter, BiRowMapper)
     */
    public <T> Tuple2<List<T>, Jdbc.OutParamResult> listAndGetOutParameters(final Jdbc.BiRowMapper<? extends T> rowMapper)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowMapper, cs.rowMapper);
        assertNotClosed();

        try {
            final List<T> result = new ArrayList<>();
            final ResultSet rs = executeQuery();

            if (rs != null) {
                final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

                while (rs.next()) {
                    result.add(rowMapper.apply(rs, columnLabels));
                }
            }

            return Tuple.of(result, JdbcUtil.getOutParameters(cstmt, outParams));
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the stored procedure and returns a filtered result set as a list along with OUT parameters.
     * Uses both a {@code BiRowFilter} and {@code BiRowMapper} that receive the {@code ResultSet}
     * and column labels, allowing for sophisticated filtering and mapping logic.
     * 
     * <p>This method is ideal for complex scenarios where both filtering and mapping decisions
     * depend on column metadata or when you need to handle dynamic result set structures.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Filter based on dynamic columns and map accordingly
     * try (CallableQuery query = JdbcUtil.prepareCallableQuery(connection, "{call search_products(?, ?, ?)}")) {
     *     query.setString(1, searchTerm)
     *          .setInt(2, maxResults)
     *          .registerOutParameter(3, Types.INTEGER);  // total matches
     *     
     *     Tuple2<List<Product>, Jdbc.OutParamResult> result = 
     *         query.listAndGetOutParameters(
     *             (rs, labels) -> {
     *                 // Only include products with a discount if the column exists
     *                 if (labels.contains("discount_percent")) {
     *                     return rs.getDouble("discount_percent") > 0;
     *                 }
     *                 return true;
     *             },
     *             (rs, labels) -> {
     *                 Product p = new Product();
     *                 p.setId(rs.getLong("id"));
     *                 p.setName(rs.getString("name"));
     *                 if (labels.contains("discount_percent")) {
     *                     p.setDiscountPercent(rs.getDouble("discount_percent"));
     *                 }
     *                 return p;
     *             }
     *         );
     *     
     *     List<Product> discountedProducts = result._1;
     *     int totalMatches = result._2.getOutParamValue(3);
     * }
     * }</pre>
     *
     * @param <T> The target type for row mapping
     * @param rowFilter the {@code BiRowFilter} that receives ResultSet and column labels. Must not be {@code null}.
     * @param rowMapper the {@code BiRowMapper} that receives ResultSet and column labels. Must not be {@code null}.
     * @return A {@code Tuple2} containing:
     *         <ul>
     *           <li>First element: List of mapped objects from filtered rows</li>
     *           <li>Second element: {@code Jdbc.OutParamResult} containing all OUT parameters</li>
     *         </ul>
     * @throws IllegalArgumentException if {@code rowFilter} or {@code rowMapper} is {@code null}
     * @throws IllegalStateException if this query has already been closed
     * @throws SQLException if a database access error occurs or the stored procedure fails
     * @see #listAndGetOutParameters(RowFilter, RowMapper)
     */
    public <T> Tuple2<List<T>, Jdbc.OutParamResult> listAndGetOutParameters(final Jdbc.BiRowFilter rowFilter, final Jdbc.BiRowMapper<? extends T> rowMapper)
            throws SQLException {
        checkArgNotNull(rowFilter, cs.rowFilter);
        checkArgNotNull(rowMapper, cs.rowMapper);
        assertNotClosed();

        try {
            final List<T> result = new ArrayList<>();
            final ResultSet rs = executeQuery();

            if (rs != null) {
                final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

                while (rs.next()) {
                    if (rowFilter.test(rs, columnLabels)) {
                        result.add(rowMapper.apply(rs, columnLabels));
                    }
                }
            }

            return Tuple.of(result, JdbcUtil.getOutParameters(cstmt, outParams));
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the stored procedure and returns all result sets as lists along with OUT parameters.
     * Each result set is automatically mapped to the specified target type using reflection.
     * This is the simplest way to handle stored procedures that return multiple result sets
     * of the same type.
     * 
     * <p>Each result set is processed independently and added to the returned list in the order
     * they are returned by the stored procedure. Empty result sets will result in empty lists.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Stored procedure that returns multiple employee groups
     * try (CallableQuery query = JdbcUtil.prepareCallableQuery(connection, "{call get_employees_by_categories(?)}")) {
     *     query.registerOutParameter(1, Types.INTEGER);  // total count
     *     
     *     Tuple2<List<List<Employee>>, Jdbc.OutParamResult> result = 
     *         query.listAllResultsetsAndGetOutParameters(Employee.class);
     *     
     *     List<Employee> managers = result._1.get(0);     // First result set
     *     List<Employee> developers = result._1.get(1);   // Second result set
     *     List<Employee> interns = result._1.get(2);      // Third result set
     *     
     *     int totalCount = result._2.getOutParamValue(1);
     *     System.out.println("Total employees: " + totalCount);
     * }
     * }</pre>
     *
     * @param <T> The target type for row mapping across all result sets
     * @param targetType the class to map each row to. Must not be {@code null}.
     *                   Applied to all result sets.
     * @return A {@code Tuple2} containing:
     *         <ul>
     *           <li>First element: List of lists, one per result set</li>
     *           <li>Second element: {@code Jdbc.OutParamResult} containing all OUT parameters</li>
     *         </ul>
     * @throws IllegalArgumentException if {@code targetType} is {@code null}
     * @throws IllegalStateException if this query has already been closed
     * @throws SQLException if a database access error occurs or the stored procedure fails
     * @see #listAllResultsetsAndGetOutParameters(RowMapper)
     * @see #queryAllResultsetsAndGetOutParameters(BiResultExtractor)
     */
    public <T> Tuple2<List<List<T>>, Jdbc.OutParamResult> listAllResultsetsAndGetOutParameters(final Class<? extends T> targetType)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(targetType, cs.targetType);
        assertNotClosed();

        try {
            JdbcUtil.execute(cstmt);

            final List<List<T>> resultList = JdbcUtil.<List<T>> streamAllResultSets(cstmt, Jdbc.BiResultExtractor.toList(targetType)).toList();

            return Tuple.of(resultList, JdbcUtil.getOutParameters(cstmt, outParams));
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the stored procedure and returns all result sets as lists along with OUT parameters.
     * Each result set is mapped using the provided {@code RowMapper}, giving you full control
     * over how each row in each result set is converted to objects.
     * 
     * <p>The same {@code RowMapper} is applied to all result sets, so this method is best used
     * when all result sets have the same structure or when the mapper can handle variations.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Process multiple result sets with custom mapping
     * try (CallableQuery query = JdbcUtil.prepareCallableQuery(connection, "{call generate_reports(?, ?)}")) {
     *     query.setDate(1, reportDate)
     *          .registerOutParameter(2, Types.VARCHAR);  // report status
     *     
     *     Tuple2<List<List<ReportRow>>, Jdbc.OutParamResult> result = 
     *         query.listAllResultsetsAndGetOutParameters(rs -> {
     *             ReportRow row = new ReportRow();
     *             row.setCategory(rs.getString("category"));
     *             row.setValue(rs.getBigDecimal("value"));
     *             row.setPercentage(rs.getDouble("percentage"));
     *             return row;
     *         });
     *     
     *     List<ReportRow> salesReport = result._1.get(0);
     *     List<ReportRow> inventoryReport = result._1.get(1);
     *     List<ReportRow> expenseReport = result._1.get(2);
     *     
     *     String status = result._2.getOutParamValue(2);
     * }
     * }</pre>
     *
     * @param <T> The target type for row mapping across all result sets
     * @param rowMapper the {@code RowMapper} to apply to each row in all result sets. Must not be {@code null}.
     * @return A {@code Tuple2} containing:
     *         <ul>
     *           <li>First element: List of lists, one per result set</li>
     *           <li>Second element: {@code Jdbc.OutParamResult} containing all OUT parameters</li>
     *         </ul>
     * @throws IllegalArgumentException if {@code rowMapper} is {@code null}
     * @throws IllegalStateException if this query has already been closed
     * @throws SQLException if a database access error occurs or the stored procedure fails
     * @see #listAllResultsetsAndGetOutParameters(Class)
     * @see #listAllResultsetsAndGetOutParameters(RowFilter, RowMapper)
     */
    public <T> Tuple2<List<List<T>>, Jdbc.OutParamResult> listAllResultsetsAndGetOutParameters(final Jdbc.RowMapper<? extends T> rowMapper)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowMapper, cs.rowMapper);
        assertNotClosed();

        try {
            JdbcUtil.execute(cstmt);

            final List<List<T>> resultList = JdbcUtil.<List<T>> streamAllResultSets(cstmt, Jdbc.ResultExtractor.toList(rowMapper)).toList();

            return Tuple.of(resultList, JdbcUtil.getOutParameters(cstmt, outParams));
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the stored procedure and returns all filtered result sets as lists along with OUT parameters.
     * Each result set is filtered using the provided {@code RowFilter} before mapping with the {@code RowMapper}.
     * This allows you to selectively process rows across multiple result sets.
     * 
     * <p>The same filter and mapper are applied to all result sets. Rows that don't pass the filter
     * are skipped entirely, which can significantly improve performance when processing large result sets.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Filter and process only significant transactions across multiple accounts
     * try (CallableQuery query = JdbcUtil.prepareCallableQuery(connection, "{call get_all_transactions(?, ?)}")) {
     *     query.setDate(1, startDate)
     *          .registerOutParameter(2, Types.INTEGER);  // total transactions
     *     
     *     Tuple2<List<List<Transaction>>, Jdbc.OutParamResult> result = 
     *         query.listAllResultsetsAndGetOutParameters(
     *             rs -> rs.getBigDecimal("amount").compareTo(new BigDecimal("1000")) >= 0,
     *             rs -> new Transaction(
     *                 rs.getLong("id"),
     *                 rs.getBigDecimal("amount"),
     *                 rs.getTimestamp("transaction_date")
     *             )
     *         );
     *     
     *     // Each list contains only transactions >= $1000
     *     List<Transaction> checkingTransactions = result._1.get(0);
     *     List<Transaction> savingsTransactions = result._1.get(1);
     *     List<Transaction> creditTransactions = result._1.get(2);
     *     
     *     int totalTransactions = result._2.getOutParamValue(2);
     * }
     * }</pre>
     *
     * @param <T> The target type for row mapping across all result sets
     * @param rowFilter the {@code RowFilter} to apply to each row in all result sets. Must not be {@code null}.
     * @param rowMapper the {@code RowMapper} to apply to filtered rows. Must not be {@code null}.
     * @return A {@code Tuple2} containing:
     *         <ul>
     *           <li>First element: List of lists of filtered and mapped objects</li>
     *           <li>Second element: {@code Jdbc.OutParamResult} containing all OUT parameters</li>
     *         </ul>
     * @throws IllegalArgumentException if {@code rowFilter} or {@code rowMapper} is {@code null}
     * @throws IllegalStateException if this query has already been closed
     * @throws SQLException if a database access error occurs or the stored procedure fails
     * @see #listAllResultsetsAndGetOutParameters(BiRowFilter, BiRowMapper)
     */
    public <T> Tuple2<List<List<T>>, Jdbc.OutParamResult> listAllResultsetsAndGetOutParameters(final Jdbc.RowFilter rowFilter,
            final Jdbc.RowMapper<? extends T> rowMapper) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowFilter, cs.rowFilter);
        checkArgNotNull(rowMapper, cs.rowMapper);
        assertNotClosed();

        try {
            JdbcUtil.execute(cstmt);

            final List<List<T>> resultList = JdbcUtil.<List<T>> streamAllResultSets(cstmt, Jdbc.ResultExtractor.toList(rowFilter, rowMapper)).toList();

            return Tuple.of(resultList, JdbcUtil.getOutParameters(cstmt, outParams));
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the stored procedure and returns all result sets as lists along with OUT parameters.
     * Uses a {@code BiRowMapper} that receives both the {@code ResultSet} and column labels for
     * each result set, enabling dynamic mapping based on result set structure.
     * 
     * <p>This method is particularly useful when different result sets have different structures
     * but can be mapped to the same target type, or when mapping logic depends on column metadata.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Handle result sets with varying columns
     * try (CallableQuery query = JdbcUtil.prepareCallableQuery(connection, "{call get_hierarchical_data(?)}")) {
     *     query.setString(1, rootId);
     *     
     *     Tuple2<List<List<Node>>, Jdbc.OutParamResult> result = 
     *         query.listAllResultsetsAndGetOutParameters((rs, labels) -> {
     *             Node node = new Node();
     *             node.setId(rs.getString("id"));
     *             node.setName(rs.getString("name"));
     *             
     *             // Different result sets may have different attributes
     *             if (labels.contains("parent_id")) {
     *                 node.setParentId(rs.getString("parent_id"));
     *             }
     *             if (labels.contains("level")) {
     *                 node.setLevel(rs.getInt("level"));
     *             }
     *             if (labels.contains("children_count")) {
     *                 node.setChildrenCount(rs.getInt("children_count"));
     *             }
     *             
     *             return node;
     *         });
     *     
     *     List<Node> rootNodes = result._1.get(0);
     *     List<Node> childNodes = result._1.get(1);
     *     List<Node> leafNodes = result._1.get(2);
     * }
     * }</pre>
     *
     * @param <T> The target type for row mapping across all result sets
     * @param rowMapper the {@code BiRowMapper} that receives ResultSet and column labels. Must not be {@code null}.
     * @return A {@code Tuple2} containing:
     *         <ul>
     *           <li>First element: List of lists, one per result set</li>
     *           <li>Second element: {@code Jdbc.OutParamResult} containing all OUT parameters</li>
     *         </ul>
     * @throws IllegalArgumentException if {@code rowMapper} is {@code null}
     * @throws IllegalStateException if this query has already been closed
     * @throws SQLException if a database access error occurs or the stored procedure fails
     * @see #listAllResultsetsAndGetOutParameters(RowMapper)
     */
    public <T> Tuple2<List<List<T>>, Jdbc.OutParamResult> listAllResultsetsAndGetOutParameters(final Jdbc.BiRowMapper<? extends T> rowMapper)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowMapper, cs.rowMapper);
        assertNotClosed();

        try {
            JdbcUtil.execute(cstmt);

            final List<List<T>> resultList = JdbcUtil.<List<T>> streamAllResultSets(cstmt, Jdbc.BiResultExtractor.toList(rowMapper)).toList();

            return Tuple.of(resultList, JdbcUtil.getOutParameters(cstmt, outParams));
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the stored procedure and returns all filtered result sets as lists along with OUT parameters.
     * Uses both a {@code BiRowFilter} and {@code BiRowMapper} that receive the {@code ResultSet} and
     * column labels, providing maximum flexibility for filtering and mapping across multiple result sets
     * with potentially different structures.
     * 
     * <p>This is the most powerful list method, allowing you to handle complex scenarios where
     * each result set may have different columns and require different filtering/mapping logic
     * based on those columns.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Complex filtering and mapping across heterogeneous result sets
     * try (CallableQuery query = JdbcUtil.prepareCallableQuery(connection, "{call analyze_customer_360(?)}")) {
     *     query.setLong(1, customerId);
     *     
     *     Tuple2<List<List<CustomerData>>, Jdbc.OutParamResult> result = 
     *         query.listAllResultsetsAndGetOutParameters(
     *             (rs, labels) -> {
     *                 // Filter based on available columns
     *                 if (labels.contains("is_active")) {
     *                     return rs.getBoolean("is_active");
     *                 } else if (labels.contains("status")) {
     *                     return "ACTIVE".equals(rs.getString("status"));
     *                 }
     *                 return true;  // Include all rows if no status column
     *             },
     *             (rs, labels) -> {
     *                 CustomerData data = new CustomerData();
     *                 data.setType(determineTypeFromColumns(labels));
     *                 
     *                 // Map common fields
     *                 if (labels.contains("id")) data.setId(rs.getLong("id"));
     *                 if (labels.contains("value")) data.setValue(rs.getBigDecimal("value"));
     *                 
     *                 // Map type-specific fields
     *                 if (labels.contains("order_date")) {
     *                     data.setDate(rs.getTimestamp("order_date"));
     *                     data.setDescription(rs.getString("product_name"));
     *                 } else if (labels.contains("interaction_date")) {
     *                     data.setDate(rs.getTimestamp("interaction_date"));
     *                     data.setDescription(rs.getString("interaction_type"));
     *                 }
     *                 
     *                 return data;
     *             }
     *         );
     *     
     *     List<CustomerData> orders = result._1.get(0);
     *     List<CustomerData> interactions = result._1.get(1);
     *     List<CustomerData> preferences = result._1.get(2);
     * }
     * }</pre>
     *
     * @param <T> The target type for row mapping across all result sets
     * @param rowFilter the {@code BiRowFilter} that receives ResultSet and column labels. Must not be {@code null}.
     * @param rowMapper the {@code BiRowMapper} that receives ResultSet and column labels. Must not be {@code null}.
     * @return A {@code Tuple2} containing:
     *         <ul>
     *           <li>First element: List of lists of filtered and mapped objects</li>
     *           <li>Second element: {@code Jdbc.OutParamResult} containing all OUT parameters</li>
     *         </ul>
     * @throws IllegalArgumentException if {@code rowFilter} or {@code rowMapper} is {@code null}
     * @throws IllegalStateException if this query has already been closed
     * @throws SQLException if a database access error occurs or the stored procedure fails
     * @see #listAllResultsetsAndGetOutParameters(RowFilter, RowMapper)
     */
    public <T> Tuple2<List<List<T>>, Jdbc.OutParamResult> listAllResultsetsAndGetOutParameters(final Jdbc.BiRowFilter rowFilter,
            final Jdbc.BiRowMapper<? extends T> rowMapper) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowFilter, cs.rowFilter);
        checkArgNotNull(rowMapper, cs.rowMapper);
        assertNotClosed();

        try {
            JdbcUtil.execute(cstmt);

            final List<List<T>> resultList = JdbcUtil.<List<T>> streamAllResultSets(cstmt, Jdbc.BiResultExtractor.toList(rowFilter, rowMapper)).toList();

            return Tuple.of(resultList, JdbcUtil.getOutParameters(cstmt, outParams));
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Closes the underlying {@code CallableStatement} after clearing its parameters.
     * This method is called automatically after query execution unless
     * {@code closeAfterExecution(false)} has been set.
     * 
     * <p>The method performs the following cleanup operations:</p>
     * <ol>
     *   <li>Clears all parameters from the CallableStatement</li>
     *   <li>Logs a warning if parameter clearing fails</li>
     *   <li>Closes the statement and releases resources</li>
     * </ol>
     * 
     * <p>This method is idempotent - calling it multiple times has no additional effect.</p>
     * 
     * @see #closeAfterExecution(boolean)
     * @see JdbcUtil#clearParameters(PreparedStatement)
     */
    @Override
    protected void closeStatement() {
        try {
            cstmt.clearParameters();
        } catch (final SQLException e) {
            logger.warn("failed to reset statement", e);
        } finally {
            super.closeStatement();
        }
    }
}
