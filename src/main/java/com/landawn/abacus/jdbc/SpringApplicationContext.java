/*
 * Copyright (c) 2019, Haiyang Li.
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

package com.landawn.abacus.jdbc;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import com.landawn.abacus.annotation.Internal;

/**
 * A utility class that provides access to Spring's ApplicationContext for bean retrieval within the JDBC framework.
 *
 * <p>This class is used internally by the JDBC framework to integrate with Spring's dependency injection container,
 * enabling the framework to retrieve Spring-managed beans such as DataSources, TransactionManagers, and custom
 * DAO implementations.</p>
 *
 * <p>The ApplicationContext is automatically injected by Spring when this class is registered as a Spring bean,
 * and is stored in a process-wide holder shared by all instances — so the instance the framework constructs
 * internally (e.g. in {@code Jdbc.HandlerFactory}) can resolve beans once any instance has been registered.
 * Once injected, it provides methods to retrieve beans by name or type from the Spring container.</p>
 *
 * <p><b>Framework Integration:</b></p>
 * <p>This class must be registered as a Spring bean for the JDBC framework to access Spring-managed resources.
 * The framework uses this to look up DataSources and other dependencies configured in the Spring context.</p>
 *
 * <p><b>Spring Configuration Example:</b></p>
 * <pre>{@code
 * @Configuration
 * public class JdbcConfig {
 *     @Bean
 *     public SpringApplicationContext springApplicationContext() {
 *         return new SpringApplicationContext();
 *     }
 *
 *     @Bean
 *     public DataSource dataSource() {
 *         // Configure and return DataSource
 *         return new HikariDataSource(config);
 *     }
 * }
 * }</pre>
 *
 * <p><b>Requirements:</b></p>
 * <ul>
 *   <li>Spring Framework must be present in the classpath</li>
 *   <li>This class must be registered as a Spring bean</li>
 *   <li>The ApplicationContext will be automatically injected by Spring</li>
 * </ul>
 *
 * <p>Note: This class is intended for internal framework use only and should not be used directly
 * by application code.</p>
 *
 * @see org.springframework.context.ApplicationContext
 */
@Internal
public final class SpringApplicationContext {

    // Held statically so that registering ANY SpringApplicationContext bean makes the context visible
    // to the framework's internally constructed instance too: Jdbc.HandlerFactory builds its own
    // instance in a static initializer, which Spring never injects. Spring ignores @Autowired on
    // static fields, so injection is routed through the instance setter below into this holder.
    private static volatile ApplicationContext appContext; // NOSONAR

    /**
     * Invoked by Spring to supply the {@link ApplicationContext} when an instance of this class is
     * registered as a Spring bean. The context is stored in a process-wide holder shared by all
     * instances (including the one the framework constructs internally).
     *
     * @param applicationContext the Spring application context
     */
    @Autowired // NOSONAR
    public void setApplicationContext(final ApplicationContext applicationContext) {
        appContext = applicationContext; // NOSONAR
    }

    /**
     * Constructs a new {@code SpringApplicationContext}.
     *
     * <p>The Spring {@link ApplicationContext} holder is populated by Spring after construction
     * via the {@link Autowired} setter, so calling {@link #getBean(String)} or {@link #getBean(Class)}
     * before Spring has finished injecting any registered instance will return {@code null}.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Typically registered as a Spring @Bean so the ApplicationContext is auto-wired in.
     * SpringApplicationContext ctx = new SpringApplicationContext();
     *
     * // Before Spring injects the ApplicationContext, lookups return null (no exception).
     * Object notReady = ctx.getBean("myDataSource"); // null until Spring finishes injection
     *
     * // After injection, beans are resolved by name or by type.
     * // DataSource ds = ctx.getBean(DataSource.class);
     * }</pre>
     *
     */
    public SpringApplicationContext() {
    }

    /**
     * Retrieves a bean from the Spring ApplicationContext by its name.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Object dataSource = springAppContext.getBean("myDataSource");
     * }</pre>
     *
     * <p>Returns {@code null} only when the context has not been initialized; if the context is
     * initialized but no bean with the given name exists, {@code NoSuchBeanDefinitionException}
     * is thrown.</p>
     *
     * @param name the name of the bean to retrieve
     * @return the bean instance, or {@code null} if the ApplicationContext is not initialized
     * @throws org.springframework.beans.factory.NoSuchBeanDefinitionException if no bean with the specified name is found
     *
     * @see ApplicationContext#getBean(String)
     */
    public Object getBean(final String name) {
        final ApplicationContext context = appContext;

        return context == null ? null : context.getBean(name);
    }

    /**
     * Retrieves a bean from the Spring ApplicationContext by its type.
     * This method returns a single bean of the specified type if exactly one exists in the context.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * DataSource dataSource = springAppContext.getBean(DataSource.class);
     * }</pre>
     *
     * @param <T> the bean type to be retrieved from the Spring context
     * @param requiredType the class object representing the type of bean to retrieve
     * @return the bean instance, or {@code null} if the ApplicationContext is not initialized
     * @throws org.springframework.beans.factory.NoSuchBeanDefinitionException if no bean of the given type exists
     * @throws org.springframework.beans.factory.NoUniqueBeanDefinitionException if more than one bean of the given type exists
     *
     * @see ApplicationContext#getBean(Class)
     */
    public <T> T getBean(final Class<T> requiredType) {
        final ApplicationContext context = appContext;

        return context == null ? null : context.getBean(requiredType);
    }

    /**
     * Retrieves a bean from the Spring ApplicationContext by name, requiring it to be of the given type.
     *
     * <p>This is the typed counterpart of {@link #getBean(String)}: the bean is returned already cast to
     * {@code requiredType}, removing the need for a cast at the call site.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * DataSource dataSource = springAppContext.getBean("myDataSource", DataSource.class);
     * }</pre>
     *
     * @param <T> the bean type to be retrieved from the Spring context
     * @param name the name of the bean to retrieve
     * @param requiredType the class object representing the required type of the bean
     * @return the bean instance, or {@code null} if the ApplicationContext is not initialized
     * @throws org.springframework.beans.factory.NoSuchBeanDefinitionException if no bean with the specified name is found
     * @throws org.springframework.beans.factory.BeanNotOfRequiredTypeException if the bean is not of the required type
     *
     * @see ApplicationContext#getBean(String, Class)
     */
    public <T> T getBean(final String name, final Class<T> requiredType) {
        final ApplicationContext context = appContext;

        return context == null ? null : context.getBean(name, requiredType);
    }
}
