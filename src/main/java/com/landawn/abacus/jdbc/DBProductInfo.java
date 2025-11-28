/*
 * Copyright (c) 2021, Haiyang Li.
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

/**
 * A record that encapsulates database product information including the product name,
 * version string, and parsed version enum.
 *
 * <p>This record is typically used to store metadata about the underlying database system
 * retrieved from JDBC connection metadata. It provides a convenient way to access both
 * the raw version information and the parsed enum value for database-specific logic.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Create from JDBC metadata
 * Connection conn = dataSource.getConnection();
 * DatabaseMetaData metaData = conn.getMetaData();
 * String productName = metaData.getDatabaseProductName();
 * String productVersion = metaData.getDatabaseProductVersion();
 * DBVersion version = parseVersion(productName, productVersion);
 * DBProductInfo dbInfo = new DBProductInfo(productName, productVersion, version);
 *
 * // Access product information
 * System.out.println("Database: " + dbInfo.productName());  // "MySQL"
 * System.out.println("Version: " + dbInfo.productVersion());  // "8.0.33"
 *
 * // Use parsed version for logic
 * if (dbInfo.version().isMySQL()) {
 *     // MySQL-specific operations
 * }
 * }</pre>
 *
 * @param productName the name of the database product (e.g., "MySQL", "PostgreSQL", "Oracle"), must not be {@code null}
 * @param productVersion the version string of the database product (e.g., "8.0.33", "15.3"), must not be {@code null}
 * @param version the parsed {@link DBVersion} enum representing the database type and major version, must not be {@code null}
 *
 * @see DBVersion
 */
public record DBProductInfo(String productName, String productVersion, DBVersion version) { // NOSONAR

}
