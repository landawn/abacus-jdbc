/*
 * Copyright (C) 2015 HaiYang Li
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.landawn.abacus.dataSource;

import java.util.Collections;
import java.util.Map;

import org.w3c.dom.Element;

import com.landawn.abacus.util.Configuration;

/** 
 *
 * @author Haiyang Li
 * @since 1.3
 */
public final class DataSourceConfiguration extends Configuration {

    public static final String DATABASE = "database";

    public static final String DATA_SOURCE = "dataSource";

    public static final String NAME = "name";

    public static final String ENV = "env";

    public static final String SLICE_SELECTOR = "sliceSelector";

    public static final String PROVIDER = "provider";

    public static final String DBCP2 = "dbcp2";

    public static final String HIKARI_CP = "HikariCP";

    public static final String DEFAULT_ISOLATION = "defaultIsolation";

    public static final String SQL_LOG = "sqlLog";

    public static final String PERF_LOG = "perfLog";

    public static final String QUERY_WITH_READ_ONLY_CONNECTION_BY_DEFAULT = "queryWithReadOnlyConnectionByDefault";

    public static final String CONNECTION = "connection";

    public static final String READ_ONLY_CONNECTION = "readOnlyConnection";

    public static final String JNDI_NAME = "jndiName";

    public static final String JNDI_CONTEXT_FACTORY = "jndiContextFactory";

    public static final String JNDI_PROVIDER_URL = "jndiProviderUrl";

    public static final String DRIVER = "driver";

    public static final String URL = "url";

    public static final String USER = "user";

    public static final String PASSWORD = "password";

    public static final String INITIAL_SIZE = "initialSize";

    public static final int DEFAULT_INITIAL_SIZE = 0;

    public static final String MIN_IDLE = "minIdle";

    public static final int DEFAULT_MIN_IDLE = 8;

    public static final String MAX_IDLE = "maxIdle";

    public static final int DEFAULT_MAX_IDLE = 16;

    public static final String MAX_ACTIVE = "maxActive";

    public static final int DEFAULT_MAX_ACTIVE = 32;

    public static final String MAX_OPEN_PREPARED_STATEMENTS_PER_CONNECTION = "maxOpenPreparedStatementsPerConnection";

    public static final int DEFAULT_MAX_OPEN_PREPARED_STATEMENTS_PER_CONNECTION = 256;

    public static final String LIVE_TIME = "liveTime";

    public static final long DEFAULT_LIVE_TIME = 24 * 60 * 60 * 1000;

    public static final String MAX_IDLE_TIME = "maxIdleTime";

    public static final long DEFAULT_MAX_IDLE_TIME = 30 * 60 * 1000;

    public static final String MAX_WAIT_TIME = "maxWaitTime";

    public static final long DEFAULT_MAX_WAIT = 1000;

    public static final String EVICT_DELAY = "evictDelay";

    public static final int DEFAULT_EVICT_DELAY = 5000;

    public static final String VALIDATION_QUERY = "validationQuery";

    public static final String DEFAULT_VALIDATION_QUERY = "SELECT 1";

    public static final String TEST_ON_BORROW = "testOnBorrow";

    public static final boolean DEFAULT_TEST_ON_BORROW = true;

    public static final String TEST_ON_RETURN = "testOnReturn";

    public static final boolean DEFAULT_TEST_ON_RETURN = false;

    private Map<String, String> connectionProps;

    private Map<String, String> readOnlyConnectionProps;

    public DataSourceConfiguration(Element element, Map<String, String> properties) {
        super(element, properties);

        if (this.getAttribute(NAME) == null) {
            throw new RuntimeException("must set the 'name' attribute in 'dataSourceManager' element. for example: <dataSource name=\"codes\"> evn=\"dev\">");
        }

        if (this.getAttribute(ENV) == null) {
            throw new RuntimeException("must set the 'env' attribute in 'dataSourceManager' element. for example: <dataSource name=\"codes\"> evn=\"dev\">");
        }
    }

    /**
     * Gets the connection props.
     *
     * @return
     */
    public Map<String, String> getConnectionProps() {
        return connectionProps;
    }

    /**
     * Gets the read only connection props.
     *
     * @return
     */
    public Map<String, String> getReadOnlyConnectionProps() {
        return readOnlyConnectionProps;
    }

    /**
     * Complex element 2 attr.
     *
     * @param element
     */
    @Override
    protected void complexElement2Attr(Element element) {
        String eleName = element.getNodeName();

        if (DataSourceConfiguration.CONNECTION.equals(eleName)) {
            connectionProps = Collections.unmodifiableMap(new Configuration(element, this.props) {
            }.getAttributes());
        } else if (DataSourceConfiguration.READ_ONLY_CONNECTION.equals(eleName)) {
            readOnlyConnectionProps = Collections.unmodifiableMap(new Configuration(element, this.props) {
            }.getAttributes());
        } else {
            throw new RuntimeException("Unknown element: " + eleName);
        }
    }
}
