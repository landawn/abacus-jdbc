/*
 * Copyright (c) 2026, Haiyang Li.
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

/**
 * Core JDBC execution, mapping, transaction, data-transfer, and code-generation APIs.
 *
 * <p>{@link com.landawn.abacus.jdbc.JdbcUtil} provides high-level connection, statement,
 * batch, transaction, streaming, and DAO-proxy operations. Fluent statement execution is
 * available through {@link com.landawn.abacus.jdbc.PreparedQuery},
 * {@link com.landawn.abacus.jdbc.NamedQuery}, and
 * {@link com.landawn.abacus.jdbc.CallableQuery}, while {@link com.landawn.abacus.jdbc.Jdbc}
 * defines reusable parameter-setting, row-mapping, result-extraction, stored-procedure,
 * handler, and caching contracts.</p>
 *
 * <p>The package also provides transaction management through
 * {@link com.landawn.abacus.jdbc.Transaction}, bulk import, export, and database-copy
 * operations through {@link com.landawn.abacus.jdbc.DataTransferUtil}, and schema-driven
 * source and SQL generation through {@link com.landawn.abacus.jdbc.JdbcCodeGenerationUtil}.</p>
 *
 * @see com.landawn.abacus.jdbc.JdbcUtil
 * @see com.landawn.abacus.jdbc.Jdbc
 * @see com.landawn.abacus.jdbc.PreparedQuery
 * @see com.landawn.abacus.jdbc.NamedQuery
 * @see com.landawn.abacus.jdbc.CallableQuery
 * @see com.landawn.abacus.jdbc.Transaction
 */
package com.landawn.abacus.jdbc;
