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
package com.landawn.abacus.jdbc.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.jdbc.JdbcUtil;

/**
 * Enables method-level result caching for DAO query methods.
 * This annotation provides fine-grained control over caching behavior, including
 * time-to-live, idle timeout, size restrictions, and serialization strategies.
 *
 * <p><strong>Note:</strong> This feature is marked as {@code @Beta} and may change in future versions.</p>
 *
 * <p>Consider carefully whether caching at the DAO layer is appropriate for your use case,
 * as it can lead to stale data issues if not managed properly.</p>
 *
 * <p>The cache key is automatically derived from the fully-qualified method name, the DAO's table name,
 * the identity of the DAO proxy (so DAOs of different interfaces or {@code DataSource}s that share a cache never
 * share entries) and the serialized method arguments; when the arguments cannot be serialized, a warning is logged
 * and the result is not cached. Results are cached after the first execution and returned from cache for subsequent
 * calls with the same parameters until the cache expires or is invalidated. A {@code null}
 * method result is not cached; use an empty {@code Optional}, collection, or other non-null
 * result object when caching an explicit "no result" value is important.</p>
 *
 * <p>Callback writes must run in an independent transaction (for example, DAO {@code REQUIRES_NEW} or an explicit
 * {@code JdbcUtil.beginTransaction} scope). The library rejects SQL execution on a Spring connection known to be
 * completing, including queries since result-producing SQL can write: its clean report covers the original work only,
 * while cleanup may commit a later callback write after another thread has cached the old rows. Register
 * {@code SpringTransactionTracker} to identify the owning transaction even with JPA or synchronization disabled;
 * untracked detection retains the limits below.</p>
 *
 * <p>Without a tracker, the SQL guard can reject an active outer transaction's SQL while another transaction on the thread
 * completes. With a tracker, later execution-listener SQL on the completing owner's connection is refused from the
 * tracker's {@code beforeCommit}/{@code beforeRollback} notification, even before the physical outcome. Use
 * {@code TransactionSynchronization.beforeCommit} for work intended for the original transaction. Independent callback
 * transactions and {@code afterBegin} work remain allowed; see {@link JdbcUtil}.</p>
 *
 * <p><strong>Transactions:</strong> inside a transaction on the DAO's data source (a {@code JdbcUtil} transaction, or a
 * Spring-managed transaction that owns its connection, read-only ones included), results are neither served from the cache
 * nor stored in it, nor in the cache of a {@code JdbcUtil.openDaoCacheScope}: they could miss the transaction's own writes,
 * or hold work it may still roll back. A {@link RefreshCache @RefreshCache} write there invalidates the cache once the
 * transaction has committed (or its commit failed, as it may have committed), and, unless it joins a {@code JdbcUtil}
 * transaction of its own (see {@link RefreshCache}), right away as well. While a Spring-managed transaction that wrote a
 * table runs, results of that table read outside it are neither served from these caches nor stored in them, as long as the
 * transaction's connection holder records its completion ({@code DataSourceTransactionManager},
 * {@code JdbcTransactionManager}), whatever transaction's synchronizations Spring runs meanwhile: the rows a read between
 * the write and the commit fetched are as they were before the write. That flag can't tell a failed cleanup: without a
 * tracker, a failed commit on a connection bound before the transaction with auto-commit already off lifts the block when
 * Spring clears the holder, although the work is still pending on the connection, so a read then may store rows that the
 * pending work later changes. Register a
 * {@link com.landawn.abacus.jdbc.SpringTransactionTracker} with the transaction managers (first, and with every one on the
 * data source: see its registration requirements) to have the transaction manager report the completion instead, for any
 * transaction manager whose transaction exposes its JDBC connection holder (JPA ones included), published so that every
 * thread sees it. Failed or unreported completion of a tracked transaction keeps affected caches disabled until a clean
 * completion report or explicit, transaction-specific
 * {@link com.landawn.abacus.jdbc.SpringTransactionTracker#confirmSettled(long)} confirmation. This applies to JDBC and JPA:
 * holder flags, entity-manager closure and garbage collection do not prove that pending work can no longer commit. Even a
 * single failed commit can leave pending work after cleanup on a pre-bound JDBC connection with auto-commit already disabled.
 * A successful rollback reported after a failed commit releases that transaction's cache block automatically:
 * {@code JdbcTransactionManager} rolls back after a commit failure it translates (such as a deadlock or a serialization
 * failure), and so does any transaction manager with {@code setRollbackOnCommitFailure(true)}, while a
 * {@code DataSourceTransactionManager} with its default settings reports every failed commit as an unknown outcome. Records
 * are retained from their first cache-affecting write, without retaining the database resources. Monitor them using
 * {@link com.landawn.abacus.jdbc.SpringTransactionTracker#unresolvedTransactions()}; without confirmed settlement the block
 * remains indefinitely. A block covers the table names written, ignoring case, for the caches of every data source in the
 * process, and the other names sharing their hashed slot (an empty table name blocks every table). For a JPA transaction
 * without a tracker, whose holder records nothing,
 * the commit is observed through Spring's transaction synchronization of the innermost Spring transaction on the thread
 * only: with synchronization disabled, the second invalidation runs right away too, and for a write to the data source of
 * an enclosing transaction while another transaction manager's transaction runs inside it, at that inner transaction's
 * commit, or not at all if it rolls back; a result read outside the transaction before the enclosing commit may then stay
 * cached, until it expires. A result whose read overlapped an invalidation of its table is not stored (it may hold the rows
 * as they were before): also one that the method's own nested write invalidated.</p>
 *
 * <p>Per its {@code @Target}, this annotation can be placed on an individual DAO method, or on a
 * DAO interface type to enable caching for every method whose name matches {@link #filter()}. A
 * method-level annotation replaces the type-level one for that method: elements it omits take their
 * defaults, not the type-level values. When several interfaces of the DAO (the DAO interface itself first, then
 * its super-interfaces) declare a type-level {@code @CacheResult}, a method uses the first one whose
 * {@link #filter()} matches its name, as for {@link PerfLog @PerfLog} and {@link SqlLogEnabled @SqlLogEnabled};
 * if that one is disabled, the method is not cached, so a sub-interface can opt matching methods out of a
 * super-interface's caching.</p>
 *
 * <p><strong>Restriction:</strong> {@code @CacheResult} (together with {@link Cache @Cache} and
 * {@link RefreshCache @RefreshCache}) is only honored on cacheable DAOs &mdash; {@code NonUpdateDao} or
 * {@code ReadOnlyDao} subtypes (and their {@code Unchecked} variants). Applying it to a DAO that supports
 * update/delete operations at the type level, or enabling it for an eligible method on such a DAO, fails with
 * {@code UnsupportedOperationException} at DAO initialization time. Use {@link Cache @Cache} on the same
 * DAO interface to configure the shared cache pool (capacity, eviction sweep interval, and
 * implementation), and {@link RefreshCache @RefreshCache} on selected methods to invalidate cached
 * entries; {@code @CacheResult} only controls whether and how an individual result is cached.</p>
 *
 * <p><strong>Init-time validation:</strong> enabling caching (through a method-level annotation or a
 * type-level one whose {@link #filter()} matches the method) for a method whose return type is not
 * cacheable ({@code void}/{@code Void}, {@code Iterator}, or any lazy sequence type &mdash; the Abacus
 * {@code Stream}, {@code EntryStream} and {@code Seq} as well as {@code java.util.stream.BaseStream})
 * causes DAO initialization to fail with {@code UnsupportedOperationException}. In addition, {@link #maxLiveTimeMillis()}
 * and {@link #maxIdleTimeMillis()} must be {@code >= 0}, and {@code 0 <= minSize() <= maxSize()} must hold;
 * violating either range rule fails DAO initialization with {@code UnsupportedOperationException} as well.
 * These range checks run only when caching is effectively enabled for the method, so an
 * {@code @CacheResult(enabled = false)} carrying out-of-range values is ignored rather than rejected.
 * Note that the built-in {@link com.landawn.abacus.jdbc.Jdbc.DefaultDaoCache} (the default
 * {@link Cache#impl()}) requires both time limits to be <em>positive</em>: a value of {@code 0} passes
 * DAO initialization, but storing an eligible non-null result then fails with
 * {@code IllegalArgumentException}.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface UserDao extends NonUpdateCrudDao<User, Long, UserDao> {
 *     // Cache individual user lookups for 30 minutes
 *     @CacheResult(enabled = true, maxLiveTimeMillis = 1800000, maxIdleTimeMillis = 600000)
 *     @Query("SELECT * FROM users WHERE id = :id")
 *     User findById(@Bind("id") Long id) throws SQLException;
 *
 *     // Cache list results with size restrictions
 *     @CacheResult(enabled = true, maxLiveTimeMillis = 300000, minSize = 1, maxSize = 100)
 *     @Query("SELECT * FROM users WHERE status = :status")
 *     List<User> findByStatus(@Bind("status") String status) throws SQLException;
 *
 *     // Use Kryo serialization for complex objects
 *     @CacheResult(enabled = true, maxLiveTimeMillis = 3600000, serialization = CacheSerialization.KRYO)
 *     @Query("SELECT * FROM user_profiles WHERE user_id = :userId")
 *     UserProfile getProfile(@Bind("userId") Long userId) throws SQLException;
 * }
 *
 * // Apply caching to all matching methods at type level
 * @CacheResult(enabled = true, maxLiveTimeMillis = 600000, filter = {"find.*", "get.*"})
 * public interface ProductDao extends NonUpdateCrudDao<Product, Long, ProductDao> {
 *     // All find* and get* methods will be cached
 * }
 * }</pre>
 *
 * <p>Cache invalidation strategies:</p>
 * <ul>
 *   <li>Time-based: Entries expire after {@code maxLiveTimeMillis} milliseconds</li>
 *   <li>Idle-based: Entries expire if not accessed for {@code maxIdleTimeMillis} milliseconds</li>
 *   <li>Size-based: Only cache results within size bounds (for collections, object arrays, maps and datasets)</li>
 *   <li>Manual: Use {@link RefreshCache} annotation on update methods</li>
 * </ul>
 *
 * <p>Note: while a thread-local DAO cache scope opened by {@code JdbcUtil.openDaoCacheScope()}
 * is active, it takes precedence on that thread for query-named methods (names starting with
 * {@code query}/{@code list}/{@code get}/{@code find}/{@code exists}/{@code count}/...) — for those
 * methods this annotation's DAO-level cache is neither read from nor written to until the
 * scope is closed.</p>
 *
 * @see Cache
 * @see RefreshCache
 * @see <a href="https://github.com/EsotericSoftware/kryo">Kryo Serialization</a>
 */
