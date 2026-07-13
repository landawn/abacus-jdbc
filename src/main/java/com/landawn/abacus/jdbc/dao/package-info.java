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
 * Composable Data Access Object interfaces for entity-oriented JDBC operations.
 *
 * <p>{@link com.landawn.abacus.jdbc.dao.Dao} provides condition-based query, insert,
 * update, and delete capabilities, while {@link com.landawn.abacus.jdbc.dao.CrudDao}
 * adds primary-key-based CRUD and upsert operations. Restricted variants such as
 * {@link com.landawn.abacus.jdbc.dao.ReadOnlyDao},
 * {@link com.landawn.abacus.jdbc.dao.ReadOnlyCrudDao},
 * {@link com.landawn.abacus.jdbc.dao.NoUpdateDao}, and
 * {@link com.landawn.abacus.jdbc.dao.NoUpdateCrudDao} expose narrower mutation policies.
 * Corresponding {@code Unchecked*} interfaces report database failures as unchecked SQL
 * exceptions.</p>
 *
 * <p>{@link com.landawn.abacus.jdbc.dao.JoinEntityHelper} and
 * {@link com.landawn.abacus.jdbc.dao.CrudJoinEntityHelper} provide operations for entity
 * associations described by join metadata. Applications normally define a self-typed
 * interface extending one of these DAO contracts and create its implementation with
 * {@link com.landawn.abacus.jdbc.JdbcUtil#createDao(Class, javax.sql.DataSource)}. Custom
 * SQL methods can be declared with {@link com.landawn.abacus.jdbc.annotation.Query}.</p>
 *
 * @see com.landawn.abacus.jdbc.dao.DaoBase
 * @see com.landawn.abacus.jdbc.dao.Dao
 * @see com.landawn.abacus.jdbc.dao.CrudDao
 * @see com.landawn.abacus.jdbc.dao.ReadOnlyDao
 * @see com.landawn.abacus.jdbc.dao.UncheckedDao
 * @see com.landawn.abacus.jdbc.dao.JoinEntityHelper
 */
package com.landawn.abacus.jdbc.dao;
