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

/**
 * A utility class that provides access to Spring's ApplicationContext for bean retrieval.
 * This class is used internally by the JDBC framework to integrate with Spring's dependency injection container.
 * 
 * <p>The ApplicationContext is automatically injected by Spring when this class is registered as a Spring bean.
 * Once injected, it provides methods to retrieve beans by name or type from the Spring container.</p>
 * 
 * <p>Note: This class is intended for internal use only and requires Spring Framework to be present in the classpath.</p>
 * 
 * @author Haiyang Li
 */
final class SpringApplicationContext {

    @Autowired // NOSONAR
    private ApplicationContext appContext;

    SpringApplicationContext() {
    }

    /**
     * Retrieves a bean from the Spring ApplicationContext by its name.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Object dataSource = springAppContext.getBean("myDataSource");
     * }</pre>
     *
     * @param name the name of the bean to retrieve
     * @return the bean instance, or {@code null} if the ApplicationContext is not initialized or the bean is not found
     * 
     * @see ApplicationContext#getBean(String)
     */
    public Object getBean(final String name) {
        return appContext == null ? null : appContext.getBean(name);
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
        return appContext == null ? null : appContext.getBean(requiredType);
    }
}