@Documented
@Beta
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.METHOD, ElementType.TYPE })
public @interface CacheResult {

    /**
     * Enables caching when set to {@code true}.
     *
     * <p>The default is {@code false}; set this element explicitly when the annotation should
     * activate cache result handling. At the method level, {@code enabled = false} can also opt
     * a method back out of a type-level {@code @CacheResult(enabled = true)} declaration.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @CacheResult(enabled = false)  // Temporarily disable for debugging
     * @Query("SELECT * FROM users WHERE id = :id")
     * User findById(@Bind("id") Long id) throws SQLException;
     * }</pre>
     *
     * @return {@code true} to enable caching, {@code false} (default) to leave it disabled
     */
    boolean enabled() default false;

    /**
     * Specifies the maximum time (in milliseconds) a cached entry can live.
     * After this time expires, the entry is removed from cache and the next
     * request will execute the query again.
     *
     * <p>The default is {@link JdbcUtil#DEFAULT_CACHE_LIVE_TIME} (30 minutes). Use a positive value: the
     * built-in {@link com.landawn.abacus.jdbc.Jdbc.DefaultDaoCache} rejects {@code 0} when storing a
     * result (see the type-level documentation).</p>
     *
     * <p>Common time duration values:</p>
     * <ul>
     *   <li>5 minutes: {@code 300000}</li>
     *   <li>30 minutes: {@code 1800000}</li>
     *   <li>1 hour: {@code 3600000}</li>
     *   <li>24 hours: {@code 86400000}</li>
     * </ul>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Cache for 15 minutes
     * @CacheResult(enabled = true, maxLiveTimeMillis = 900000)
     * @Query("SELECT * FROM configurations WHERE key = :key")
     * Config getConfig(@Bind("key") String key) throws SQLException;
     * }</pre>
     *
     * @return the maximum cache entry lifetime in milliseconds; defaults to
     *         {@link JdbcUtil#DEFAULT_CACHE_LIVE_TIME} (30 minutes)
     */
    long maxLiveTimeMillis() default JdbcUtil.DEFAULT_CACHE_LIVE_TIME;

