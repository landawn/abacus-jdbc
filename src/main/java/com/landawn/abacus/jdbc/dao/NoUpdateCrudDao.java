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
 * CRUD DAO that disables update and delete operations while permitting read and insert operations.
 * This interface extends {@link NoUpdateDao}, {@link ReadableCrudDao} and {@link InsertableCrudDao}, effectively
 * creating a DAO that can only read existing records and insert new ones, but cannot modify or remove existing records.
 *
 * <p>This pattern is particularly useful for:</p>
 * <ul>
 *   <li>Audit logs or event stores where records should be immutable</li>
 *   <li>Append-only data stores and event sourcing patterns</li>
 *   <li>Historical data that should not be modified</li>
 *   <li>Enforcing data integrity by preventing updates at the DAO level</li>
 * </ul>
 *
 * <p>Update, upsert, and delete operations are <b>absent from the type</b> — calling them is a compile error
 * rather than a runtime {@link UnsupportedOperationException}. Read operations (find, exists, query) and insert
 * operations remain functional. (The inherited raw-SQL {@code prepareQuery}/{@code prepareNamedQuery} overloads
 * accept only {@code SELECT} and {@code INSERT} statements at runtime, enforced centrally by the DAO proxy.)</p>
 *
 * <p><b>Supported Operations:</b></p>
 * <ul>
 *   <li><b>Read by ID:</b> {@code get(ID)}, {@code gett(ID)}, {@code queryForBoolean/Int/Long/String(propName, ID)}</li>
 *   <li><b>Query Operations:</b> {@code list(Condition)}, {@code findFirst(Condition)}, {@code findOnlyOne(Condition)}</li>
 *   <li><b>Aggregate Operations:</b> {@code count(Condition)}, {@code exists(Condition)}</li>
 *   <li><b>Insert Operations:</b> {@code insert(entity)}, {@code batchInsert(entities)}</li>
 *   <li><b>Query Preparation:</b> {@code prepareQuery} and {@code prepareNamedQuery} for {@code SELECT} and {@code INSERT}</li>
 * </ul>
 *
 * <p>This interface is marked as {@link Beta @Beta}, indicating it may be subject to
 * incompatible changes, or even removal, in a future release.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Define a DAO for immutable transaction records
 * public interface TransactionDao extends NoUpdateCrudDao<Transaction, String, TransactionDao> {
 *     // Custom read methods can be added
 * }
 *
 * TransactionDao transactionDao = JdbcUtil.createDao(TransactionDao.class, dataSource);
 *
 * // Supported operations - all work fine:
 *
 * // Insert operations
 * Transaction txn = new Transaction("TXN001", customerId, amount);
 * String txnId = transactionDao.insert(txn);   // Returns generated ID
 *
 * List<Transaction> newTransactions = createTransactions();
 * List<String> ids = transactionDao.batchInsert(newTransactions);   // Batch insert
 *
 * // Read by ID operations
 * Optional<Transaction> transaction = transactionDao.get(txnId);   // Returns Optional
 * Transaction txn2 = transactionDao.gett(txnId);   // Returns null if not found
 *
 * // Query single property by ID
 * Nullable<String> status = transactionDao.queryForString("status", txnId);
 * OptionalDouble amount = transactionDao.queryForDouble("amount", txnId);
 *
 * // Query operations
 * List<Transaction> txns = transactionDao.list(Filters.eq("customerId", customerId));
 * Optional<Transaction> firstTxn = transactionDao.findFirst(Filters.gt("amount", 1000.0));
 * Optional<Transaction> uniqueTxn = transactionDao.findOnlyOne(Filters.eq("referenceNumber", "REF123"));
 *
 * // Count and existence checks
 * int count = transactionDao.count(Filters.eq("status", "PENDING"));
 * boolean exists = transactionDao.exists(Filters.eq("id", txnId));
 *
 * // Prepare custom SELECT queries
 * List<Transaction> results = transactionDao.prepareQuery(
 *         "SELECT * FROM transactions WHERE amount > ? AND status = ?")
 *         .setDouble(1, 500.0)
 *         .setString(2, "COMPLETED")
 *         .list(Transaction.class);
 *
 * // Unsupported operations - these are absent from the type and do not compile:
 * // transactionDao.update(txn);                            // does not compile
 * // transactionDao.update("status", "CANCELLED", txnId);   // does not compile
 * // transactionDao.deleteById(txnId);                      // does not compile
 * // transactionDao.delete(txn);                            // does not compile
 * // transactionDao.batchDelete(transactions);             // does not compile
 * // transactionDao.upsert(txn);                            // does not compile
 * }</pre>
 *
 * @param <T> the entity type managed by this DAO
 * @param <ID> the type of the entity's primary key
 * @param <TD> the concrete DAO type itself (self-referencing generic for fluent method chaining)
 * @see NoUpdateDao
 * @see ReadableCrudDao
 * @see InsertableCrudDao
 * @see com.landawn.abacus.query.Filters
 */
@SuppressWarnings("RedundantThrows")
@Beta
public non-sealed interface NoUpdateCrudDao<T, ID, TD extends NoUpdateCrudDao<T, ID, TD>>
        extends NoUpdateDao<T, TD>, ReadableCrudDao<T, ID, TD>, InsertableCrudDao<T, ID, TD> {

}
