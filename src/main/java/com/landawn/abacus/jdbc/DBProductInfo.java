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
 * retrieved from JDBC connection metadata.</p>
 * 
 * <p>Usage example:
 * <pre>{@code
 * DBProductInfo dbInfo = new DBProductInfo("MySQL", "8.0.33", DBVersion.MYSQL_8);
 * String name = dbInfo.productName(); // "MySQL"
 * }</pre>
 * 
 * @param productName The name of the database product (e.g., "MySQL", "PostgreSQL", "Oracle")
 * @param productVersion The version string of the database product (e.g., "8.0.33", "15.3")
 * @param version The parsed {@link DBVersion} enum representing the database type and major version
 * 
 * @see DBVersion
 * @since 1.0
 */
public record DBProductInfo(String productName, String productVersion, DBVersion version) { // NOSONAR

}