    /**
     * Specifies the maximum idle time (in milliseconds) for a cached entry.
     * If an entry is not accessed within this time, it expires and is removed.
     * This is useful for frequently accessed data that should expire if unused.
     *
     * <p>The entry expires when either {@code maxLiveTimeMillis} or {@code maxIdleTimeMillis}
     * is exceeded, whichever comes first.</p>
     *
     * <p>The default is {@link JdbcUtil#DEFAULT_CACHE_MAX_IDLE_TIME} (3 minutes). Use a positive value: the
     * built-in {@link com.landawn.abacus.jdbc.Jdbc.DefaultDaoCache} rejects {@code 0} when storing a
     * result (see the type-level documentation).</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Expire if not accessed for 10 minutes
     * @CacheResult(enabled = true, maxLiveTimeMillis = 3600000, maxIdleTimeMillis = 600000)
     * @Query("SELECT * FROM user_sessions WHERE token = :token")
     * UserSession getSession(@Bind("token") String token) throws SQLException;
     * }</pre>
     *
     * @return the maximum idle time in milliseconds; defaults to
     *         {@link JdbcUtil#DEFAULT_CACHE_MAX_IDLE_TIME} (3 minutes)
     */
    long maxIdleTimeMillis() default JdbcUtil.DEFAULT_CACHE_MAX_IDLE_TIME;

