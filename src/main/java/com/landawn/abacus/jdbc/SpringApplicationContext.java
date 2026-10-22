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

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.BeanNotOfRequiredTypeException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;

import com.landawn.abacus.annotation.Internal;

/**
 * A bridge class that provides access to Spring's ApplicationContext for bean retrieval within the JDBC framework.
 *
 * <p>This class is used internally by {@link Jdbc.HandlerFactory} to integrate handler lookup with
 * Spring's dependency injection container. It lets handler implementations registered as Spring beans
 * be resolved by qualifier or type when a DAO proxy is created.</p>
 *
 * <p>The ApplicationContext is automatically injected by Spring when this class is registered as a Spring bean,
 * and is registered in a process-wide holder shared by all instances — so the instance the framework constructs
 * internally (e.g. in {@code Jdbc.HandlerFactory}) can resolve beans once any instance has been registered.
 * Once injected, it provides the framework's handler registry with methods to retrieve beans by name or
 * type from the Spring container.</p>
 *
 * <p>The holder tracks every registered context in registration order and answers lookups with the most
 * recently registered context that is still registered; registering a context again is a no-op that keeps
 * its position. Closing a context removes only that context, so with a parent and a child context that
 * both register a bridge, closing the child makes lookups fall back to the still-running parent. This keeps
 * the framework from retaining a stopped application and its beans. Destroying a bridge bean also removes
 * its context, covering an initialization failure before the context can publish a close event.</p>
 *
 * <p><b>Framework Integration:</b></p>
 * <p>This class must be registered as a Spring bean for the JDBC framework to access Spring-managed handlers.
 * The framework uses this bridge to look up Spring-managed {@link Jdbc.Handler} instances.</p>
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
public final class SpringApplicationContext implements ApplicationListener<ContextClosedEvent>, DisposableBean {

    /**
     * Contexts supplied to bridge beans and not yet released, oldest first, each at most once (by identity).
     * Guarded by the class monitor.
     */
    // Held statically so that registering ANY SpringApplicationContext bean makes the context visible
    // to the framework's internally constructed instance too: Jdbc.HandlerFactory builds its own
    // instance in a static initializer, which Spring never injects. Spring ignores @Autowired on
    // static fields, so injection is routed through the instance setter below into this registry.
    // A list rather than a single slot: with a parent and a child context that both register a bridge,
    // closing the child must fall back to the still-running parent instead of clearing the holder.
    private static final List<ApplicationContext> registeredContexts = new ArrayList<>(); // NOSONAR

    /**
     * The context lookups use: the last element of {@link #registeredContexts}, or {@code null} when none
     * is registered. Updated under the class monitor whenever the registry changes and marked
     * {@code volatile} so lookups read it without locking.
     */
    private static volatile ApplicationContext appContext; // NOSONAR

    /** The context injected into this bean, guarded by the class monitor along with the shared registry. */
    private ApplicationContext injectedContext;

    /**
     * Invoked by Spring to supply the {@link ApplicationContext} when an instance of this class is
     * registered as a Spring bean. The context is registered in a process-wide holder shared by all
     * instances (including the one the framework constructs internally) and becomes the context used for
     * lookups, unless it is already registered, in which case this call leaves the holder unchanged.
     *
     * <p>Passing {@code null} clears the process-wide holder, removing every registered context, after which
     * bean lookups return {@code null} until another context is supplied.</p>
     *
     * @param applicationContext the Spring application context, or {@code null} to clear the holder.
     */
    @Autowired // NOSONAR
    public void setApplicationContext(final ApplicationContext applicationContext) {
        synchronized (SpringApplicationContext.class) {
            injectedContext = applicationContext;

            if (applicationContext == null) {
                registeredContexts.clear();
            } else if (indexOf(applicationContext) < 0) {
                registeredContexts.add(applicationContext);
            }

            updateCurrentContext();
        }
    }

    /**
     * Removes this bean's context from the holder when Spring destroys it, including after a failed context
     * refresh. Other registered contexts remain available. Calling this more than once is harmless.
     *
     * <p>Two boundaries are accepted: if this bean's own creation fails after the setter has run (for example
     * a {@code BeanPostProcessor} throws), Spring never registers it as disposable, so the holder keeps that
     * context registered until the holder is cleared (a context registered later takes precedence while it
     * remains registered); and destroying this bean individually while its
     * context is still running removes that running context from the holder, because Spring does not tell a
     * destruction callback why it is invoked.</p>
     */
    @Override
    public void destroy() {
        synchronized (SpringApplicationContext.class) {
            // A failed refresh destroys initialized singletons without publishing ContextClosedEvent.
            // Only this bean's injected context belongs to its destruction callback.
            if (injectedContext != null) {
                unregister(injectedContext);
            }

            injectedContext = null;
        }
    }

    /**
     * Removes the closed context from the holder; any other registered context (for example the parent of
     * a closed child context) remains available.
     *
     * @param event the context shutdown event.
     */
    @Override
    public void onApplicationEvent(final ContextClosedEvent event) {
        synchronized (SpringApplicationContext.class) {
            // Spring also publishes a child context's close event to its parent's listeners; removing
            // exactly the closed context keeps every other registered context, whoever receives it.
            unregister(event.getApplicationContext());
        }
    }

    /**
     * Finds {@code context} in {@link #registeredContexts} by identity. Must be called while holding the class monitor.
     *
     * @param context the context to find.
     * @return its position, or {@code -1} if it is not registered.
     */
    private static int indexOf(final ApplicationContext context) {
        for (int i = 0, size = registeredContexts.size(); i < size; i++) {
            if (registeredContexts.get(i) == context) {
                return i;
            }
        }

        return -1;
    }

    /**
     * Removes {@code context} from {@link #registeredContexts}, if registered, and updates the context used
     * for lookups. Must be called while holding the class monitor.
     *
     * @param context the context to remove.
     */
    private static void unregister(final ApplicationContext context) {
        final int index = indexOf(context);

        if (index >= 0) {
            registeredContexts.remove(index);
            updateCurrentContext();
        }
    }

    /** Publishes the most recently registered context to lookups. Must be called while holding the class monitor. */
    private static void updateCurrentContext() {
        appContext = registeredContexts.isEmpty() ? null : registeredContexts.get(registeredContexts.size() - 1); // NOSONAR
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
     * <p>The lookup uses the most recently registered context that is still registered. Returns {@code null}
     * only when no context is registered: none has been injected yet, or every registered context has since
     * closed or had its bridge bean destroyed. If a context is registered but no bean with the given name
     * exists, {@code NoSuchBeanDefinitionException} is thrown.</p>
     *
     * @param name the name of the bean to retrieve.
     * @return the bean instance, or {@code null} if no ApplicationContext is registered (none injected yet, or all released after close/destruction).
     * @throws IllegalStateException if the supplied context has not been refreshed or has already been closed.
     * @throws IllegalArgumentException if the context is initialized and {@code name} is {@code null}.
     * @throws NoSuchBeanDefinitionException if no bean with the specified name is found.
     * @throws BeansException if the initialized context cannot create or retrieve the bean.
     * @see ApplicationContext#getBean(String)
     */
    public Object getBean(final String name) throws IllegalStateException, IllegalArgumentException, NoSuchBeanDefinitionException, BeansException {
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
     * @param <T> the bean type to be retrieved from the Spring context.
     * @param requiredType the class object representing the type of bean to retrieve.
     * @return the bean instance, or {@code null} if no ApplicationContext is registered (none injected yet, or all released after close/destruction).
     * @throws IllegalStateException if the supplied context has not been refreshed or has already been closed.
     * @throws IllegalArgumentException if the context is initialized and {@code requiredType} is {@code null}.
     * @throws NoSuchBeanDefinitionException if no bean of the given type exists.
     * @throws NoUniqueBeanDefinitionException if multiple candidates exist and none can be selected unambiguously.
     * @throws BeansException if the initialized context cannot create or retrieve the bean.
     * @see ApplicationContext#getBean(Class)
     */
    public <T> T getBean(final Class<T> requiredType)
            throws IllegalStateException, IllegalArgumentException, NoSuchBeanDefinitionException, NoUniqueBeanDefinitionException, BeansException {
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
     * @param <T> the bean type to be retrieved from the Spring context.
     * @param name the name of the bean to retrieve.
     * @param requiredType the class object representing the required type of the bean.
     * @return the bean instance, or {@code null} if no ApplicationContext is registered (none injected yet, or all released after close/destruction).
     * @throws IllegalStateException if the supplied context has not been refreshed or has already been closed.
     * @throws IllegalArgumentException if the context is initialized and {@code name} is {@code null}.
     * @throws NoSuchBeanDefinitionException if no bean with the specified name is found.
     * @throws BeanNotOfRequiredTypeException if the bean is not of the required type.
     * @throws BeansException if the initialized context cannot create or retrieve the bean.
     * @see ApplicationContext#getBean(String, Class)
     */
    public <T> T getBean(final String name, final Class<T> requiredType)
            throws IllegalStateException, IllegalArgumentException, NoSuchBeanDefinitionException, BeanNotOfRequiredTypeException, BeansException {
        final ApplicationContext context = appContext;

        return context == null ? null : context.getBean(name, requiredType);
    }
}
