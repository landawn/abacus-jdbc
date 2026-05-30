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
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Container annotation for multiple {@link Handler} declarations on the same DAO method or type.
 * Synthesized automatically by the Java compiler from the {@link java.lang.annotation.Repeatable}
 * declaration on {@link Handler}; direct use is rarely necessary.
 *
 * <p>The DAO proxy ({@code DaoImpl}) reads the {@code @HandlerList} (whether explicit or
 * compiler-synthesized), resolves each {@link Handler#qualifier() qualifier} or
 * {@link Handler#type() type} to a {@code Jdbc.Handler} instance, then composes them into an
 * <em>onion-style</em> interceptor chain around the actual DAO call: outer handlers see the
 * invocation first on the way in and last on the way out (see "Execution flow" below).</p>
 * 
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * @Handler(type = LoggingHandler.class)
 * @Handler(type = SecurityHandler.class)
 * @Handler(type = CacheHandler.class, filter = {"find.*", "get.*"})
 * public interface UserDao extends CrudDao<User, Long, SqlBuilder.PSC, UserDao> {
 *     // All three handlers will be applied according to their configurations
 * }
 * }</pre>
 * 
 * <p>The handlers are executed in the order they are declared:</p>
 * <ol>
 *   <li>{@code beforeInvoke()} is called in declaration order.</li>
 *   <li>{@code afterInvoke()} is called in reverse declaration order.</li>
 *   <li>This creates a nested interception pattern.</li>
 * </ol>
 * 
 * <p>Execution flow example with three handlers A, B, C:</p>
 * <pre>
 * A.beforeInvoke()
 *   B.beforeInvoke()
 *     C.beforeInvoke()
 *       [Actual method execution]
 *     C.afterInvoke()
 *   B.afterInvoke()
 * A.afterInvoke()
 * </pre>
 *
 * @see Handler
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.METHOD, ElementType.TYPE })
public @interface HandlerList {

    /**
     * Returns the array of {@link Handler} annotations contained in this list.
     * The handlers will be processed in the order they appear in this array.
     *
     * <p>When multiple handlers are applied to a DAO or method, they form
     * a chain of interceptors. Each handler may:</p>
     * <ul>
     *   <li>Inspect arguments and run custom logic in {@code beforeInvoke}.</li>
     *   <li>Mutate mutable elements within the {@code args} array (e.g., entity fields).</li>
     *   <li>Observe the return value and run custom logic in {@code afterInvoke}.</li>
     * </ul>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Accessing HandlerList programmatically via reflection
     * HandlerList handlers = MyDao.class.getAnnotation(HandlerList.class);
     * if (handlers != null) {
     *     for (Handler handler : handlers.value()) {
     *         System.out.println("Handler type: " + handler.type());
     *         System.out.println("Filter: " + Arrays.toString(handler.filter()));
     *     }
     * }
     *
     * // Multiple handlers are automatically wrapped in HandlerList
     * @Handler(type = LoggingHandler.class)
     * @Handler(type = SecurityHandler.class)
     * @Handler(type = CacheHandler.class)
     * public interface UserDao extends CrudDao<User, Long, SqlBuilder.PSC, UserDao> {
     *     // The compiler wraps these in a HandlerList annotation
     * }
     * }</pre>
     *
     * @return the array of {@link Handler} annotations contained in this list
     */
    Handler[] value();
}