    /**
     * Specifies the minimum size requirement for caching results.
     * Results with fewer elements than this value will not be cached.
     * The size bound applies to {@code Collection} and object-array results (their element count),
     * {@code Map} results (their entry count) and {@code Dataset} results (their row count); any other
     * single-valued result, including a primitive array such as a {@code byte[]} column value, is cached
     * regardless of {@code minSize}.
     *
     * <p>This is useful to avoid caching overhead for very small result sets
     * that are cheap to query.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Only cache if result has at least 10 items
     * @CacheResult(enabled = true, minSize = 10)
     * @Query("SELECT * FROM products WHERE category = :category")
     * List<Product> findByCategory(@Bind("category") String category) throws SQLException;
     * }</pre>
     *
     * @return the minimum result size to cache; the default {@code 0} means no minimum
     */
    int minSize() default 0;

    /**
     * Specifies the maximum size limit for caching results.
     * Results with more elements than this value will not be cached.
     * The size bound applies to {@code Collection} and object-array results (their element count),
     * {@code Map} results (their entry count) and {@code Dataset} results (their row count); any other
     * single-valued result, including a primitive array such as a {@code byte[]} column value, is cached
     * regardless of {@code maxSize}.
     *
     * <p>This prevents memory issues from caching very large result sets
     * and ensures predictable memory usage.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Don't cache if result has more than 1000 items
     * @CacheResult(enabled = true, maxSize = 1000)
     * @Query("SELECT * FROM orders WHERE date >= :startDate")
     * List<Order> findOrdersSince(@Bind("startDate") Date date) throws SQLException;
     * }</pre>
     *
     * @return the maximum result size to cache; the default {@link Integer#MAX_VALUE} means no maximum
     */
    int maxSize() default Integer.MAX_VALUE;

    /**
     * Specifies the serialization strategy for cache storage and retrieval.
     * This determines how objects are copied when stored in or retrieved from cache.
     *
     * <p>Available options:</p>
     * <ul>
     *   <li>{@link CacheSerialization#NONE} (default) - No serialization, stores direct references</li>
     *   <li>{@link CacheSerialization#KRYO} - Uses Kryo to deep-copy result graphs</li>
     *   <li>{@link CacheSerialization#JSON} - Uses JSON round trips to create copies</li>
     * </ul>
     *
     * <p>With {@link CacheSerialization#KRYO} or {@link CacheSerialization#JSON}, collections, maps and
     * populated optional/nullable wrappers are copied even when they implement
     * {@link com.landawn.abacus.util.Immutable}. Other top-level {@code Immutable} values are stored and returned
     * as-is; their contents must be deeply immutable to avoid sharing mutable state. Values without this
     * marker pass through the selected serializer even if their Java types are immutable, except that
     * {@link CacheSerialization#JSON} shares {@code java.time} values as-is. Selecting
     * {@link CacheSerialization#KRYO} requires an available Kryo parser whenever copying is needed; this is
     * not checked at DAO creation, so missing dependencies or parser initialization failures surface at
     * invocation time with {@code UnsupportedOperationException}, whose message quotes the failure and states
     * whether Kryo, one of its dependencies, or its initialization is at fault. Copying the supported immutable wrappers
     * also requires Kryo's Unsafe support and permission for its memory-access operations under the JVM's
     * runtime policy. See {@link CacheSerialization#KRYO} for the supported copying contracts.</p>
     *
     * <p>The default of this annotation is {@link CacheSerialization#NONE}. Unannotated methods used with
     * the thread-local DAO cache select Kryo when its parser is available and JSON otherwise.</p>
     *
     * <p>JSON copying preserves comparator instances for supported standard and library sorted containers
     * reached through array, collection, map and optional/nullable container paths, while copying their contents.
     * Custom concrete sorted classes reached by this comparator-preserving copy retain their constructor-created
     * comparator and state; the copied contents are checked for changes in size, comparison keys and order.
     * Plain {@code ImmutableSet}, {@code ImmutableMap} and {@code ImmutableBiMap} copies keep the source's
     * iteration order, and a {@code null} map key in such a container tree stays a {@code null} key. Natural-order
     * container trees without a custom comparator, one of those wrappers or a {@code null} map key follow
     * the ordinary JSON parser path. A {@code java.sql.Timestamp}, {@code Calendar} or {@code java.time} value is
     * copied exactly (nanos, zone and offset included) as the result, as an optional/nullable's value and anywhere
     * in such a container tree. JSON does not preserve shared object identity
     * or cycles. Shared comparator state and bean-property copying retain the limitations described in
     * {@link CacheSerialization#JSON}.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Use Kryo for deep copying complex objects
     * @CacheResult(enabled = true, serialization = CacheSerialization.KRYO)
     * @Query("SELECT * FROM user_profiles WHERE user_id = :userId")
     * UserProfile getComplexProfile(@Bind("userId") Long userId) throws SQLException;
     *
     * // Use JSON copying for cache isolation
     * @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
     * @Query("SELECT * FROM audit_logs WHERE id = :id")
     * AuditLog getAuditLog(@Bind("id") Long id) throws SQLException;
     * }</pre>
     *
     * @return the serialization strategy
     * @see <a href="https://github.com/EsotericSoftware/kryo">Kryo Serialization</a>
     */
    CacheSerialization serialization() default CacheSerialization.NONE;

