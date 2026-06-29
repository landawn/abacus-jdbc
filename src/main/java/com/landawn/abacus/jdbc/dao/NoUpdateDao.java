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
 * DAO that allows read and insert operations but disables update and delete. Useful for audit trails,
 * append-only/event-sourcing stores, and CQRS-style write models where existing rows must never change.
 *
 * <p>It is a pure capability composite of {@link InsertableDao} (reads + inserts, via its
 * {@link ReadableDao} super-interface) plus the {@link Cacheable} marker. It does <b>not</b> mix in
 * {@code UpdatableDao}/{@code DeletableDao}, so {@code update}/{@code upsert}/{@code delete}/
 * {@code batchUpdate}/{@code batchUpsert} (and {@code prepareCallableQuery}) are <b>absent from the
 * type</b> — calling them is a compile error rather than a runtime {@link UnsupportedOperationException}.</p>
 *
 * <p>The inherited {@code prepareQuery}/{@code prepareNamedQuery} (and {@code *ForLargeResult})
 * overloads that take a raw SQL {@code String}/{@link com.landawn.abacus.query.ParsedSql} are
 * restricted to {@code SELECT} and {@code INSERT} statements: the framework rejects update/delete/merge
 * (and upsert-style inserts that overwrite existing rows) at runtime with an
 * {@link UnsupportedOperationException}. (This SQL-kind gate is enforced centrally by the DAO proxy.)</p>
 *
 * <p>This interface is marked as {@link Beta @Beta}, indicating it may be subject to
 * incompatible changes, or even removal, in a future release.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface AuditLogDao extends NoUpdateDao<AuditLog, AuditLogDao> {
 * }
 *
 * AuditLogDao dao = JdbcUtil.createDao(AuditLogDao.class, dataSource);
 *
 * dao.save(new AuditLog("LOGIN", userId));                            // reads + inserts work
 * dao.prepareNamedQuery("INSERT INTO audit_log(action) VALUES (:a)").setString("a", "LOGOUT").execute();
 *
 * // dao.update("status", "X", Filters.eq("id", 1L));                 // does not compile
 * // dao.prepareQuery("UPDATE audit_log SET ...");                    // throws UnsupportedOperationException
 * }</pre>
 *
 * @param <T> the entity type managed by this DAO
 * @param <TD> the concrete DAO type itself (self-referencing generic for fluent method chaining)
 * @see InsertableDao
 * @see ReadableDao
 * @see com.landawn.abacus.query.Filters
 */
@Beta
public non-sealed interface NoUpdateDao<T, TD extends NoUpdateDao<T, TD>> extends InsertableDao<T, TD>, Cacheable {
}
