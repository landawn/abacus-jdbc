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
 * Represents the direction in which rows will be processed in a {@link ResultSet}.
 * 
 * <p>This enum provides a type-safe wrapper around the JDBC fetch direction constants
 * defined in {@link ResultSet}. The fetch direction is a hint to the JDBC driver
 * about the direction in which result set rows will be processed, allowing the driver
 * to optimize performance.</p>
 * 
 * <p>Note that not all JDBC drivers support all fetch directions, and some may ignore
 * this hint entirely. The actual behavior depends on the result set type and the
 * database driver implementation.</p>
 * 
 * <p>Usage example:
 * <pre>{@code
 * Statement stmt = connection.createStatement(
 *     ResultSet.TYPE_SCROLL_INSENSITIVE,
 *     ResultSet.CONCUR_READ_ONLY
 * );
 * stmt.setFetchDirection(FetchDirection.REVERSE.intValue());
 * 
 * ResultSet rs = stmt.executeQuery("SELECT * FROM users");
 * // Process results in reverse order
 * }</pre>
 * 
 * @see ResultSet#setFetchDirection(int)
 * @see ResultSet#getFetchDirection()
 * @since 1.0
 */
public enum FetchDirection {

    /**
     * Indicates that rows in a result set will be processed in a forward direction,
     * from first to last.
     * 
     * <p>This is the default and most common fetch direction. When processing a
     * result set with this direction, rows are typically fetched sequentially
     * from the beginning to the end.</p>
     * 
     * <p>Corresponds to {@link ResultSet#FETCH_FORWARD}.</p>
     */
    FORWARD(ResultSet.FETCH_FORWARD),

    /**
     * Indicates that rows in a result set will be processed in a reverse direction,
     * from last to first.
     * 
     * <p>This fetch direction requires a scrollable result set. Not all JDBC drivers
     * support reverse fetching, and it may have performance implications depending
     * on the database and driver implementation.</p>
     * 
     * <p>Corresponds to {@link ResultSet#FETCH_REVERSE}.</p>
     */
    REVERSE(ResultSet.FETCH_REVERSE),

    /**
     * Indicates that the order in which rows in a result set will be processed is unknown.
     * 
     * <p>This value may be returned when the fetch direction has not been set or when
     * the JDBC driver does not support fetch direction hints. In such cases, the driver
     * will use its default behavior.</p>
     * 
     * <p>Corresponds to {@link ResultSet#FETCH_UNKNOWN}.</p>
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
     * Returns the FetchDirection enum constant corresponding to the specified integer value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ResultSet rs = stmt.executeQuery("SELECT * FROM users");
     * int direction = rs.getFetchDirection();
     * FetchDirection fetchDir = FetchDirection.valueOf(direction);
     * System.out.println("Fetch direction: " + fetchDir);
     * }</pre>
     *
     * @param intValue The JDBC constant value for the fetch direction
     * @return The corresponding FetchDirection enum constant
     * @throws IllegalArgumentException if the specified value does not correspond to any fetch direction
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
     * Returns the integer value of this fetch direction as defined in {@link ResultSet}.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Statement stmt = connection.createStatement(
     *     ResultSet.TYPE_SCROLL_SENSITIVE,
     *     ResultSet.CONCUR_READ_ONLY
     * );
     * stmt.setFetchDirection(FetchDirection.REVERSE.intValue());
     * }</pre>
     *
     * @return The JDBC constant value for this fetch direction
     */
    public int intValue() {
        return intValue;
    }
}