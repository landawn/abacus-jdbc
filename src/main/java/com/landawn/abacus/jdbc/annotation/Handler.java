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
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.jdbc.EmptyHandler;
import com.landawn.abacus.jdbc.Jdbc;
import com.landawn.abacus.jdbc.dao.DaoBase;

/**
 * Defines an interceptor handler for DAO methods or entire DAO interfaces.
 * Handlers provide AOP-like functionality to intercept and modify the behavior
 * of DAO method invocations, useful for cross-cutting concerns like logging,
 * performance monitoring, security checks, or inspection/mutation of mutable result objects.
 *
 * <p><strong>Note:</strong> This feature is marked as {@code @Beta} and may change in future versions.</p>
 *
 * <p>Handlers can be applied at two levels:</p>
 * <ul>
 *   <li><strong>Method level:</strong> Applies only to the specific annotated method</li>
 *   <li><strong>Type level:</strong> Applies to all methods in the DAO interface (with filtering options)</li>
 * </ul>
 *
 * <p>This annotation is {@link java.lang.annotation.Repeatable @Repeatable}: multiple {@code @Handler}
 * declarations may be placed on the same method or type, in which case they are collected into a
 * {@link Handlers} container and each handler is applied in declaration order.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Custom handler implementation
 * public class LoggingHandler implements Jdbc.Handler<UserDao> {
 *     @Override
 *     public void beforeInvoke(UserDao proxy, Object[] args, Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
 *         logger.info("Calling method: {}", methodSignature._1.getName());
 *     }
 *
 *     @Override
 *     public void afterInvoke(Object result, UserDao proxy, Object[] args, Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
 *         logger.info("Method completed: {}", methodSignature._1.getName());
 *     }
 * }
 *
 * // Apply handler to entire DAO
 * @Handler(impl = LoggingHandler.class)
 * public interface UserDao extends CrudDao<User, Long, UserDao> {
 *     // All methods will be intercepted by LoggingHandler
 * }
 *
 * // Apply handler to specific method
 * public interface OrderDao extends CrudDao<Order, Long, OrderDao> {
 *     @Handler(impl = PerformanceHandler.class)
 *     @Query("SELECT * FROM orders WHERE total > :amount")
 *     List<Order> findLargeOrders(@Bind("amount") BigDecimal amount) throws SQLException;
 * }
 *
 * // Multiple handlers with filtering
 * @Handler(impl = LoggingHandler.class, filter = {"find.*", "query.*"})
 * @Handler(impl = CacheHandler.class, filter = {"get.*", "find.*"})
 * public interface ProductDao extends CrudDao<Product, Long, ProductDao> {
 *     // Methods matching filters will be intercepted
 * }
 * }</pre>
 *
 * @see Handlers
 * @see Jdbc.Handler
 * @see NonDBOperation
 */
@Documented
@Beta
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.METHOD, ElementType.TYPE })
@Repeatable(Handlers.class)
public @interface Handler {

    /**
     * Specifies a qualifier used to look up a pre-registered handler instance from
     * {@code HandlerFactory} (or a DAO-class handler map). When non-empty, the qualifier takes
     * precedence over {@link #impl()}; the framework resolves the handler by this name instead
     * of instantiating one from the {@code impl} attribute.
     *
     * <p>This is useful when you want to register a handler instance once (with custom
     * configuration or dependencies) and then reference it from multiple DAO interfaces or
     * methods by name.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Register handler instances at startup (e.g., HandlerFactory.register("auditHandler", new AuditHandler(...)))
     * @Handler(qualifier = "auditHandler")
     * public interface ConfigDao extends CrudDao<Config, Long, ConfigDao> {
     *     // The handler registered under "auditHandler" will be applied
     * }
     * }</pre>
     *
     * @return the handler qualifier name; empty (default) means resolve a handler by {@link #impl()} instead
     */
    String qualifier() default "";

    /**
     * Specifies the handler implementation class.
     * The class must implement {@link Jdbc.Handler} with the appropriate DAO type parameter.
     *
     * <p>The handler lifecycle methods are called in this order:</p>
     * <ol>
     *   <li>{@code beforeInvoke()} - Before the actual method invocation</li>
     *   <li>Actual DAO method execution</li>
     *   <li>{@code afterInvoke()} - After the method completes (whether successfully or with an exception);
     *       it can observe the result but cannot replace the returned reference</li>
     * </ol>
     *
     * <p>Example handler implementation:</p>
     * <pre>{@code
     * public class SecurityHandler implements Jdbc.Handler<UserDao> {
     *     @Override
     *     public void beforeInvoke(UserDao proxy, Object[] args, Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
     *         // Check user permissions
     *         if (!hasPermission(methodSignature._1)) {
     *             throw new SecurityException("Access denied");
     *         }
     *     }
     *
     *     @Override
     *     public void afterInvoke(Object result, UserDao proxy, Object[] args, Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
     *         // Can filter or log results
     *         filterSensitiveData(result);
     *     }
     * }
     * }</pre>
     *
     * @return the handler class, defaults to {@link EmptyHandler} (no-op)
     */
    @SuppressWarnings("rawtypes")
    Class<? extends Jdbc.Handler<? extends DaoBase>> impl() default EmptyHandler.class; //NOSONAR

    /**
     * Specifies filter patterns for methods when the annotation is applied at the class level.
     * Only methods whose names match at least one of these patterns will be intercepted by this handler.
     *
     * <p>Each entry matches when the method name starts with that entry (case-insensitive) or when the
     * entry matches the full method name as a regular expression. Multiple patterns are combined with
     * OR logic — a method is intercepted if it matches at least one entry.</p>
     *
     * <p>This filter is ignored when the annotation is applied at the method level.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Handler(impl = ReadOnlyHandler.class, filter = {"find.*", "get.*", "query.*"})
     * @Handler(impl = AuditHandler.class, filter = {"save.*", "update.*", "delete.*"})
     * public interface UserDao extends CrudDao<User, Long, UserDao> {
     *     // Read methods will use ReadOnlyHandler
     *     // Write methods will use AuditHandler
     * }
     * }</pre>
     *
     * @return array of filter patterns (default matches all methods)
     */
    String[] filter() default { ".*" };

    /**
     * Specifies whether this handler should only be applied to external invocations of the DAO.
     * When {@code true}, the handler will not be triggered for internal method calls within the DAO.
     *
     * <p>This is useful for handlers that should only apply when the DAO is called from
     * outside code, not when DAO methods call each other internally.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Handler(impl = TransactionHandler.class, externalCallsOnly = true)
     * public interface UserDao extends CrudDao<User, Long, UserDao> {
     *     @Query("SELECT * FROM users WHERE id = :id")
     *     User findById(@Bind("id") Long id) throws SQLException;
     *
     *     default User findActiveById(Long id) throws SQLException {
     *         User user = findById(id);   // TransactionHandler NOT applied here
     *         return user != null && user.isActive() ? user : null;
     *     }
     * }
     *
     * // External call
     * User user = userDao.findById(123L);   // TransactionHandler IS applied here
     * }</pre>
     *
     * @return {@code true} if handler only applies to external calls, {@code false} (default) otherwise
     */
    boolean externalCallsOnly() default false;
}
