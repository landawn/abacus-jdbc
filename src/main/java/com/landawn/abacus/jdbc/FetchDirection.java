/*
 * Copyright (C) 2020 HaiYang Li
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.landawn.abacus.jdbc;

import java.sql.ResultSet;

/**
 * Enumeration representing the direction in which rows of a {@link ResultSet} will be processed.
 * This enum provides a type-safe and more readable alternative to using the raw integer constants
 * defined in {@link ResultSet} for specifying fetch directions.
 *
 * <p>The fetch direction is a hint provided to the JDBC driver, suggesting the expected
 * pattern of row access. This hint allows the driver to optimize data retrieval from the
 * database, potentially improving performance for certain access patterns.</p>
 *
 * <p><b>Important Considerations:</b></p>
 * <ul>
 *   <li><b>Driver Support:</b> Not all JDBC drivers fully support or respect all fetch directions.
 *       Some drivers may ignore the hint entirely, or only support it for specific {@link ResultSet} types.</li>
 *   <li><b>ResultSet Type:</b> Fetch directions like {@code REVERSE} are typically only effective
 *       with scrollable {@link ResultSet} types (e.g., {@code ResultSet.TYPE_SCROLL_INSENSITIVE}
 *       or {@code ResultSet.TYPE_SCROLL_SENSITIVE}).</li>
 *   <li><b>Performance:</b> While intended for optimization, incorrect usage or unsupported
 *       directions might lead to no performance gain or even degradation.</li>
 * </ul>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Example: Setting fetch direction for a Statement
 * try (Connection conn = dataSource.getConnection()) {
 *     // Create a scrollable ResultSet to support reverse fetching
 *     Statement stmt = conn.createStatement(
 *         ResultSet.TYPE_SCROLL_INSENSITIVE,
 *         ResultSet.CONCUR_READ_ONLY
 *     );
 *
 *     // Hint to the driver to process rows in reverse order
 *     stmt.setFetchDirection(FetchDirection.REVERSE.intValue());
 *
 *     try (ResultSet rs = stmt.executeQuery("SELECT id, name FROM users ORDER BY id DESC")) {
 *         // Process results, potentially benefiting from the reverse hint
 *         while (rs.next()) {
 *             System.out.println("User: " + rs.getString("name"));
 *         }
 *     }
 * } catch (SQLException e) {
 *     System.err.println("Database error: " + e.getMessage());
 * }
 * }</pre>
 *
 * @see ResultSet#setFetchDirection(int)
 * @see ResultSet#getFetchDirection()
 * @see ResultSet#FETCH_FORWARD
 * @see ResultSet#FETCH_REVERSE
 * @see ResultSet#FETCH_UNKNOWN
 */
public enum FetchDirection {

    /**
     * Indicates that rows in a {@link ResultSet} will be processed in a forward direction,
     * from the first row to the last. This is the default fetch direction for most
     * {@link ResultSet} types and is generally the most efficient for sequential access.
     *
     * <p>Corresponds to the JDBC constant {@link ResultSet#FETCH_FORWARD}.</p>
     */
    FORWARD(ResultSet.FETCH_FORWARD),

    /**
     * Indicates that rows in a {@link ResultSet} will be processed in a reverse direction,
     * from the last row to the first.
     *
     * <p>This fetch direction typically requires a scrollable {@link ResultSet} type
     * (e.g., {@code ResultSet.TYPE_SCROLL_INSENSITIVE}). Not all JDBC drivers or database
     * systems support reverse fetching, and its performance can vary significantly.</p>
     *
     * <p>Corresponds to the JDBC constant {@link ResultSet#FETCH_REVERSE}.</p>
     */
    REVERSE(ResultSet.FETCH_REVERSE),

    /**
     * Indicates that the order in which rows in a {@link ResultSet} will be processed is unknown.
     *
     * <p>This constant is typically used when the fetch direction has not been explicitly set,
     * or when the JDBC driver does not provide specific fetch direction hints. In such scenarios,
     * the driver will revert to its default behavior for processing result set rows.</p>
     *
     * <p>Corresponds to the JDBC constant {@link ResultSet#FETCH_UNKNOWN}.</p>
     */
    UNKNOWN(ResultSet.FETCH_UNKNOWN);

    /**
     * The integer value representing this fetch direction, as defined in {@link ResultSet}.
     */
    final int intValue;

    /**
     * Constructs a FetchDirection with the specified integer value.
     * 
     * @param intValue The JDBC constant value for this fetch direction
     */
    FetchDirection(final int intValue) {
        this.intValue = intValue;
    }

    /**
     * Returns the {@code FetchDirection} enum constant that corresponds to the given
     * JDBC integer constant value.
     *
     * <p>This static factory method provides a convenient way to convert a raw JDBC
     * fetch direction integer (obtained, for example, from {@link ResultSet#getFetchDirection()})
     * into its type-safe {@code FetchDirection} enum representation.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Connection conn = dataSource.getConnection();
     *      Statement stmt = conn.createStatement();
     *      ResultSet rs = stmt.executeQuery("SELECT * FROM products")) {
     *
     *     int jdbcFetchDirection = rs.getFetchDirection();
     *     FetchDirection enumFetchDirection = FetchDirection.valueOf(jdbcFetchDirection);
     *     System.out.println("Detected fetch direction: " + enumFetchDirection);
     * } catch (SQLException e) {
     *     System.err.println("Database error: " + e.getMessage());
     * }
     * }</pre>
     *
     * @param intValue the JDBC integer constant representing a fetch direction
     *        (e.g., {@link ResultSet#FETCH_FORWARD}, {@link ResultSet#FETCH_REVERSE}, {@link ResultSet#FETCH_UNKNOWN}).
     * @return the corresponding {@code FetchDirection} enum constant.
     * @throws IllegalArgumentException if {@code intValue} does not match any known JDBC fetch direction constant.
     */
    public static FetchDirection valueOf(final int intValue) {
        switch (intValue) {
            case ResultSet.FETCH_FORWARD:
                return FORWARD;

            case ResultSet.FETCH_REVERSE:
                return REVERSE;

            case ResultSet.FETCH_UNKNOWN:
                return UNKNOWN;

            default:
                throw new IllegalArgumentException("No FetchDirection mapping to int value: " + intValue);

        }
    }

    /**
     * Returns the raw JDBC integer constant value associated with this {@code FetchDirection}.
     * This value can be directly used with {@link java.sql.Statement#setFetchDirection(int)}
     * or {@link java.sql.ResultSet#setFetchDirection(int)}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Connection conn = dataSource.getConnection()) {
     *     Statement stmt = conn.createStatement(
     *         ResultSet.TYPE_SCROLL_INSENSITIVE, // Example: requires scrollable ResultSet
     *         ResultSet.CONCUR_READ_ONLY
     *     );
     *     stmt.setFetchDirection(FetchDirection.REVERSE.intValue());
     *     // ... execute query and process ResultSet ...
     * } catch (SQLException e) {
     *     System.err.println("Database error: " + e.getMessage());
     * }
     * }</pre>
     *
     * @return the JDBC integer constant value (e.g., {@link ResultSet#FETCH_FORWARD}).
     */
    public int intValue() {
        return intValue;
    }
}
