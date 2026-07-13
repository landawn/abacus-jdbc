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
 * Runtime annotations for declaring and configuring JDBC DAO methods.
 *
 * <p>{@link com.landawn.abacus.jdbc.annotation.Query} declares inline or externally
 * mapped SQL, with parameters supplied through {@link com.landawn.abacus.jdbc.annotation.Bind}
 * and {@link com.landawn.abacus.jdbc.annotation.BindList}.
 * {@link com.landawn.abacus.jdbc.annotation.SqlFragment} and
 * {@link com.landawn.abacus.jdbc.annotation.SqlFragmentList} support controlled
 * substitution of SQL structure, while {@link com.landawn.abacus.jdbc.annotation.OutParameter}
 * and {@link com.landawn.abacus.jdbc.annotation.OutParameters} describe stored-procedure
 * output parameters.</p>
 *
 * <p>Additional annotations configure transactions
 * ({@link com.landawn.abacus.jdbc.annotation.Transactional}), DAO behavior
 * ({@link com.landawn.abacus.jdbc.annotation.DaoConfig}), result mapping
 * ({@link com.landawn.abacus.jdbc.annotation.MappedByKey} and
 * {@link com.landawn.abacus.jdbc.annotation.MergedById}), invocation interception
 * ({@link com.landawn.abacus.jdbc.annotation.Handler}), caching
 * ({@link com.landawn.abacus.jdbc.annotation.Cache} and
 * {@link com.landawn.abacus.jdbc.annotation.CacheResult}), and SQL or performance logging.</p>
 *
 * <p><b>&#9888; Warning:</b> SQL fragments are textual substitutions rather than JDBC bind
 * parameters. Fragment values should be selected from application-controlled input;
 * untrusted values should be passed through {@link com.landawn.abacus.jdbc.annotation.Bind}
 * or {@link com.landawn.abacus.jdbc.annotation.BindList}.</p>
 *
 * @see com.landawn.abacus.jdbc.annotation.Query
 * @see com.landawn.abacus.jdbc.annotation.SqlSource
 * @see com.landawn.abacus.jdbc.annotation.Transactional
 * @see com.landawn.abacus.jdbc.annotation.Handler
 * @see com.landawn.abacus.jdbc.annotation.CacheResult
 * @see com.landawn.abacus.jdbc.dao.Dao
 */
package com.landawn.abacus.jdbc.annotation;
