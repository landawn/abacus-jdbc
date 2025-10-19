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

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.jdbc.EmptyHandler;
import com.landawn.abacus.jdbc.Jdbc;
import com.landawn.abacus.jdbc.dao.Dao;

/**
 * Defines an interceptor handler for DAO methods or entire DAO interfaces.
 * Handlers provide AOP-like functionality to intercept and modify the behavior
 * of DAO method invocations, useful for cross-cutting concerns like logging,
 * performance monitoring, security checks, or result transformation.
 * 
 * <p><strong>Note:</strong> This feature is marked as {@code @Beta} and may undergo
 * changes in future versions.</p>
 * 
 * <p>Handlers can be applied at two levels:</p>
 * <ul>
 *   <li><strong>Method level:</strong> Applies only to the specific annotated method</li>
 *   <li><strong>Type level:</strong> Applies to all methods in the DAO interface (with filtering options)</li>
 * </ul>
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * // Custom handler implementation
 * public class LoggingHandler extends Jdbc.Handler<UserDao> {
 *     @Override
 *     public void beforeInvoke(UserDao dao, Method method, Object[] args) {
 *         logger.info("Calling method: " + method.getName());
 *     }
 *     
 *     @Override
 *     public void afterInvoke(Object result, UserDao dao, Method method, Object[] args) {
 *         logger.info("Method completed: " + method.getName());
 *     }
 * }
 * 
 * // Apply handler to entire DAO
 * @Handler(type = LoggingHandler.class)
 * public interface UserDao extends CrudDao<User, Long> {
 *     // All methods will be intercepted by LoggingHandler
 * }
 * 
 * // Apply handler to specific method
 * public interface OrderDao extends CrudDao<Order, Long> {
 *     @Handler(type = PerformanceHandler.class)
 *     @Query("SELECT * FROM orders WHERE total > :amount")
 *     List<Order> findLargeOrders(@Bind("amount") BigDecimal amount);
 * }
 * 
 * // Multiple handlers with filtering
 * @Handler(type = LoggingHandler.class, filter = {"find.*", "query.*"})
 * @Handler(type = CacheHandler.class, filter = {"get.*", "find.*"})
 * public interface ProductDao extends CrudDao<Product, Long> {
 *     // Methods matching filters will be intercepted
 * }
 * }</pre>
 *
 * @see HandlerList
 * @see Jdbc.Handler
 * @see NonDBOperation
 * @since 0.8
 */
@Beta
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.METHOD, ElementType.TYPE })
@Repeatable(HandlerList.class)
public @interface Handler {

    /**
     * Specifies a qualifier to distinguish between multiple handlers of the same type.
     * This is useful when you need different configurations of the same handler class.
     * 
     * <p>Example:</p>
     * <pre>{@code
     * @Handler(type = CacheHandler.class, qualifier = "shortTerm")
     * @Handler(type = CacheHandler.class, qualifier = "longTerm")
     * public interface ConfigDao extends CrudDao<Config, Long> {
     *     // Different cache configurations can be applied based on qualifier
     * }
     * }</pre>
     *
     * @return the qualifier string, empty by default
     */
    String qualifier() default "";

    /**
     * Specifies the handler implementation class.
     * The class must extend {@link Jdbc.Handler} with the appropriate DAO type parameter.
     * 
     * <p>The handler lifecycle methods are called in this order:</p>
     * <ol>
     *   <li>{@code beforeInvoke()} - Before the actual method invocation</li>
     *   <li>Actual DAO method execution</li>
     *   <li>{@code afterInvoke()} - After successful completion (with result)</li>
     *   <li>{@code onError()} - If an exception occurs (instead of afterInvoke)</li>
     * </ol>
     * 
     * <p>Example handler implementation:</p>
     * <pre>{@code
     * public class SecurityHandler extends Jdbc.Handler<BaseDao> {
     *     @Override
     *     public void beforeInvoke(BaseDao dao, Method method, Object[] args) {
     *         // Check user permissions
     *         if (!hasPermission(method)) {
     *             throw new SecurityException("Access denied");
     *         }
     *     }
     *     
     *     @Override
     *     public Object afterInvoke(Object result, BaseDao dao, Method method, Object[] args) {
     *         // Can modify or filter results
     *         return filterSensitiveData(result);
     *     }
     * }
     * }</pre>
     *
     * @return the handler class, defaults to {@link EmptyHandler} (no-op)
     */
    @SuppressWarnings("rawtypes")
    Class<? extends Jdbc.Handler<? extends Dao>> type() default EmptyHandler.class; //NOSONAR

    /**
     * Specifies filter patterns to determine which methods this handler applies to.
     * Only applicable when the annotation is used at the type (interface) level.
     * 
     * <p>Filters can be:</p>
     * <ul>
     *   <li>Regular expressions (e.g., {@code "find.*"} matches all methods starting with "find")</li>
     *   <li>Exact method names (case-insensitive contains match)</li>
     *   <li>Default {@code ".*"} matches all methods</li>
     * </ul>
     * 
     * <p>Multiple filters are joined with OR logic - a method matches if it matches ANY filter.</p>
     * 
     * <p>Example:</p>
     * <pre>{@code
     * @Handler(type = ReadOnlyHandler.class, filter = {"find.*", "get.*", "query.*"})
     * @Handler(type = AuditHandler.class, filter = {"save.*", "update.*", "delete.*"})
     * public interface UserDao extends CrudDao<User, Long> {
     *     // Read methods will use ReadOnlyHandler
     *     // Write methods will use AuditHandler
     * }
     * }</pre>
     *
     * @return array of filter patterns, default matches all methods
     */
    String[] filter() default { ".*" };

    /**
     * Specifies whether this handler should only be applied to external invocations of the DAO.
     * When {@code true}, the handler will not be triggered for internal method calls within the DAO.
     * 
     * <p>This is useful for handlers that should only apply when the DAO is called from
     * outside code, not when DAO methods call each other internally.</p>
     * 
     * <p>Example:</p>
     * <pre>{@code
     * @Handler(type = TransactionHandler.class, isForInvokeFromOutsideOfDaoOnly = true)
     * public interface UserDao extends CrudDao<User, Long> {
     *     @Query("SELECT * FROM users WHERE id = :id")
     *     User findById(@Bind("id") Long id);
     *     
     *     default User findActiveById(Long id) {
     *         User user = findById(id);  // TransactionHandler NOT applied here
     *         return user != null && user.isActive() ? user : null;
     *     }
     * }
     * 
     * // External call
     * User user = userDao.findById(123L);  // TransactionHandler IS applied here
     * }</pre>
     *
     * @return {@code true} if handler only applies to external calls, {@code false} otherwise
     */
    boolean isForInvokeFromOutsideOfDaoOnly() default false;
}