    /**
     * Whether calls made by this cached method may also use inherited type-level result caching.
     * The default preserves caching at every matching method, including forwarding overloads.
     * Set this to {@code false} to cache the outer result without also retaining copies at each
     * nested forwarding method, for example:
     *
     * <pre>{@code
     * @CacheResult(enabled = true, serialization = CacheSerialization.JSON, cacheNestedResults = false)
     * public interface UserDao extends ReadOnlyCrudDao<User, Long, UserDao> {
     * }
     * }</pre>
     *
     * <p>Suppression lasts only while the method body executes on the current thread and also applies
     * to calls to other DAO proxies. Explicit method-level {@code @CacheResult} annotations remain
     * effective for the annotated method itself; they do not lift an outer method's suppression of
     * inherited caching on further nested calls. {@code @RefreshCache} invalidation, {@code @Handler}
     * invocation and local thread caches are unaffected. Calls on another thread have their own scope.
     * A custom cache receives no result lookup or insertion for suppressed inherited methods.</p>
     *
     * @return {@code true} to preserve nested type-level result caching; {@code false} to suppress it
     */
    boolean cacheNestedResults() default true;

    /**
     * Specifies filter patterns for methods when the annotation is applied at the type level.
     * Only methods whose names match at least one of these patterns will be cached.
     *
     * <p>Each entry matches when the method name starts with that entry (case-insensitive), or when the
     * entry matches the full method name as a regular expression. Multiple patterns are combined with OR logic.</p>
     *
     * <p>This filter is ignored when the annotation is applied at the method level.</p>
     *
     * <p>When the annotation is applied at the type level, methods whose names contain
     * {@code "page"} or {@code "paginate"} (case-insensitive) are always skipped from caching,
     * regardless of this filter.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @CacheResult(enabled = true,
     *              maxLiveTimeMillis = 600000,
     *              filter = {"find.*", "get.*", "load.*", "fetch.*"})
     * public interface UserDao extends NonUpdateCrudDao<User, Long, UserDao> {
     *     // Matched by the filter - results are cached:
     *     @Query("SELECT * FROM users WHERE status = :status")
     *     List<User> findByStatus(@Bind("status") String status) throws SQLException;
     *
     *     @Query("SELECT * FROM users WHERE email = :email")
     *     User getByEmail(@Bind("email") String email) throws SQLException;
     * }
     *
     * // Inherited built-in reads also match the filter and are cached, for example:
     * //     User user = userDao.getOrNull(userId);                 // matches "get.*"
     * //     com.landawn.abacus.util.u.Optional<User> first = userDao.findFirst(cond); // matches "find.*"
     * }</pre>
     *
     * @return array of method name patterns to cache; the default targets common read methods:
     *         {@code query}, {@code queryFor}, {@code list}, {@code get}, {@code batchGet},
     *         {@code find}, {@code findFirst}, {@code findOnlyOne}, {@code exists}, {@code notExists}, and {@code count}
     */
    String[] filter() default { "query", "queryFor", "list", "get", "batchGet", "find", "findFirst", "findOnlyOne", "exists", "notExists", "count" };
}
