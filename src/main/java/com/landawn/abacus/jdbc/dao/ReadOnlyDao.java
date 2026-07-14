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
package com.landawn.abacus.jdbc.dao;

import com.landawn.abacus.annotation.Beta;

/**
 * Strictly read-only DAO that permits only {@code SELECT} queries. This is the most restrictive DAO
 * interface in the hierarchy.
 *
 * <p>It is a pure capability composite of {@link ReadOps} (reads only). It does <b>not</b> mix in
 * {@code InsertOps}/{@code UpdateOps}/{@code DeleteOps},
 * so {@code save}/{@code insert}/{@code update}/{@code upsert}/{@code delete}/{@code batchXxx} (and
 * {@code prepareCallableQuery}) are simply <b>absent from the type</b> — calling them is a compile
 * error rather than a runtime {@link UnsupportedOperationException}. Note that {@code ReadOnlyDao}
 * is intentionally not a subtype of {@link NonUpdateDao}: a read-only DAO cannot substitute for a
 * non-update one (which permits inserts), so such a subtype edge would violate the Liskov
 * substitution principle.</p>
 *
 * <p>The inherited {@code prepareQuery}/{@code prepareNamedQuery} (and {@code *ForLargeResult})
 * overloads that take a raw SQL {@code String}/{@link com.landawn.abacus.query.ParsedSql} are
 * restricted to {@code SELECT} statements: the framework rejects anything else at runtime with an
 * {@link UnsupportedOperationException}. (This SQL-kind gate is enforced centrally by the DAO proxy.)</p>
 *
 * <p>This interface is marked as {@link Beta @Beta}, indicating it may be subject to
 * incompatible changes, or even removal, in a future release.</p>
 *
 * <p><b>&#9888; Warning:</b> This is an API capability restriction, not a database security or
 * integrity boundary. Enforce read-only access with database permissions and an appropriate
 * read-only transaction policy as well.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Define a read-only DAO for viewing data
 * public interface CustomerViewDao extends ReadOnlyDao<Customer, CustomerViewDao> {
 * }
 *
 * CustomerViewDao dao = JdbcUtil.createDao(CustomerViewDao.class, dataSource);
 *
 * List<Customer> active = dao.list(Filters.eq("status", "ACTIVE"));   // reads work
 * dao.prepareQuery("SELECT * FROM customers WHERE status = ?").setString(1, "ACTIVE").list(Customer.class);
 *
 * // dao.save(new Customer());                          // does not compile
 * // dao.prepareQuery("UPDATE customers SET ...");      // throws UnsupportedOperationException
 * }</pre>
 *
 * @param <T> the entity type managed by this DAO
 * @param <TD> the concrete DAO type itself (self-referencing generic for fluent method chaining)
 * @see ReadOps
 * @see NonUpdateDao
 * @see com.landawn.abacus.query.Filters
 */
@Beta
public non-sealed interface ReadOnlyDao<T, TD extends ReadOnlyDao<T, TD>> extends ReadOps<T, TD> {
}
