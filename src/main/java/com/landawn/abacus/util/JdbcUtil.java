/*
 * Copyright (c) 2015, Haiyang Li.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.landawn.abacus.util;

import static com.landawn.abacus.dataSource.DataSourceConfiguration.DRIVER;
import static com.landawn.abacus.dataSource.DataSourceConfiguration.PASSWORD;
import static com.landawn.abacus.dataSource.DataSourceConfiguration.URL;
import static com.landawn.abacus.dataSource.DataSourceConfiguration.USER;
import static com.landawn.abacus.util.IOUtil.DEFAULT_QUEUE_SIZE_FOR_ROW_PARSER;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Date;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.xml.parsers.DocumentBuilder;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import com.landawn.abacus.DataSet;
import com.landawn.abacus.DataSource;
import com.landawn.abacus.DataSourceManager;
import com.landawn.abacus.DirtyMarker;
import com.landawn.abacus.EntityId;
import com.landawn.abacus.IsolationLevel;
import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.annotation.Internal;
import com.landawn.abacus.condition.Condition;
import com.landawn.abacus.core.DirtyMarkerUtil;
import com.landawn.abacus.core.RowDataSet;
import com.landawn.abacus.core.Seid;
import com.landawn.abacus.dataSource.DataSourceConfiguration;
import com.landawn.abacus.dataSource.DataSourceManagerConfiguration;
import com.landawn.abacus.dataSource.SQLDataSource;
import com.landawn.abacus.dataSource.SQLDataSourceManager;
import com.landawn.abacus.exception.DuplicatedResultException;
import com.landawn.abacus.exception.ParseException;
import com.landawn.abacus.exception.UncheckedIOException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.logging.Logger;
import com.landawn.abacus.logging.LoggerFactory;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.EntityInfo;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.type.TypeFactory;
import com.landawn.abacus.util.ExceptionalStream.ExceptionalIterator;
import com.landawn.abacus.util.ExceptionalStream.StreamE;
import com.landawn.abacus.util.Fn.BiConsumers;
import com.landawn.abacus.util.Fn.Suppliers;
import com.landawn.abacus.util.SQLBuilder.SP;
import com.landawn.abacus.util.SQLExecutor.StatementSetter;
import com.landawn.abacus.util.SQLTransaction.CreatedBy;
import com.landawn.abacus.util.StringUtil.Strings;
import com.landawn.abacus.util.Tuple.Tuple2;
import com.landawn.abacus.util.Tuple.Tuple3;
import com.landawn.abacus.util.Tuple.Tuple4;
import com.landawn.abacus.util.Tuple.Tuple5;
import com.landawn.abacus.util.u.Nullable;
import com.landawn.abacus.util.u.Optional;
import com.landawn.abacus.util.u.OptionalBoolean;
import com.landawn.abacus.util.u.OptionalByte;
import com.landawn.abacus.util.u.OptionalChar;
import com.landawn.abacus.util.u.OptionalDouble;
import com.landawn.abacus.util.u.OptionalFloat;
import com.landawn.abacus.util.u.OptionalInt;
import com.landawn.abacus.util.u.OptionalLong;
import com.landawn.abacus.util.u.OptionalShort;
import com.landawn.abacus.util.function.BiConsumer;
import com.landawn.abacus.util.function.BiFunction;
import com.landawn.abacus.util.function.BinaryOperator;
import com.landawn.abacus.util.function.Function;
import com.landawn.abacus.util.function.Supplier;
import com.landawn.abacus.util.stream.Collector;
import com.landawn.abacus.util.stream.EntryStream;
import com.landawn.abacus.util.stream.Stream;

// TODO: Auto-generated Javadoc
/**
 * The Class JdbcUtil.
 *
 * @author Haiyang Li
 * @see {@link com.landawn.abacus.condition.ConditionFactory}
 * @see {@link com.landawn.abacus.condition.ConditionFactory.CF}
 * @see {@link com.landawn.abacus.annotation.ReadOnly}
 * @see {@link com.landawn.abacus.annotation.ReadOnlyId}
 * @see {@link com.landawn.abacus.annotation.NonUpdatable}
 * @see {@link com.landawn.abacus.annotation.Transient}
 * @see {@link com.landawn.abacus.annotation.Table}
 * @see {@link com.landawn.abacus.annotation.Column}
 * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/Connection.html">http://docs.oracle.com/javase/8/docs/api/java/sql/Connection.html</a>
 * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/Statement.html">http://docs.oracle.com/javase/8/docs/api/java/sql/Statement.html</a>
 * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/PreparedStatement.html">http://docs.oracle.com/javase/8/docs/api/java/sql/PreparedStatement.html</a>
 * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/ResultSet.html">http://docs.oracle.com/javase/8/docs/api/java/sql/ResultSet.html</a>
 * @since 0.8
 */
public final class JdbcUtil {

    /** The Constant logger. */
    static final Logger logger = LoggerFactory.getLogger(JdbcUtil.class);

    /** The Constant DEFAULT_BATCH_SIZE. */
    public static final int DEFAULT_BATCH_SIZE = 200;

    /** The Constant CURRENT_DIR_PATH. */
    // ...
    static final String CURRENT_DIR_PATH = "./";

    /** The async executor. */
    static final AsyncExecutor asyncExecutor = new AsyncExecutor(Math.max(8, IOUtil.CPU_CORES), Math.max(64, IOUtil.CPU_CORES), 180L, TimeUnit.SECONDS);

    /** The Constant DEFAULT_STMT_SETTER. */
    static final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> DEFAULT_STMT_SETTER = new JdbcUtil.BiParametersSetter<PreparedStatement, Object[]>() {
        @Override
        public void accept(PreparedStatement stmt, Object[] parameters) throws SQLException {
            for (int i = 0, len = parameters.length; i < len; i++) {
                stmt.setObject(i + 1, parameters[i]);
            }
        }
    };

    /** The Constant sqlStateForTableNotExists. */
    private static final Set<String> sqlStateForTableNotExists = N.newHashSet();

    static {
        sqlStateForTableNotExists.add("42S02"); // for MySQCF.
        sqlStateForTableNotExists.add("42P01"); // for PostgreSQCF.
        sqlStateForTableNotExists.add("42501"); // for HSQLDB.
    }

    /**
     * Instantiates a new jdbc util.
     */
    private JdbcUtil() {
        // singleton
    }

    /**
     * Gets the DB version.
     *
     * @param conn
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static DBVersion getDBVersion(final Connection conn) throws UncheckedSQLException {
        try {
            String dbProudctName = conn.getMetaData().getDatabaseProductName();
            String dbProudctVersion = conn.getMetaData().getDatabaseProductVersion();

            DBVersion dbVersion = DBVersion.OTHERS;

            String upperCaseProductName = dbProudctName.toUpperCase();
            if (upperCaseProductName.contains("H2")) {
                dbVersion = DBVersion.H2;
            } else if (upperCaseProductName.contains("HSQL")) {
                dbVersion = DBVersion.HSQLDB;
            } else if (upperCaseProductName.contains("MYSQL")) {
                if (dbProudctVersion.startsWith("5.5")) {
                    dbVersion = DBVersion.MYSQL_5_5;
                } else if (dbProudctVersion.startsWith("5.6")) {
                    dbVersion = DBVersion.MYSQL_5_6;
                } else if (dbProudctVersion.startsWith("5.7")) {
                    dbVersion = DBVersion.MYSQL_5_7;
                } else if (dbProudctVersion.startsWith("5.8")) {
                    dbVersion = DBVersion.MYSQL_5_8;
                } else if (dbProudctVersion.startsWith("5.9")) {
                    dbVersion = DBVersion.MYSQL_5_9;
                } else if (dbProudctVersion.startsWith("6")) {
                    dbVersion = DBVersion.MYSQL_6;
                } else if (dbProudctVersion.startsWith("7")) {
                    dbVersion = DBVersion.MYSQL_7;
                } else if (dbProudctVersion.startsWith("8")) {
                    dbVersion = DBVersion.MYSQL_8;
                } else if (dbProudctVersion.startsWith("9")) {
                    dbVersion = DBVersion.MYSQL_9;
                } else if (dbProudctVersion.startsWith("10")) {
                    dbVersion = DBVersion.MYSQL_10;
                } else {
                    dbVersion = DBVersion.MYSQL_OTHERS;
                }
            } else if (upperCaseProductName.contains("POSTGRESQL")) {
                if (dbProudctVersion.startsWith("9.2")) {
                    dbVersion = DBVersion.POSTGRESQL_9_2;
                } else if (dbProudctVersion.startsWith("9.3")) {
                    dbVersion = DBVersion.POSTGRESQL_9_3;
                } else if (dbProudctVersion.startsWith("9.4")) {
                    dbVersion = DBVersion.POSTGRESQL_9_4;
                } else if (dbProudctVersion.startsWith("9.5")) {
                    dbVersion = DBVersion.POSTGRESQL_9_5;
                } else if (dbProudctVersion.startsWith("10")) {
                    dbVersion = DBVersion.POSTGRESQL_10;
                } else if (dbProudctVersion.startsWith("11")) {
                    dbVersion = DBVersion.POSTGRESQL_11;
                } else if (dbProudctVersion.startsWith("12")) {
                    dbVersion = DBVersion.POSTGRESQL_12;
                } else {
                    dbVersion = DBVersion.POSTGRESQL_OTHERS;
                }
            } else if (upperCaseProductName.contains("ORACLE")) {
                dbVersion = DBVersion.ORACLE;
            } else if (upperCaseProductName.contains("DB2")) {
                dbVersion = DBVersion.DB2;
            } else if (upperCaseProductName.contains("SQL SERVER")) {
                dbVersion = DBVersion.SQL_SERVER;
            }

            return dbVersion;
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     * Creates the data source manager.
     *
     * @param dataSourceXmlFile
     * @return DataSourceManager
     * @throws UncheckedIOException the unchecked IO exception
     * @throws UncheckedSQLException the unchecked SQL exception
     * @see DataSource.xsd
     */
    public static DataSourceManager createDataSourceManager(final String dataSourceXmlFile) throws UncheckedIOException, UncheckedSQLException {
        InputStream is = null;
        try {
            is = new FileInputStream(Configuration.findFile(dataSourceXmlFile));
            return createDataSourceManager(is, dataSourceXmlFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            IOUtil.close(is);
        }

    }

    /**
     * Creates the data source manager.
     *
     * @param dataSourceXmlInputStream
     * @return DataSourceManager
     * @throws UncheckedIOException the unchecked IO exception
     * @throws UncheckedSQLException the unchecked SQL exception
     * @see DataSource.xsd
     */
    public static DataSourceManager createDataSourceManager(final InputStream dataSourceXmlInputStream) throws UncheckedIOException, UncheckedSQLException {
        return createDataSourceManager(dataSourceXmlInputStream, CURRENT_DIR_PATH);
    }

    /** The Constant PROPERTIES. */
    private static final String PROPERTIES = "properties";

    /** The Constant RESOURCE. */
    private static final String RESOURCE = "resource";

    /**
     * Creates the data source manager.
     *
     * @param dataSourceXmlInputStream
     * @param dataSourceXmlFile
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    private static DataSourceManager createDataSourceManager(final InputStream dataSourceXmlInputStream, final String dataSourceXmlFile)
            throws UncheckedIOException, UncheckedSQLException {
        DocumentBuilder domParser = XMLUtil.createDOMParser();
        Document doc = null;

        try {
            doc = domParser.parse(dataSourceXmlInputStream);

            Element rootElement = doc.getDocumentElement();

            final Map<String, String> props = new HashMap<>();
            List<Element> propertiesElementList = XMLUtil.getElementsByTagName(rootElement, PROPERTIES);

            if (N.notNullOrEmpty(propertiesElementList)) {
                for (Element propertiesElement : propertiesElementList) {
                    File resourcePropertiesFile = Configuration.findFileByFile(new File(dataSourceXmlFile), propertiesElement.getAttribute(RESOURCE));
                    java.util.Properties properties = new java.util.Properties();
                    InputStream is = null;

                    try {
                        is = new FileInputStream(resourcePropertiesFile);

                        if (resourcePropertiesFile.getName().endsWith(".xml")) {
                            properties.loadFromXML(is);
                        } else {
                            properties.load(is);
                        }
                    } finally {
                        IOUtil.close(is);
                    }

                    for (Object key : properties.keySet()) {
                        props.put((String) key, (String) properties.get(key));
                    }
                }
            }

            String nodeName = rootElement.getNodeName();
            if (nodeName.equals(DataSourceManagerConfiguration.DATA_SOURCE_MANAGER)) {
                DataSourceManagerConfiguration config = new DataSourceManagerConfiguration(rootElement, props);
                return new SQLDataSourceManager(config);
            } else if (nodeName.equals(DataSourceConfiguration.DATA_SOURCE)) {
                DataSourceConfiguration config = new DataSourceConfiguration(rootElement, props);
                return new SimpleDataSourceManager(new SQLDataSource(config));
            } else {
                throw new RuntimeException("Unknown xml format with root element: " + nodeName);
            }
        } catch (SAXException e) {
            throw new ParseException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Creates the data source.
     *
     * @param dataSourceFile
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     * @throws UncheckedSQLException the unchecked SQL exception
     * @see DataSource.xsd
     */
    public static javax.sql.DataSource createDataSource(final String dataSourceFile) throws UncheckedIOException, UncheckedSQLException {
        InputStream is = null;
        try {
            is = new FileInputStream(Configuration.findFile(dataSourceFile));
            return createDataSource(is, dataSourceFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            IOUtil.close(is);
        }
    }

    /**
     * Creates the data source.
     *
     * @param dataSourceInputStream
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     * @throws UncheckedSQLException the unchecked SQL exception
     * @see DataSource.xsd
     */
    public static javax.sql.DataSource createDataSource(final InputStream dataSourceInputStream) throws UncheckedIOException, UncheckedSQLException {
        return createDataSource(dataSourceInputStream, CURRENT_DIR_PATH);
    }

    /**
     * Creates the data source.
     *
     * @param dataSourceInputStream
     * @param dataSourceFile
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    private static javax.sql.DataSource createDataSource(final InputStream dataSourceInputStream, final String dataSourceFile)
            throws UncheckedIOException, UncheckedSQLException {
        final String dataSourceString = IOUtil.readString(dataSourceInputStream);

        if (CURRENT_DIR_PATH.equals(dataSourceFile) || dataSourceFile.endsWith(".xml")) {
            try {
                return createDataSourceManager(new ByteArrayInputStream(dataSourceString.getBytes())).getPrimaryDataSource();
            } catch (ParseException e) {
                // ignore.
            } catch (UncheckedIOException e) {
                // ignore.
            }
        }

        final Map<String, String> newProps = new HashMap<>();
        final java.util.Properties properties = new java.util.Properties();

        try {
            properties.load(new ByteArrayInputStream(dataSourceString.getBytes()));

            Object value = null;

            for (Object key : properties.keySet()) {
                value = properties.get(key);
                newProps.put(key.toString().trim(), value.toString().trim());
            }

        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return new SQLDataSource(newProps);
    }

    /**
     * Creates the data source.
     *
     * @param url
     * @param user
     * @param password
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static javax.sql.DataSource createDataSource(final String url, final String user, final String password) throws UncheckedSQLException {
        return createDataSource(getDriverClasssByUrl(url), url, user, password);
    }

    /**
     * Creates the data source.
     *
     * @param driver
     * @param url
     * @param user
     * @param password
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static javax.sql.DataSource createDataSource(final String driver, final String url, final String user, final String password)
            throws UncheckedSQLException {
        final Class<? extends Driver> driverClass = ClassUtil.forClass(driver);

        return createDataSource(driverClass, url, user, password);
    }

    /**
     * Creates the data source.
     *
     * @param driverClass
     * @param url
     * @param user
     * @param password
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static javax.sql.DataSource createDataSource(final Class<? extends Driver> driverClass, final String url, final String user, final String password)
            throws UncheckedSQLException {
        N.checkArgNotNullOrEmpty(url, "url");

        final Map<String, Object> props = new HashMap<>();

        props.put(DRIVER, driverClass.getCanonicalName());
        props.put(URL, url);
        props.put(USER, user);
        props.put(PASSWORD, password);

        return createDataSource(props);
    }

    /**
     * Creates the data source.
     *
     * @param props refer to Connection.xsd for the supported properties.
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static javax.sql.DataSource createDataSource(final Map<String, ?> props) throws UncheckedSQLException {
        final String driver = (String) props.get(DRIVER);

        if (N.isNullOrEmpty(driver)) {
            final String url = (String) props.get(URL);

            if (N.isNullOrEmpty(url)) {
                throw new IllegalArgumentException("Url is not specified");
            }

            final Map<String, Object> tmp = new HashMap<>(props);

            tmp.put(DRIVER, getDriverClasssByUrl(url).getCanonicalName());

            return new SQLDataSource(tmp);
        } else {
            return new SQLDataSource(props);
        }
    }

    //    /**
    //     *
    //     * @param sqlDataSource
    //     * @return
    //     * @deprecated
    //     */
    //    @Deprecated
    //    public static DataSource wrap(final javax.sql.DataSource sqlDataSource) {
    //        return sqlDataSource instanceof DataSource ? ((DataSource) sqlDataSource) : new SimpleDataSource(sqlDataSource);
    //    }

    /**
     * Creates the connection.
     *
     * @param url
     * @param user
     * @param password
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static Connection createConnection(final String url, final String user, final String password) throws UncheckedSQLException {
        return createConnection(getDriverClasssByUrl(url), url, user, password);
    }

    /**
     * Gets the driver classs by url.
     *
     * @param url
     * @return
     */
    private static Class<? extends Driver> getDriverClasssByUrl(final String url) {
        N.checkArgNotNullOrEmpty(url, "url");

        Class<? extends Driver> driverClass = null;
        // jdbc:mysql://localhost:3306/abacustest
        if (url.indexOf("mysql") > 0 || StringUtil.indexOfIgnoreCase(url, "mysql") > 0) {
            driverClass = ClassUtil.forClass("com.mysql.jdbc.Driver");
            // jdbc:postgresql://localhost:5432/abacustest
        } else if (url.indexOf("postgresql") > 0 || StringUtil.indexOfIgnoreCase(url, "postgresql") > 0) {
            driverClass = ClassUtil.forClass("org.postgresql.Driver");
            // jdbc:h2:hsql://<host>:<port>/<database>
        } else if (url.indexOf("h2") > 0 || StringUtil.indexOfIgnoreCase(url, "h2") > 0) {
            driverClass = ClassUtil.forClass("org.h2.Driver");
            // jdbc:hsqldb:hsql://localhost/abacustest
        } else if (url.indexOf("hsqldb") > 0 || StringUtil.indexOfIgnoreCase(url, "hsqldb") > 0) {
            driverClass = ClassUtil.forClass("org.hsqldb.jdbc.JDBCDriver");
            // jdbc.url=jdbc:oracle:thin:@localhost:1521:abacustest
        } else if (url.indexOf("oracle") > 0 || StringUtil.indexOfIgnoreCase(url, "oracle") > 0) {
            driverClass = ClassUtil.forClass("oracle.jdbc.driver.OracleDriver");
            // jdbc.url=jdbc:sqlserver://localhost:1433;Database=abacustest
        } else if (url.indexOf("sqlserver") > 0 || StringUtil.indexOfIgnoreCase(url, "sqlserver") > 0) {
            driverClass = ClassUtil.forClass("com.microsoft.sqlserver.jdbc.SQLServerDriver");
            // jdbc:db2://localhost:50000/abacustest
        } else if (url.indexOf("db2") > 0 || StringUtil.indexOfIgnoreCase(url, "db2") > 0) {
            driverClass = ClassUtil.forClass("com.ibm.db2.jcc.DB2Driver");
        } else {
            throw new IllegalArgumentException(
                    "Can not identity the driver class by url: " + url + ". Only mysql, postgresql, hsqldb, sqlserver, oracle and db2 are supported currently");
        }
        return driverClass;
    }

    /**
     * Creates the connection.
     *
     * @param driverClass
     * @param url
     * @param user
     * @param password
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static Connection createConnection(final String driverClass, final String url, final String user, final String password)
            throws UncheckedSQLException {
        Class<? extends Driver> cls = ClassUtil.forClass(driverClass);
        return createConnection(cls, url, user, password);
    }

    /**
     * Creates the connection.
     *
     * @param driverClass
     * @param url
     * @param user
     * @param password
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static Connection createConnection(final Class<? extends Driver> driverClass, final String url, final String user, final String password)
            throws UncheckedSQLException {
        try {
            DriverManager.registerDriver(N.newInstance(driverClass));

            return DriverManager.getConnection(url, user, password);
        } catch (SQLException e) {
            throw new UncheckedSQLException("Failed to close create connection", e);
        }
    }

    /** The is in spring. */
    private static boolean isInSpring = true;

    static {
        try {
            isInSpring = ClassUtil.forClass("org.springframework.jdbc.datasource.DataSourceUtils") != null;
        } catch (Throwable e) {
            isInSpring = false;
        }
    }

    /**
     * Spring Transaction is supported and Integrated.
     * If this method is called where a Spring transaction is started with the specified {@code DataSource},
     * the {@code Connection} started the Spring Transaction will be returned. Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be returned.
     *
     * @param ds
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static Connection getConnection(final javax.sql.DataSource ds) throws UncheckedSQLException {
        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) {
            try {
                return org.springframework.jdbc.datasource.DataSourceUtils.getConnection(ds);
            } catch (NoClassDefFoundError e) {
                isInSpring = false;

                try {
                    return ds.getConnection();
                } catch (SQLException e1) {
                    throw new UncheckedSQLException(e1);
                }
            }
        } else {
            try {
                return ds.getConnection();
            } catch (SQLException e) {
                throw new UncheckedSQLException(e);
            }
        }
    }

    /**
     * Spring Transaction is supported and Integrated.
     * If this method is called where a Spring transaction is started with the specified {@code DataSource},
     * the specified {@code Connection} won't be returned to {@code DataSource}(Connection pool) until the transaction is committed or rolled back. Otherwise the specified {@code Connection} will be directly returned back to {@code DataSource}(Connection pool).
     *
     * @param conn
     * @param ds
     */
    public static void releaseConnection(final Connection conn, final javax.sql.DataSource ds) {
        if (conn == null) {
            return;
        }

        if (isInSpring && ds != null && !isSpringTransactionalDisabled_TL.get()) {
            try {
                org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(conn, ds);
            } catch (NoClassDefFoundError e) {
                isInSpring = false;
                JdbcUtil.closeQuietly(conn);
            }
        } else {
            JdbcUtil.closeQuietly(conn);
        }
    }

    /**
     * Creates the close handler.
     *
     * @param conn
     * @param ds
     * @return
     */
    static Runnable createCloseHandler(final Connection conn, final javax.sql.DataSource ds) {
        return () -> releaseConnection(conn, ds);
    }

    /**
     *
     * @param rs
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static void close(final ResultSet rs) throws UncheckedSQLException {
        if (rs != null) {
            try {
                rs.close();
            } catch (SQLException e) {
                throw new UncheckedSQLException(e);
            }
        }
    }

    /**
     *
     * @param rs
     * @param closeStatement
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static void close(final ResultSet rs, final boolean closeStatement) throws UncheckedSQLException {
        close(rs, closeStatement, false);
    }

    /**
     *
     * @param rs
     * @param closeStatement
     * @param closeConnection
     * @throws IllegalArgumentException if {@code closeStatement = false} while {@code closeConnection = true}.
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static void close(final ResultSet rs, final boolean closeStatement, final boolean closeConnection)
            throws IllegalArgumentException, UncheckedSQLException {
        if (closeConnection && closeStatement == false) {
            throw new IllegalArgumentException("'closeStatement' can't be false while 'closeConnection' is true");
        }

        if (rs == null) {
            return;
        }

        Connection conn = null;
        Statement stmt = null;

        try {
            if (closeStatement || closeConnection) {
                stmt = rs.getStatement();
            }

            if (closeConnection && stmt != null) {
                conn = stmt.getConnection();
            }
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            close(rs, stmt, conn);
        }
    }

    /**
     *
     * @param stmt
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static void close(final Statement stmt) throws UncheckedSQLException {
        if (stmt != null) {
            try {
                stmt.close();
            } catch (SQLException e) {
                throw new UncheckedSQLException(e);
            }
        }
    }

    /**
     *
     * @param conn
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static void close(final Connection conn) throws UncheckedSQLException {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException e) {
                throw new UncheckedSQLException(e);
            }
        }
    }

    /**
     *
     * @param rs
     * @param stmt
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static void close(final ResultSet rs, final Statement stmt) throws UncheckedSQLException {
        try {
            if (rs != null) {
                rs.close();
            }
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            try {
                if (stmt != null) {
                    stmt.close();
                }
            } catch (SQLException e) {
                throw new UncheckedSQLException(e);
            }
        }
    }

    /**
     *
     * @param stmt
     * @param conn
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static void close(final Statement stmt, final Connection conn) throws UncheckedSQLException {
        try {
            if (stmt != null) {
                stmt.close();
            }
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            try {
                if (conn != null) {
                    conn.close();
                }
            } catch (SQLException e) {
                throw new UncheckedSQLException(e);
            }
        }
    }

    /**
     *
     * @param rs
     * @param stmt
     * @param conn
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static void close(final ResultSet rs, final Statement stmt, final Connection conn) throws UncheckedSQLException {
        try {
            if (rs != null) {
                rs.close();
            }
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            try {
                if (stmt != null) {
                    stmt.close();
                }
            } catch (SQLException e) {
                throw new UncheckedSQLException(e);
            } finally {
                try {
                    if (conn != null) {
                        conn.close();
                    }
                } catch (SQLException e) {
                    throw new UncheckedSQLException(e);
                }
            }
        }
    }

    /**
     * Unconditionally close an <code>ResultSet</code>.
     * <p>
     * Equivalent to {@link ResultSet#close()}, except any exceptions will be ignored.
     * This is typically used in finally blocks.
     *
     * @param rs
     */
    public static void closeQuietly(final ResultSet rs) {
        closeQuietly(rs, null, null);
    }

    /**
     *
     * @param rs
     * @param closeStatement
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static void closeQuietly(final ResultSet rs, final boolean closeStatement) throws UncheckedSQLException {
        closeQuietly(rs, closeStatement, false);
    }

    /**
     *
     * @param rs
     * @param closeStatement
     * @param closeConnection
     * @throws IllegalArgumentException if {@code closeStatement = false} while {@code closeConnection = true}.
     */
    public static void closeQuietly(final ResultSet rs, final boolean closeStatement, final boolean closeConnection) throws IllegalArgumentException {
        if (closeConnection && closeStatement == false) {
            throw new IllegalArgumentException("'closeStatement' can't be false while 'closeConnection' is true");
        }

        if (rs == null) {
            return;
        }

        Connection conn = null;
        Statement stmt = null;

        try {
            if (closeStatement || closeConnection) {
                stmt = rs.getStatement();
            }

            if (closeConnection && stmt != null) {
                conn = stmt.getConnection();
            }
        } catch (SQLException e) {
            logger.error("Failed to get Statement or Connection by ResultSet", e);
        } finally {
            closeQuietly(rs, stmt, conn);
        }
    }

    /**
     * Unconditionally close an <code>Statement</code>.
     * <p>
     * Equivalent to {@link Statement#close()}, except any exceptions will be ignored.
     * This is typically used in finally blocks.
     *
     * @param stmt
     */
    public static void closeQuietly(final Statement stmt) {
        closeQuietly(null, stmt, null);
    }

    /**
     * Unconditionally close an <code>Connection</code>.
     * <p>
     * Equivalent to {@link Connection#close()}, except any exceptions will be ignored.
     * This is typically used in finally blocks.
     *
     * @param conn
     */
    public static void closeQuietly(final Connection conn) {
        closeQuietly(null, null, conn);
    }

    /**
     * Unconditionally close the <code>ResultSet, Statement</code>.
     * <p>
     * Equivalent to {@link ResultSet#close()}, {@link Statement#close()}, except any exceptions will be ignored.
     * This is typically used in finally blocks.
     *
     * @param rs
     * @param stmt
     */
    public static void closeQuietly(final ResultSet rs, final Statement stmt) {
        closeQuietly(rs, stmt, null);
    }

    /**
     * Unconditionally close the <code>Statement, Connection</code>.
     * <p>
     * Equivalent to {@link Statement#close()}, {@link Connection#close()}, except any exceptions will be ignored.
     * This is typically used in finally blocks.
     *
     * @param stmt
     * @param conn
     */
    public static void closeQuietly(final Statement stmt, final Connection conn) {
        closeQuietly(null, stmt, conn);
    }

    /**
     * Unconditionally close the <code>ResultSet, Statement, Connection</code>.
     * <p>
     * Equivalent to {@link ResultSet#close()}, {@link Statement#close()}, {@link Connection#close()}, except any exceptions will be ignored.
     * This is typically used in finally blocks.
     *
     * @param rs
     * @param stmt
     * @param conn
     */
    public static void closeQuietly(final ResultSet rs, final Statement stmt, final Connection conn) {
        if (rs != null) {
            try {
                rs.close();
            } catch (Exception e) {
                logger.error("Failed to close ResultSet", e);
            }
        }

        if (stmt != null) {
            try {
                stmt.close();
            } catch (Exception e) {
                logger.error("Failed to close Statement", e);
            }
        }

        if (conn != null) {
            try {
                conn.close();
            } catch (Exception e) {
                logger.error("Failed to close Connection", e);
            }
        }
    }

    /**
     *
     * @param rs
     * @param n the count of row to move ahead.
     * @return
     * @throws SQLException the SQL exception
     */
    public static int skip(final ResultSet rs, int n) throws SQLException {
        return skip(rs, (long) n);
    }

    /**
     *
     * @param rs
     * @param n the count of row to move ahead.
     * @return
     * @throws SQLException the SQL exception
     * @see {@link ResultSet#absolute(int)}
     */
    public static int skip(final ResultSet rs, long n) throws SQLException {
        return InternalJdbcUtil.skip(rs, n);
    }

    /**
     * Gets the column count.
     *
     * @param rs
     * @return
     * @throws SQLException the SQL exception
     */
    public static int getColumnCount(ResultSet rs) throws SQLException {
        return rs.getMetaData().getColumnCount();
    }

    /**
     * Gets the column name list.
     *
     * @param conn
     * @param tableName
     * @return
     * @throws SQLException the SQL exception
     */
    public static List<String> getColumnNameList(final Connection conn, final String tableName) throws SQLException {
        final String query = "SELECT * FROM " + tableName + " WHERE 1 > 2";
        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            stmt = prepareStatement(conn, query);
            rs = executeQuery(stmt);

            final ResultSetMetaData metaData = rs.getMetaData();
            final int columnCount = metaData.getColumnCount();
            final List<String> columnNameList = new ArrayList<>(columnCount);

            for (int i = 1, n = columnCount + 1; i < n; i++) {
                columnNameList.add(metaData.getColumnName(i));
            }

            return columnNameList;
        } finally {
            closeQuietly(rs, stmt);
        }
    }

    /**
     * Gets the column label list.
     *
     * @param rs
     * @return
     * @throws SQLException the SQL exception
     */
    public static List<String> getColumnLabelList(ResultSet rs) throws SQLException {
        return InternalJdbcUtil.getColumnLabelList(rs);
    }

    /**
     * Gets the column label.
     *
     * @param rsmd
     * @param columnIndex
     * @return
     * @throws SQLException the SQL exception
     */
    public static String getColumnLabel(final ResultSetMetaData rsmd, final int columnIndex) throws SQLException {
        return InternalJdbcUtil.getColumnLabel(rsmd, columnIndex);
    }

    /**
     * Gets the column value.
     *
     * @param rs
     * @param columnIndex
     * @return
     * @throws SQLException the SQL exception
     */
    public static Object getColumnValue(final ResultSet rs, final int columnIndex) throws SQLException {
        return InternalJdbcUtil.getColumnValue(rs, columnIndex);
    }

    /**
     * Gets the column value.
     *
     * @param rs
     * @param columnLabel
     * @return
     * @throws SQLException the SQL exception
     */
    public static Object getColumnValue(final ResultSet rs, final String columnLabel) throws SQLException {
        return InternalJdbcUtil.getColumnValue(rs, columnLabel);
    }

    /**
     * Gets the column value.
     *
     * @param <T>
     * @param targetClass
     * @param rs
     * @param columnIndex
     * @return
     * @throws SQLException the SQL exception
     */
    public static <T> T getColumnValue(final Class<T> targetClass, final ResultSet rs, final int columnIndex) throws SQLException {
        return N.<T> typeOf(targetClass).get(rs, columnIndex);
    }

    /**
     * Gets the column value.
     *
     * @param <T>
     * @param targetClass
     * @param rs
     * @param columnLabel
     * @return
     * @throws SQLException the SQL exception
     */
    public static <T> T getColumnValue(final Class<T> targetClass, final ResultSet rs, final String columnLabel) throws SQLException {
        return N.<T> typeOf(targetClass).get(rs, columnLabel);
    }

    /**
     * Refer to: {@code beginTransaction(javax.sql.DataSource, IsolationLevel, boolean)}.
     *
     * @param dataSource
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @see {@link #beginTransaction(javax.sql.DataSource, IsolationLevel, boolean)}
     */
    public static SQLTransaction beginTransaction(final javax.sql.DataSource dataSource) throws UncheckedSQLException {
        return beginTransaction(dataSource, IsolationLevel.DEFAULT);
    }

    /**
     * Refer to: {@code beginTransaction(javax.sql.DataSource, IsolationLevel, boolean)}.
     *
     * @param dataSource
     * @param isolationLevel
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @see {@link #beginTransaction(javax.sql.DataSource, IsolationLevel, boolean)}
     */
    public static SQLTransaction beginTransaction(final javax.sql.DataSource dataSource, final IsolationLevel isolationLevel) throws UncheckedSQLException {
        return beginTransaction(dataSource, isolationLevel, false);
    }

    /**
     * Starts a global transaction which will be shared by all in-line database query with the same {@code DataSource} in the same thread,
     * including methods: {@code JdbcUtil.beginTransaction/prepareQuery/prepareNamedQuery/prepareCallableQuery, SQLExecutor(Mapper).beginTransaction/get/insert/batchInsert/update/batchUpdate/query/list/findFirst/...}
     *
     * <br />
     * Spring Transaction is supported and Integrated.
     * If this method is called at where a Spring transaction is started with the specified {@code DataSource},
     * the {@code Connection} started the Spring Transaction will be used here.
     * That's to say the Spring transaction will have the final control on commit/roll back over the {@code Connection}.
     *
     * <br />
     * <br />
     *
     * Here is the general code pattern to work with {@code SQLTransaction}.
     *
     * <pre>
     * <code>
     * public void doSomethingA() {
     *     ...
     *     final SQLTransaction tranA = JdbcUtil.beginTransaction(dataSource1, isolation);
     *
     *     try {
     *         ...
     *         doSomethingB(); // Share the same transaction 'tranA' because they're in the same thread and start transaction with same DataSource 'dataSource1'.
     *         ...
     *         doSomethingC(); // won't share the same transaction 'tranA' although they're in the same thread but start transaction with different DataSource 'dataSource2'.
     *         ...
     *         tranA.commit();
     *     } finally {
     *         tranA.rollbackIfNotCommitted();
     *     }
     * }
     *
     * public void doSomethingB() {
     *     ...
     *     final SQLTransaction tranB = JdbcUtil.beginTransaction(dataSource1, isolation);
     *     try {
     *         // do your work with the conn...
     *         ...
     *         tranB.commit();
     *     } finally {
     *         tranB.rollbackIfNotCommitted();
     *     }
     * }
     *
     * public void doSomethingC() {
     *     ...
     *     final SQLTransaction tranC = JdbcUtil.beginTransaction(dataSource2, isolation);
     *     try {
     *         // do your work with the conn...
     *         ...
     *         tranC.commit();
     *     } finally {
     *         tranC.rollbackIfNotCommitted();
     *     }
     * }
     * </pre>
     * </code>
     *
     * It's incorrect to use flag to identity the transaction should be committed or rolled back.
     * Don't write below code:
     * <pre>
     * <code>
     * public void doSomethingA() {
     *     ...
     *     final SQLTransaction tranA = JdbcUtil.beginTransaction(dataSource1, isolation);
     *     boolean flagToCommit = false;
     *     try {
     *         // do your work with the conn...
     *         ...
     *         flagToCommit = true;
     *     } finally {
     *         if (flagToCommit) {
     *             tranA.commit();
     *         } else {
     *             tranA.rollbackIfNotCommitted();
     *         }
     *     }
     * }
     * </code>
     * </pre>
     *
     * @param dataSource
     * @param isolationLevel
     * @param isForUpdateOnly
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @see {@link #getConnection(javax.sql.DataSource)}
     * @see {@link #releaseConnection(Connection, javax.sql.DataSource)}
     * @see SQLExecutor#beginTransaction(IsolationLevel, boolean, JdbcSettings)
     */
    public static SQLTransaction beginTransaction(final javax.sql.DataSource dataSource, final IsolationLevel isolationLevel, final boolean isForUpdateOnly)
            throws UncheckedSQLException {
        N.checkArgNotNull(dataSource, "dataSource");
        N.checkArgNotNull(isolationLevel, "isolationLevel");

        SQLTransaction tran = SQLTransaction.getTransaction(dataSource, CreatedBy.JDBC_UTIL);

        if (tran == null) {
            Connection conn = null;
            boolean noException = false;

            try {
                conn = getConnection(dataSource);
                tran = new SQLTransaction(dataSource, conn, isolationLevel, CreatedBy.JDBC_UTIL, true);
                tran.incrementAndGetRef(isolationLevel, isForUpdateOnly);

                noException = true;
            } catch (SQLException e) {
                throw new UncheckedSQLException(e);
            } finally {
                if (noException == false) {
                    releaseConnection(conn, dataSource);
                }
            }

            logger.info("Create a new SQLTransaction(id={})", tran.id());
            SQLTransaction.putTransaction(tran);
        } else {
            logger.info("Reusing the existing SQLTransaction(id={})", tran.id());
            tran.incrementAndGetRef(isolationLevel, isForUpdateOnly);
        }

        return tran;
    }

    /**
     *
     * @param <T>
     * @param <E>
     * @param ds
     * @param cmd
     * @return
     * @throws E
     */
    @Beta
    public static <T, E extends Throwable> T callInTransaction(final javax.sql.DataSource ds, final Throwables.Callable<T, E> cmd) throws E {
        final SQLTransaction tran = JdbcUtil.beginTransaction(ds);
        T result = null;

        try {
            result = cmd.call();
            tran.commit();
        } finally {
            tran.rollbackIfNotCommitted();
        }

        return result;
    }

    /**
     *
     * @param <T>
     * @param <E>
     * @param ds
     * @param cmd
     * @return
     * @throws E
     */
    @Beta
    public static <T, E extends Throwable> T callInTransaction(final javax.sql.DataSource ds, final Throwables.Function<javax.sql.DataSource, T, E> cmd)
            throws E {
        final SQLTransaction tran = JdbcUtil.beginTransaction(ds);
        T result = null;

        try {
            result = cmd.apply(ds);
            tran.commit();
        } finally {
            tran.rollbackIfNotCommitted();
        }

        return result;
    }

    /**
     *
     * @param <E>
     * @param ds
     * @param cmd
     * @return
     * @throws E
     */
    @Beta
    public static <E extends Throwable> void runInTransaction(final javax.sql.DataSource ds, final Throwables.Runnable<E> cmd) throws E {
        final SQLTransaction tran = JdbcUtil.beginTransaction(ds);

        try {
            cmd.run();
            tran.commit();
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    /**
     *
     * @param <E>
     * @param ds
     * @param cmd
     * @return
     * @throws E
     */
    @Beta
    public static <E extends Throwable> void runInTransaction(final javax.sql.DataSource ds, final Throwables.Consumer<javax.sql.DataSource, E> cmd) throws E {
        final SQLTransaction tran = JdbcUtil.beginTransaction(ds);

        try {
            cmd.accept(ds);
            tran.commit();
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    /**
     *
     * @param <T>
     * @param <E>
     * @param ds
     * @param cmd
     * @return
     * @throws E
     */
    @Beta
    public static <T, E extends Throwable> T callNotInStartedTransaction(final javax.sql.DataSource ds, final Throwables.Callable<T, E> cmd) throws E {
        final SQLTransaction tran = SQLTransaction.getTransaction(ds, CreatedBy.JDBC_UTIL);

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) {
            JdbcUtil.disableSpringTransactional(true);

            try {
                if (tran == null) {
                    return cmd.call();
                } else {
                    return tran.callNotInMe(cmd);
                }
            } finally {
                JdbcUtil.disableSpringTransactional(false);
            }
        } else {
            if (tran == null) {
                return cmd.call();
            } else {
                return tran.callNotInMe(cmd);
            }
        }
    }

    /**
     *
     * @param <T>
     * @param <E>
     * @param ds
     * @param cmd
     * @return
     * @throws E
     */
    @Beta
    public static <T, E extends Throwable> T callNotInStartedTransaction(final javax.sql.DataSource ds,
            final Throwables.Function<javax.sql.DataSource, T, E> cmd) throws E {
        final SQLTransaction tran = SQLTransaction.getTransaction(ds, CreatedBy.JDBC_UTIL);

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) {
            JdbcUtil.disableSpringTransactional(true);

            try {
                if (tran == null) {
                    return cmd.apply(ds);
                } else {
                    return tran.callNotInMe(() -> cmd.apply(ds));
                }
            } finally {
                JdbcUtil.disableSpringTransactional(false);
            }
        } else {
            if (tran == null) {
                return cmd.apply(ds);
            } else {
                return tran.callNotInMe(() -> cmd.apply(ds));
            }
        }
    }

    /**
     *
     * @param <E>
     * @param ds
     * @param cmd
     * @return
     * @throws E
     */
    @Beta
    public static <E extends Throwable> void runNotInStartedTransaction(final javax.sql.DataSource ds, final Throwables.Runnable<E> cmd) throws E {
        final SQLTransaction tran = SQLTransaction.getTransaction(ds, CreatedBy.JDBC_UTIL);

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) {
            JdbcUtil.disableSpringTransactional(true);

            try {
                if (tran == null) {
                    cmd.run();
                } else {
                    tran.runNotInMe(cmd);
                }
            } finally {
                JdbcUtil.disableSpringTransactional(false);
            }
        } else {
            if (tran == null) {
                cmd.run();
            } else {
                tran.runNotInMe(cmd);
            }
        }
    }

    /**
     *
     * @param <E>
     * @param ds
     * @param cmd
     * @return
     * @throws E
     */
    @Beta
    public static <E extends Throwable> void runNotInStartedTransaction(final javax.sql.DataSource ds, final Throwables.Consumer<javax.sql.DataSource, E> cmd)
            throws E {
        final SQLTransaction tran = SQLTransaction.getTransaction(ds, CreatedBy.JDBC_UTIL);

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) {
            JdbcUtil.disableSpringTransactional(true);

            try {
                if (tran == null) {
                    cmd.accept(ds);
                } else {
                    tran.runNotInMe(() -> cmd.accept(ds));
                }
            } finally {
                JdbcUtil.disableSpringTransactional(false);
            }
        } else {
            if (tran == null) {
                cmd.accept(ds);
            } else {
                tran.runNotInMe(() -> cmd.accept(ds));
            }
        }
    }

    /**
     * Gets the SQL operation.
     *
     * @param sql
     * @return
     */
    static SQLOperation getSQLOperation(String sql) {
        if (StringUtil.startsWithIgnoreCase(sql.trim(), "select ")) {
            return SQLOperation.SELECT;
        } else if (StringUtil.startsWithIgnoreCase(sql.trim(), "update ")) {
            return SQLOperation.UPDATE;
        } else if (StringUtil.startsWithIgnoreCase(sql.trim(), "insert ")) {
            return SQLOperation.INSERT;
        } else if (StringUtil.startsWithIgnoreCase(sql.trim(), "delete ")) {
            return SQLOperation.DELETE;
        } else {
            for (SQLOperation so : SQLOperation.values()) {
                if (StringUtil.startsWithIgnoreCase(sql.trim(), so.name())) {
                    return so;
                }
            }
        }

        return SQLOperation.UPDATE; // TODO change it to SQLOperation.UNKNOWN in 1.10.1 release
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param sql
     * @return
     * @throws SQLException the SQL exception
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql) throws SQLException {
        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareQuery(tran.connection(), sql);
        } else {
            PreparedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareQuery(conn, sql).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param sql
     * @param autoGeneratedKeys
     * @return
     * @throws SQLException the SQL exception
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql, final boolean autoGeneratedKeys) throws SQLException {
        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareQuery(tran.connection(), sql, autoGeneratedKeys);
        } else {
            PreparedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareQuery(conn, sql, autoGeneratedKeys).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     *
     * @param ds
     * @param sql
     * @param returnColumnIndexes
     * @return
     * @throws SQLException the SQL exception
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql, final int[] returnColumnIndexes) throws SQLException {
        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareQuery(tran.connection(), sql, returnColumnIndexes);
        } else {
            PreparedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareQuery(conn, sql, returnColumnIndexes).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     *
     * @param ds
     * @param sql
     * @param returnColumnNames
     * @return
     * @throws SQLException the SQL exception
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql, final String[] returnColumnNames) throws SQLException {
        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareQuery(tran.connection(), sql, returnColumnNames);
        } else {
            PreparedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareQuery(conn, sql, returnColumnNames).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     *
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param sql
     * @param stmtCreator the created {@code PreparedStatement} will be closed after any execution methods in {@code PreparedQuery/PreparedCallableQuery} is called.
     * An execution method is a method which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/list/execute/....
     * @return
     * @throws SQLException the SQL exception
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareQuery(tran.connection(), sql, stmtCreator);
        } else {
            PreparedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareQuery(conn, sql, stmtCreator).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareQuery(dataSource.getConnection(), sql);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param sql
     * @return
     * @throws SQLException the SQL exception
     */
    public static PreparedQuery prepareQuery(final Connection conn, final String sql) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");

        return new PreparedQuery(prepareStatement(conn, sql));
    }

    /**
     *
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareQuery(dataSource.getConnection(), sql, autoGeneratedKeys);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param sql
     * @param autoGeneratedKeys
     * @return
     * @throws SQLException the SQL exception
     */
    public static PreparedQuery prepareQuery(final Connection conn, final String sql, final boolean autoGeneratedKeys) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");

        return new PreparedQuery(prepareStatement(conn, sql, autoGeneratedKeys));
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareQuery(dataSource.getConnection(), sql, returnColumnIndexes);
     * </code>
     * </pre>
     *
     * @param conn
     * @param sql
     * @param returnColumnIndexes
     * @return
     * @throws SQLException the SQL exception
     */
    public static PreparedQuery prepareQuery(final Connection conn, final String sql, final int[] returnColumnIndexes) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");
        N.checkArgNotNullOrEmpty(returnColumnIndexes, "returnColumnIndexes");

        return new PreparedQuery(prepareStatement(conn, sql, returnColumnIndexes));
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareQuery(dataSource.getConnection(), sql, returnColumnNames);
     * </code>
     * </pre>
     *
     * @param conn
     * @param sql
     * @param returnColumnNames
     * @return
     * @throws SQLException the SQL exception
     */
    public static PreparedQuery prepareQuery(final Connection conn, final String sql, final String[] returnColumnNames) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");
        N.checkArgNotNullOrEmpty(returnColumnNames, "returnColumnNames");

        return new PreparedQuery(prepareStatement(conn, sql, returnColumnNames));
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareQuery(dataSource.getConnection(), sql, stmtCreator);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param sql
     * @param stmtCreator the created {@code PreparedStatement} will be closed after any execution methods in {@code PreparedQuery/PreparedCallableQuery} is called.
     * An execution method is a method which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/list/execute/....
     * @return
     * @throws SQLException the SQL exception
     */
    public static PreparedQuery prepareQuery(final Connection conn, final String sql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");
        N.checkArgNotNull(stmtCreator, "stmtCreator");

        return new PreparedQuery(prepareStatement(conn, sql, stmtCreator));
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @return
     * @throws SQLException the SQL exception
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql) throws SQLException {
        final SQLTransaction tran = getTransaction(ds, namedSql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareNamedQuery(conn, namedSql).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param autoGeneratedKeys
     * @return
     * @throws SQLException the SQL exception
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql, final boolean autoGeneratedKeys) throws SQLException {
        final SQLTransaction tran = getTransaction(ds, namedSql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql, autoGeneratedKeys);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareNamedQuery(conn, namedSql, autoGeneratedKeys).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param returnColumnIndexes
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql, final int[] returnColumnIndexes) throws SQLException {
        final SQLTransaction tran = getTransaction(ds, namedSql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql, returnColumnIndexes);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareNamedQuery(conn, namedSql, returnColumnIndexes).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param returnColumnNames
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql, final String[] returnColumnNames) throws SQLException {
        final SQLTransaction tran = getTransaction(ds, namedSql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql, returnColumnNames);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareNamedQuery(conn, namedSql, returnColumnNames).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     *
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param stmtCreator the created {@code PreparedStatement} will be closed after any execution methods in {@code NamedQuery/PreparedCallableQuery} is called.
     * An execution method is a method which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/list/execute/....
     * @return
     * @throws SQLException the SQL exception
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        final SQLTransaction tran = getTransaction(ds, namedSql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql, stmtCreator);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareNamedQuery(conn, namedSql, stmtCreator).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSql);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final String namedSql) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(namedSql, "namedSql");

        final NamedSQL namedSQL = createNamedSQL(namedSql);

        return new NamedQuery(prepareStatement(conn, namedSQL), namedSQL);
    }

    /**
     *
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSql, autoGeneratedKeys);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param autoGeneratedKeys
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final String namedSql, final boolean autoGeneratedKeys) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(namedSql, "namedSql");

        final NamedSQL namedSQL = createNamedSQL(namedSql);

        return new NamedQuery(prepareStatement(conn, namedSQL, autoGeneratedKeys), namedSQL);
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSql);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param returnColumnIndexes
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final String namedSql, final int[] returnColumnIndexes) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(namedSql, "namedSql");
        N.checkArgNotNullOrEmpty(returnColumnIndexes, "returnColumnIndexes");

        final NamedSQL namedSQL = createNamedSQL(namedSql);

        return new NamedQuery(prepareStatement(conn, namedSQL, returnColumnIndexes), namedSQL);
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSql);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param returnColumnNames
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final String namedSql, final String[] returnColumnNames) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(namedSql, "namedSql");
        N.checkArgNotNullOrEmpty(returnColumnNames, "returnColumnNames");

        final NamedSQL namedSQL = createNamedSQL(namedSql);

        return new NamedQuery(prepareStatement(conn, namedSQL, returnColumnNames), namedSQL);
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSql, stmtCreator);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param stmtCreator the created {@code PreparedStatement} will be closed after any execution methods in {@code NamedQuery/PreparedCallableQuery} is called.
     * An execution method is a method which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/list/execute/....
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final String namedSql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(namedSql, "namedSql");
        N.checkArgNotNull(stmtCreator, "stmtCreator");

        final NamedSQL namedSQL = createNamedSQL(namedSql);

        return new NamedQuery(prepareStatement(conn, namedSQL, stmtCreator), namedSQL);
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param namedSQL for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @return
     * @throws SQLException the SQL exception
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final NamedSQL namedSQL) throws SQLException {
        validateNamedSQL(namedSQL);

        final SQLTransaction tran = getTransaction(ds, namedSQL.getParameterizedSQL(), CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSQL);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareNamedQuery(conn, namedSQL).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param namedSQL for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param autoGeneratedKeys
     * @return
     * @throws SQLException the SQL exception
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final NamedSQL namedSQL, final boolean autoGeneratedKeys) throws SQLException {
        validateNamedSQL(namedSQL);

        final SQLTransaction tran = getTransaction(ds, namedSQL.getParameterizedSQL(), CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSQL, autoGeneratedKeys);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareNamedQuery(conn, namedSQL, autoGeneratedKeys).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param namedSQL for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param returnColumnIndexes
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final NamedSQL namedSQL, final int[] returnColumnIndexes) throws SQLException {
        validateNamedSQL(namedSQL);

        final SQLTransaction tran = getTransaction(ds, namedSQL.getParameterizedSQL(), CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSQL, returnColumnIndexes);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareNamedQuery(conn, namedSQL, returnColumnIndexes).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param namedSQL for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param returnColumnNames
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final NamedSQL namedSQL, final String[] returnColumnNames) throws SQLException {
        validateNamedSQL(namedSQL);

        final SQLTransaction tran = getTransaction(ds, namedSQL.getParameterizedSQL(), CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSQL, returnColumnNames);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareNamedQuery(conn, namedSQL, returnColumnNames).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     *
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param namedSQL for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param stmtCreator the created {@code PreparedStatement} will be closed after any execution methods in {@code NamedQuery/PreparedCallableQuery} is called.
     * An execution method is a method which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/list/execute/....
     * @return
     * @throws SQLException the SQL exception
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final NamedSQL namedSQL,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        validateNamedSQL(namedSQL);

        final SQLTransaction tran = getTransaction(ds, namedSQL.getParameterizedSQL(), CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSQL, stmtCreator);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareNamedQuery(conn, namedSQL, stmtCreator).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSQL);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param namedSQL for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final NamedSQL namedSQL) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(namedSQL, "namedSQL");
        validateNamedSQL(namedSQL);

        return new NamedQuery(prepareStatement(conn, namedSQL), namedSQL);
    }

    /**
     *
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSQL, autoGeneratedKeys);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param namedSQL for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param autoGeneratedKeys
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final NamedSQL namedSQL, final boolean autoGeneratedKeys) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(namedSQL, "namedSQL");
        validateNamedSQL(namedSQL);

        return new NamedQuery(prepareStatement(conn, namedSQL, autoGeneratedKeys), namedSQL);
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSQL);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param namedSQL for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param returnColumnIndexes
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final NamedSQL namedSQL, final int[] returnColumnIndexes) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(namedSQL, "namedSQL");
        N.checkArgNotNullOrEmpty(returnColumnIndexes, "returnColumnIndexes");
        validateNamedSQL(namedSQL);

        return new NamedQuery(prepareStatement(conn, namedSQL, returnColumnIndexes), namedSQL);
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSQL);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param namedSQL for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param returnColumnNames
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final NamedSQL namedSQL, final String[] returnColumnNames) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(namedSQL, "namedSQL");
        N.checkArgNotNullOrEmpty(returnColumnNames, "returnColumnNames");
        validateNamedSQL(namedSQL);

        return new NamedQuery(prepareStatement(conn, namedSQL, returnColumnNames), namedSQL);
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSQL, stmtCreator);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param namedSQL for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param stmtCreator the created {@code PreparedStatement} will be closed after any execution methods in {@code NamedQuery/PreparedCallableQuery} is called.
     * An execution method is a method which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/list/execute/....
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final NamedSQL namedSQL,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(namedSQL, "namedSQL");
        N.checkArgNotNull(stmtCreator, "stmtCreator");
        validateNamedSQL(namedSQL);

        return new NamedQuery(prepareStatement(conn, namedSQL, stmtCreator), namedSQL);
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param sql
     * @return
     * @throws SQLException the SQL exception
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static PreparedCallableQuery prepareCallableQuery(final javax.sql.DataSource ds, final String sql) throws SQLException {
        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareCallableQuery(tran.connection(), sql);
        } else {
            PreparedCallableQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareCallableQuery(conn, sql).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param sql
     * @param stmtCreator the created {@code CallableStatement} will be closed after any execution methods in {@code PreparedQuery/PreparedCallableQuery} is called.
     * An execution method is a method which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/list/execute/....
     * @return
     * @throws SQLException the SQL exception
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static PreparedCallableQuery prepareCallableQuery(final javax.sql.DataSource ds, final String sql,
            final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator) throws SQLException {
        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareCallableQuery(tran.connection(), sql, stmtCreator);
        } else {
            PreparedCallableQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareCallableQuery(conn, sql, stmtCreator).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareCallableQuery(dataSource.getConnection(), sql);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param sql
     * @return
     * @throws SQLException the SQL exception
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static PreparedCallableQuery prepareCallableQuery(final Connection conn, final String sql) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");

        return new PreparedCallableQuery(prepareCallable(conn, sql));
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareCallableQuery(dataSource.getConnection(), sql, stmtCreator);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param sql
     * @param stmtCreator the created {@code CallableStatement} will be closed after any execution methods in {@code PreparedQuery/PreparedCallableQuery} is called.
     * An execution method is a method which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/list/execute/....
     * @return
     * @throws SQLException the SQL exception
     */
    public static PreparedCallableQuery prepareCallableQuery(final Connection conn, final String sql,
            final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");
        N.checkArgNotNull(stmtCreator, "stmtCreator");

        return new PreparedCallableQuery(prepareCallable(conn, sql, stmtCreator));
    }

    static PreparedStatement prepareStatement(final Connection conn, final String sql) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + sql);
        }

        return conn.prepareStatement(sql);
    }

    static PreparedStatement prepareStatement(final Connection conn, final String sql, final boolean autoGeneratedKeys) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + sql);
        }

        return conn.prepareStatement(sql, autoGeneratedKeys ? Statement.RETURN_GENERATED_KEYS : Statement.NO_GENERATED_KEYS);
    }

    static PreparedStatement prepareStatement(final Connection conn, final String sql, final int[] returnColumnIndexes) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + sql);
        }

        return conn.prepareStatement(sql, returnColumnIndexes);
    }

    static PreparedStatement prepareStatement(final Connection conn, final String sql, final String[] returnColumnNames) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + sql);
        }

        return conn.prepareStatement(sql, returnColumnNames);
    }

    static PreparedStatement prepareStatement(final Connection conn, final String sql, final int resultSetType, final int resultSetConcurrency)
            throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + sql);
        }

        return conn.prepareStatement(sql, resultSetType, resultSetConcurrency);
    }

    static PreparedStatement prepareStatement(final Connection conn, final String sql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + sql);
        }

        return stmtCreator.apply(conn, sql);
    }

    static PreparedStatement prepareStatement(final Connection conn, final NamedSQL namedSQL) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + namedSQL.getNamedSQL());
        }

        return conn.prepareStatement(namedSQL.getParameterizedSQL());
    }

    static PreparedStatement prepareStatement(final Connection conn, final NamedSQL namedSQL, final boolean autoGeneratedKeys) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + namedSQL.getNamedSQL());
        }

        return conn.prepareStatement(namedSQL.getParameterizedSQL(), autoGeneratedKeys ? Statement.RETURN_GENERATED_KEYS : Statement.NO_GENERATED_KEYS);
    }

    static PreparedStatement prepareStatement(final Connection conn, final NamedSQL namedSQL, final int[] returnColumnIndexes) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + namedSQL.getNamedSQL());
        }

        return conn.prepareStatement(namedSQL.getParameterizedSQL(), returnColumnIndexes);
    }

    static PreparedStatement prepareStatement(final Connection conn, final NamedSQL namedSQL, final String[] returnColumnNames) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + namedSQL.getNamedSQL());
        }

        return conn.prepareStatement(namedSQL.getParameterizedSQL(), returnColumnNames);
    }

    static PreparedStatement prepareStatement(final Connection conn, final NamedSQL namedSQL, final int resultSetType, final int resultSetConcurrency)
            throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + namedSQL.getNamedSQL());
        }

        return conn.prepareStatement(namedSQL.getParameterizedSQL(), resultSetType, resultSetConcurrency);
    }

    static PreparedStatement prepareStatement(final Connection conn, final NamedSQL namedSQL,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + namedSQL.getNamedSQL());
        }

        return stmtCreator.apply(conn, namedSQL.getParameterizedSQL());
    }

    static CallableStatement prepareCallable(final Connection conn, final String sql) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + sql);
        }

        return conn.prepareCall(sql);
    }

    static CallableStatement prepareCallable(final Connection conn, final String sql,
            final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + sql);
        }

        return stmtCreator.apply(conn, sql);
    }

    static CallableStatement prepareCallable(final Connection conn, final NamedSQL namedSQL) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + namedSQL.getNamedSQL());
        }

        return conn.prepareCall(namedSQL.getParameterizedSQL());
    }

    static CallableStatement prepareCallable(final Connection conn, final NamedSQL namedSQL,
            final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + namedSQL.getNamedSQL());
        }

        return stmtCreator.apply(conn, namedSQL.getParameterizedSQL());
    }

    /**
     *
     * @param conn
     * @param sql
     * @param parameters
     * @return
     * @throws SQLException the SQL exception
     */
    @SafeVarargs
    static PreparedStatement prepareStmt(final Connection conn, final String sql, final Object... parameters) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");

        final NamedSQL namedSQL = NamedSQL.parse(sql);
        final PreparedStatement stmt = prepareStatement(conn, namedSQL);

        if (N.notNullOrEmpty(parameters)) {
            StatementSetter.DEFAULT.setParameters(namedSQL, stmt, parameters);
        }

        return stmt;
    }

    /**
     *
     * @param conn
     * @param sql
     * @param parameters
     * @return
     * @throws SQLException the SQL exception
     */
    @SafeVarargs
    static CallableStatement prepareCall(final Connection conn, final String sql, final Object... parameters) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");

        final NamedSQL namedSQL = NamedSQL.parse(sql);
        final CallableStatement stmt = prepareCallable(conn, namedSQL);

        if (N.notNullOrEmpty(parameters)) {
            StatementSetter.DEFAULT.setParameters(namedSQL, stmt, parameters);
        }

        return stmt;
    }

    /**
     * Batch prepare statement.
     *
     * @param conn
     * @param sql
     * @param parametersList
     * @return
     * @throws SQLException the SQL exception
     */
    static PreparedStatement prepareBatchStmt(final Connection conn, final String sql, final List<?> parametersList) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");

        final NamedSQL namedSQL = NamedSQL.parse(sql);
        final PreparedStatement stmt = prepareStatement(conn, namedSQL);

        for (Object parameters : parametersList) {
            StatementSetter.DEFAULT.setParameters(namedSQL, stmt, N.asArray(parameters));
            stmt.addBatch();
        }

        return stmt;
    }

    /**
     *
     * @param conn
     * @param sql
     * @param parametersList
     * @return
     * @throws SQLException the SQL exception
     */
    static CallableStatement prepareBatchCall(final Connection conn, final String sql, final List<?> parametersList) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");

        final NamedSQL namedSQL = NamedSQL.parse(sql);
        final CallableStatement stmt = prepareCallable(conn, namedSQL);

        for (Object parameters : parametersList) {
            StatementSetter.DEFAULT.setParameters(namedSQL, stmt, N.asArray(parameters));
            stmt.addBatch();
        }

        return stmt;
    }

    /**
     * Creates the named SQL.
     *
     * @param namedSql
     * @return
     */
    private static NamedSQL createNamedSQL(final String namedSql) {
        N.checkArgNotNullOrEmpty(namedSql, "namedSql");

        final NamedSQL namedSQL = NamedSQL.parse(namedSql);

        validateNamedSQL(namedSQL);

        return namedSQL;
    }

    private static void validateNamedSQL(final NamedSQL namedSQL) {
        if (namedSQL.getNamedParameters().size() != namedSQL.getParameterCount()) {
            throw new IllegalArgumentException("\"" + namedSQL.getNamedSQL() + "\" is not a valid named sql:");
        }
    }

    private static SQLTransaction getTransaction(final javax.sql.DataSource ds, final String sql, final CreatedBy createdBy) {
        final SQLOperation sqlOperation = JdbcUtil.getSQLOperation(sql);
        final SQLTransaction tran = SQLTransaction.getTransaction(ds, createdBy);

        if (tran == null || (tran.isForUpdateOnly() && sqlOperation == SQLOperation.SELECT)) {
            return null;
        } else {
            return tran;
        }
    }

    /**
     *
     * @param ds
     * @param sql
     * @param parameters
     * @return
     * @throws SQLException the SQL exception
     */
    @SafeVarargs
    public static DataSet executeQuery(final javax.sql.DataSource ds, final String sql, final Object... parameters) throws SQLException {
        N.checkArgNotNull(ds, "ds");
        N.checkArgNotNull(sql, "sql");

        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return executeQuery(tran.connection(), sql, parameters);
        } else {
            final Connection conn = getConnection(ds);

            try {
                return executeQuery(conn, sql, parameters);
            } finally {
                JdbcUtil.closeQuietly(conn);
            }
        }
    }

    /**
     *
     * @param conn
     * @param sql
     * @param parameters
     * @return
     * @throws SQLException the SQL exception
     */
    @SafeVarargs
    public static DataSet executeQuery(final Connection conn, final String sql, final Object... parameters) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");

        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            stmt = prepareStmt(conn, sql, parameters);

            stmt.setFetchDirection(ResultSet.FETCH_FORWARD);

            rs = executeQuery(stmt);

            return extractData(rs);
        } finally {
            closeQuietly(rs, stmt);
        }
    }

    //    /**
    //     *
    //     * @param stmt
    //     * @return
    //     * @throws SQLException the SQL exception
    //     */
    //    public static DataSet executeQuery(final PreparedStatement stmt) throws SQLException {
    //        ResultSet rs = null;
    //
    //        try {
    //            rs = executeQuerry(stmt);
    //
    //            return extractData(rs);
    //        } finally {
    //            closeQuietly(rs);
    //        }
    //    }

    /**
     *
     * @param ds
     * @param sql
     * @param parameters
     * @return
     * @throws SQLException the SQL exception
     */
    @SafeVarargs
    public static int executeUpdate(final javax.sql.DataSource ds, final String sql, final Object... parameters) throws SQLException {
        N.checkArgNotNull(ds, "ds");
        N.checkArgNotNull(sql, "sql");

        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return executeUpdate(tran.connection(), sql, parameters);
        } else {
            final Connection conn = getConnection(ds);

            try {
                return executeUpdate(conn, sql, parameters);
            } finally {
                JdbcUtil.closeQuietly(conn);
            }
        }
    }

    /**
     *
     * @param conn
     * @param sql
     * @param parameters
     * @return
     * @throws SQLException the SQL exception
     */
    @SafeVarargs
    public static int executeUpdate(final Connection conn, final String sql, final Object... parameters) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");

        PreparedStatement stmt = null;

        try {
            stmt = prepareStmt(conn, sql, parameters);

            return executeUpdate(stmt);
        } finally {
            closeQuietly(stmt);
        }
    }

    /**
     *
     * @param ds
     * @param sql
     * @param listOfParameters
     * @return
     * @throws SQLException the SQL exception
     */
    public static int executeBatchUpdate(final javax.sql.DataSource ds, final String sql, final List<?> listOfParameters) throws SQLException {
        return executeBatchUpdate(ds, sql, listOfParameters, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     *
     * @param ds
     * @param sql
     * @param listOfParameters
     * @param batchSize
     * @return
     * @throws SQLException the SQL exception
     */
    public static int executeBatchUpdate(final javax.sql.DataSource ds, final String sql, final List<?> listOfParameters, final int batchSize)
            throws SQLException {
        N.checkArgNotNull(ds, "ds");
        N.checkArgNotNull(sql, "sql");
        N.checkArgPositive(batchSize, "batchSize");

        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return executeBatchUpdate(tran.connection(), sql, listOfParameters, batchSize);
        } else if (listOfParameters.size() <= batchSize) {
            final Connection conn = getConnection(ds);

            try {
                return executeBatchUpdate(conn, sql, listOfParameters, batchSize);
            } finally {
                JdbcUtil.closeQuietly(conn);
            }
        } else {
            final SQLTransaction tran2 = JdbcUtil.beginTransaction(ds);
            int ret = 0;

            try {
                ret = executeBatchUpdate(tran2.connection(), sql, listOfParameters, batchSize);
                tran2.commit();
            } finally {
                tran2.rollbackIfNotCommitted();
            }

            return ret;
        }
    }

    /**
     * Execute batch update.
     *
     * @param conn
     * @param sql
     * @param listOfParameters
     * @return
     * @throws SQLException the SQL exception
     */
    public static int executeBatchUpdate(final Connection conn, final String sql, final List<?> listOfParameters) throws SQLException {
        return executeBatchUpdate(conn, sql, listOfParameters, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Execute batch update.
     *
     * @param conn
     * @param sql
     * @param listOfParameters
     * @param batchSize
     * @return
     * @throws SQLException the SQL exception
     */
    public static int executeBatchUpdate(final Connection conn, final String sql, final List<?> listOfParameters, final int batchSize) throws SQLException {
        N.checkArgNotNull(conn);
        N.checkArgNotNull(sql);
        N.checkArgPositive(batchSize, "batchSize");

        if (N.isNullOrEmpty(listOfParameters)) {
            return 0;
        }

        final NamedSQL namedSQL = NamedSQL.parse(sql);
        final boolean originalAutoCommit = conn.getAutoCommit();
        PreparedStatement stmt = null;
        boolean noException = false;

        try {
            if (originalAutoCommit && listOfParameters.size() > batchSize) {
                conn.setAutoCommit(false);
            }

            stmt = prepareStatement(conn, namedSQL);

            int res = 0;
            int idx = 0;

            for (Object parameters : listOfParameters) {
                StatementSetter.DEFAULT.setParameters(namedSQL, stmt, parameters);
                stmt.addBatch();

                if (++idx % batchSize == 0) {
                    res += N.sum(executeBatch(stmt));
                }
            }

            if (idx % batchSize != 0) {
                res += N.sum(executeBatch(stmt));
            }

            noException = true;

            return res;
        } finally {
            if (originalAutoCommit && listOfParameters.size() > batchSize) {
                try {
                    if (noException) {
                        conn.commit();
                    } else {
                        conn.rollback();
                    }
                } finally {
                    try {
                        conn.setAutoCommit(true);
                    } finally {
                        JdbcUtil.closeQuietly(stmt);
                    }
                }
            } else {
                JdbcUtil.closeQuietly(stmt);
            }
        }
    }

    /**
     *
     * @param ds
     * @param sql
     * @param listOfParameters
     * @return
     * @throws SQLException the SQL exception
     */
    public static long executeLargeBatchUpdate(final javax.sql.DataSource ds, final String sql, final List<?> listOfParameters) throws SQLException {
        return executeLargeBatchUpdate(ds, sql, listOfParameters, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     *
     * @param ds
     * @param sql
     * @param listOfParameters
     * @param batchSize
     * @return
     * @throws SQLException the SQL exception
     */
    public static long executeLargeBatchUpdate(final javax.sql.DataSource ds, final String sql, final List<?> listOfParameters, final int batchSize)
            throws SQLException {
        N.checkArgNotNull(ds, "ds");
        N.checkArgNotNull(sql, "sql");
        N.checkArgPositive(batchSize, "batchSize");

        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return executeLargeBatchUpdate(tran.connection(), sql, listOfParameters, batchSize);
        } else if (listOfParameters.size() <= batchSize) {
            final Connection conn = getConnection(ds);

            try {
                return executeLargeBatchUpdate(conn, sql, listOfParameters, batchSize);
            } finally {
                JdbcUtil.closeQuietly(conn);
            }
        } else {
            final SQLTransaction tran2 = JdbcUtil.beginTransaction(ds);
            long ret = 0;

            try {
                ret = executeLargeBatchUpdate(tran2.connection(), sql, listOfParameters, batchSize);
                tran2.commit();
            } finally {
                tran2.rollbackIfNotCommitted();
            }

            return ret;
        }
    }

    /**
     * Execute batch update.
     *
     * @param conn
     * @param sql
     * @param listOfParameters
     * @return
     * @throws SQLException the SQL exception
     */
    public static long executeLargeBatchUpdate(final Connection conn, final String sql, final List<?> listOfParameters) throws SQLException {
        return executeLargeBatchUpdate(conn, sql, listOfParameters, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Execute batch update.
     *
     * @param conn
     * @param sql
     * @param listOfParameters
     * @param batchSize
     * @return
     * @throws SQLException the SQL exception
     */
    public static long executeLargeBatchUpdate(final Connection conn, final String sql, final List<?> listOfParameters, final int batchSize)
            throws SQLException {
        N.checkArgNotNull(conn);
        N.checkArgNotNull(sql);
        N.checkArgPositive(batchSize, "batchSize");

        if (N.isNullOrEmpty(listOfParameters)) {
            return 0;
        }

        final NamedSQL namedSQL = NamedSQL.parse(sql);
        final boolean originalAutoCommit = conn.getAutoCommit();
        PreparedStatement stmt = null;
        boolean noException = false;

        try {
            if (originalAutoCommit && listOfParameters.size() > batchSize) {
                conn.setAutoCommit(false);
            }

            stmt = prepareStatement(conn, namedSQL);

            long res = 0;
            int idx = 0;

            for (Object parameters : listOfParameters) {
                StatementSetter.DEFAULT.setParameters(namedSQL, stmt, parameters);
                stmt.addBatch();

                if (++idx % batchSize == 0) {
                    res += N.sum(executeLargeBatch(stmt));
                }
            }

            if (idx % batchSize != 0) {
                res += N.sum(executeLargeBatch(stmt));
            }

            noException = true;

            return res;
        } finally {
            if (originalAutoCommit && listOfParameters.size() > batchSize) {
                try {
                    if (noException) {
                        conn.commit();
                    } else {
                        conn.rollback();
                    }
                } finally {
                    try {
                        conn.setAutoCommit(true);
                    } finally {
                        JdbcUtil.closeQuietly(stmt);
                    }
                }
            } else {
                JdbcUtil.closeQuietly(stmt);
            }
        }
    }

    /**
     *
     * @param ds
     * @param sql
     * @param parameters
     * @return
     * @throws SQLException the SQL exception
     */
    @SafeVarargs
    public static boolean execute(final javax.sql.DataSource ds, final String sql, final Object... parameters) throws SQLException {
        N.checkArgNotNull(ds, "ds");
        N.checkArgNotNull(sql, "sql");

        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return execute(tran.connection(), sql, parameters);
        } else {
            final Connection conn = getConnection(ds);

            try {
                return execute(conn, sql, parameters);
            } finally {
                JdbcUtil.closeQuietly(conn);
            }
        }
    }

    /**
     *
     * @param conn
     * @param sql
     * @param parameters
     * @return true, if successful
     * @throws SQLException the SQL exception
     */
    @SafeVarargs
    public static boolean execute(final Connection conn, final String sql, final Object... parameters) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");

        PreparedStatement stmt = null;

        try {
            stmt = prepareStmt(conn, sql, parameters);

            return JdbcUtil.execute(stmt);
        } finally {
            closeQuietly(stmt);
        }
    }

    static ResultSet executeQuery(PreparedStatement stmt) throws SQLException {
        if (logger.isInfoEnabled() && minExecutionTimeForSQLPerfLog_TL.get() >= 0) {
            final long startTime = System.currentTimeMillis();

            try {
                return stmt.executeQuery();
            } finally {
                final long elapsedTime = System.currentTimeMillis() - startTime;

                if (elapsedTime >= minExecutionTimeForSQLPerfLog_TL.get()) {
                    logger.info("[SQL-PERF]: " + elapsedTime + ", " + stmt.toString());
                }

                try {
                    stmt.clearParameters();
                } catch (SQLException e) {
                    logger.error("Failed to clear parameters after executeQuery", e);
                }
            }
        } else {
            try {
                return stmt.executeQuery();
            } finally {
                try {
                    stmt.clearParameters();
                } catch (SQLException e) {
                    logger.error("Failed to clear parameters after executeQuery", e);
                }
            }
        }
    }

    static int executeUpdate(PreparedStatement stmt) throws SQLException {
        if (logger.isInfoEnabled() && minExecutionTimeForSQLPerfLog_TL.get() >= 0) {
            final long startTime = System.currentTimeMillis();

            try {
                return stmt.executeUpdate();
            } finally {
                final long elapsedTime = System.currentTimeMillis() - startTime;

                if (elapsedTime >= minExecutionTimeForSQLPerfLog_TL.get()) {
                    logger.info("[SQL-PERF]: " + elapsedTime + ", " + stmt.toString());
                }

                try {
                    stmt.clearParameters();
                } catch (SQLException e) {
                    logger.error("Failed to clear parameters after executeUpdate", e);
                }
            }
        } else {
            try {
                return stmt.executeUpdate();
            } finally {
                try {
                    stmt.clearParameters();
                } catch (SQLException e) {
                    logger.error("Failed to clear parameters after executeUpdate", e);
                }
            }
        }
    }

    static int[] executeBatch(Statement stmt) throws SQLException {
        if (logger.isInfoEnabled() && minExecutionTimeForSQLPerfLog_TL.get() >= 0) {
            final long startTime = System.currentTimeMillis();

            try {
                return stmt.executeBatch();
            } finally {
                final long elapsedTime = System.currentTimeMillis() - startTime;

                if (elapsedTime >= minExecutionTimeForSQLPerfLog_TL.get()) {
                    logger.info("[SQL-PERF]: " + elapsedTime + ", " + stmt.toString());
                }

                try {
                    stmt.clearBatch();
                } catch (SQLException e) {
                    logger.error("Failed to clear batch parameters after executeBatch", e);
                }
            }
        } else {
            try {
                return stmt.executeBatch();
            } finally {
                try {
                    stmt.clearBatch();
                } catch (SQLException e) {
                    logger.error("Failed to clear batch parameters after executeBatch", e);
                }
            }
        }
    }

    static long[] executeLargeBatch(Statement stmt) throws SQLException {
        if (logger.isInfoEnabled() && minExecutionTimeForSQLPerfLog_TL.get() >= 0) {
            final long startTime = System.currentTimeMillis();

            try {
                return stmt.executeLargeBatch();
            } finally {
                final long elapsedTime = System.currentTimeMillis() - startTime;

                if (elapsedTime >= minExecutionTimeForSQLPerfLog_TL.get()) {
                    logger.info("[SQL-PERF]: " + elapsedTime + ", " + stmt.toString());
                }

                try {
                    stmt.clearBatch();
                } catch (SQLException e) {
                    logger.error("Failed to clear batch parameters after executeLargeBatch", e);
                }
            }
        } else {
            try {
                return stmt.executeLargeBatch();
            } finally {
                try {
                    stmt.clearBatch();
                } catch (SQLException e) {
                    logger.error("Failed to clear batch parameters after executeLargeBatch", e);
                }
            }
        }
    }

    static boolean execute(PreparedStatement stmt) throws SQLException {
        if (logger.isInfoEnabled() && minExecutionTimeForSQLPerfLog_TL.get() >= 0) {
            final long startTime = System.currentTimeMillis();

            try {
                return stmt.execute();
            } finally {
                final long elapsedTime = System.currentTimeMillis() - startTime;

                if (elapsedTime >= minExecutionTimeForSQLPerfLog_TL.get()) {
                    logger.info("[SQL-PERF]: " + elapsedTime + ", " + stmt.toString());
                }

                try {
                    stmt.clearParameters();
                } catch (SQLException e) {
                    logger.error("Failed to clear parameters after execute", e);
                }
            }
        } else {
            try {
                return stmt.execute();
            } finally {
                try {
                    stmt.clearParameters();
                } catch (SQLException e) {
                    logger.error("Failed to clear parameters after execute", e);
                }
            }
        }
    }

    /**
     *
     * @param rs
     * @return
     * @throws SQLException the SQL exception
     */
    public static DataSet extractData(final ResultSet rs) throws SQLException {
        return extractData(rs, false);
    }

    /**
     *
     * @param rs
     * @param closeResultSet
     * @return
     * @throws SQLException the SQL exception
     */
    public static DataSet extractData(final ResultSet rs, final boolean closeResultSet) throws SQLException {
        return extractData(rs, 0, Integer.MAX_VALUE, closeResultSet);
    }

    /**
     *
     * @param rs
     * @param offset
     * @param count
     * @return
     * @throws SQLException the SQL exception
     */
    public static DataSet extractData(final ResultSet rs, final int offset, final int count) throws SQLException {
        return extractData(rs, offset, count, false);
    }

    /**
     *
     * @param rs
     * @param offset
     * @param count
     * @param closeResultSet
     * @return
     * @throws SQLException the SQL exception
     */
    public static DataSet extractData(final ResultSet rs, final int offset, final int count, final boolean closeResultSet) throws SQLException {
        return extractData(rs, offset, count, RowFilter.ALWAYS_TRUE, closeResultSet);
    }

    /**
     *
     * @param rs
     * @param offset
     * @param count
     * @param filter
     * @param closeResultSet
     * @return
     * @throws SQLException the SQL exception
     */
    public static DataSet extractData(final ResultSet rs, int offset, int count, final RowFilter filter, final boolean closeResultSet) throws SQLException {
        N.checkArgNotNull(rs, "ResultSet");
        N.checkArgNotNegative(offset, "offset");
        N.checkArgNotNegative(count, "count");
        N.checkArgNotNull(filter, "filter");

        try {
            // TODO [performance improvement]. it will improve performance a lot if MetaData is cached.
            final ResultSetMetaData rsmd = rs.getMetaData();
            final int columnCount = rsmd.getColumnCount();
            final List<String> columnNameList = new ArrayList<>(columnCount);
            final List<List<Object>> columnList = new ArrayList<>(columnCount);

            for (int i = 0; i < columnCount;) {
                columnNameList.add(JdbcUtil.getColumnLabel(rsmd, ++i));
                columnList.add(new ArrayList<>());
            }

            JdbcUtil.skip(rs, offset);

            while (count > 0 && rs.next()) {
                if (filter == null || filter.test(rs)) {
                    for (int i = 0; i < columnCount;) {
                        columnList.get(i).add(JdbcUtil.getColumnValue(rs, ++i));
                    }

                    count--;
                }
            }

            // return new RowDataSet(null, entityClass, columnNameList, columnList);
            return new RowDataSet(columnNameList, columnList);
        } finally {
            if (closeResultSet) {
                closeQuietly(rs);
            }
        }
    }

    /**
     * Does table exist.
     *
     * @param conn
     * @param tableName
     * @return true, if successful
     */
    public static boolean doesTableExist(final Connection conn, final String tableName) {
        try {
            executeQuery(conn, "SELECT 1 FROM " + tableName + " WHERE 1 > 2");

            return true;
        } catch (SQLException e) {
            if (isTableNotExistsException(e)) {
                return false;
            }

            throw new UncheckedSQLException(e);
        }
    }

    /**
     * Returns {@code true} if succeed to create table, otherwise {@code false} is returned.
     *
     * @param conn
     * @param tableName
     * @param schema
     * @return true, if successful
     */
    public static boolean createTableIfNotExists(final Connection conn, final String tableName, final String schema) {
        if (doesTableExist(conn, tableName)) {
            return false;
        }

        try {
            execute(conn, schema);

            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Returns {@code true} if succeed to drop table, otherwise {@code false} is returned.
     *
     * @param conn
     * @param tableName
     * @return true, if successful
     */
    public static boolean dropTableIfExists(final Connection conn, final String tableName) {
        try {
            if (doesTableExist(conn, tableName)) {
                execute(conn, "DROP TABLE " + tableName);

                return true;
            }
        } catch (SQLException e) {
            // ignore.
        }

        return false;
    }

    /**
     * Gets the named parameters.
     *
     * @param sql
     * @return
     */
    public static List<String> getNamedParameters(String sql) {
        return NamedSQL.parse(sql).getNamedParameters();
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final Connection conn, final String insertSQL) throws UncheckedSQLException {
        return importData(dataset, dataset.columnNameList(), conn, insertSQL);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param selectColumnNames
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final Collection<String> selectColumnNames, final Connection conn, final String insertSQL)
            throws UncheckedSQLException {
        return importData(dataset, selectColumnNames, 0, dataset.size(), conn, insertSQL);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param selectColumnNames
     * @param offset
     * @param count
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final Collection<String> selectColumnNames, final int offset, final int count, final Connection conn,
            final String insertSQL) throws UncheckedSQLException {
        return importData(dataset, selectColumnNames, offset, count, conn, insertSQL, 200, 0);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param selectColumnNames
     * @param offset
     * @param count
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @param batchSize
     * @param batchInterval
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final Collection<String> selectColumnNames, final int offset, final int count, final Connection conn,
            final String insertSQL, final int batchSize, final int batchInterval) throws UncheckedSQLException {
        return importData(dataset, selectColumnNames, offset, count, Fn.alwaysTrue(), conn, insertSQL, batchSize, batchInterval);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param <E>
     * @param dataset
     * @param selectColumnNames
     * @param offset
     * @param count
     * @param filter
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @param batchSize
     * @param batchInterval
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> int importData(final DataSet dataset, final Collection<String> selectColumnNames, final int offset, final int count,
            final Throwables.Predicate<? super Object[], E> filter, final Connection conn, final String insertSQL, final int batchSize, final int batchInterval)
            throws UncheckedSQLException, E {
        PreparedStatement stmt = null;

        try {
            stmt = prepareStatement(conn, insertSQL);

            return importData(dataset, selectColumnNames, offset, count, filter, stmt, batchSize, batchInterval);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.closeQuietly(stmt);
        }
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @param columnTypeMap
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("rawtypes")
    public static int importData(final DataSet dataset, final Connection conn, final String insertSQL, final Map<String, ? extends Type> columnTypeMap)
            throws UncheckedSQLException {
        return importData(dataset, 0, dataset.size(), conn, insertSQL, columnTypeMap);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param offset
     * @param count
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @param columnTypeMap
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("rawtypes")
    public static int importData(final DataSet dataset, final int offset, final int count, final Connection conn, final String insertSQL,
            final Map<String, ? extends Type> columnTypeMap) throws UncheckedSQLException {
        return importData(dataset, offset, count, conn, insertSQL, 200, 0, columnTypeMap);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param offset
     * @param count
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @param batchSize
     * @param batchInterval
     * @param columnTypeMap
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("rawtypes")
    public static int importData(final DataSet dataset, final int offset, final int count, final Connection conn, final String insertSQL, final int batchSize,
            final int batchInterval, final Map<String, ? extends Type> columnTypeMap) throws UncheckedSQLException {
        return importData(dataset, offset, count, Fn.alwaysTrue(), conn, insertSQL, batchSize, batchInterval, columnTypeMap);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param <E>
     * @param dataset
     * @param offset
     * @param count
     * @param filter
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @param batchSize
     * @param batchInterval
     * @param columnTypeMap
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    @SuppressWarnings("rawtypes")
    public static <E extends Exception> int importData(final DataSet dataset, final int offset, final int count,
            final Throwables.Predicate<? super Object[], E> filter, final Connection conn, final String insertSQL, final int batchSize, final int batchInterval,
            final Map<String, ? extends Type> columnTypeMap) throws UncheckedSQLException, E {
        PreparedStatement stmt = null;

        try {
            stmt = prepareStatement(conn, insertSQL);

            return importData(dataset, offset, count, filter, stmt, batchSize, batchInterval, columnTypeMap);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.closeQuietly(stmt);
        }
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final Connection conn, final String insertSQL,
            final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> stmtSetter) throws UncheckedSQLException {
        return importData(dataset, 0, dataset.size(), conn, insertSQL, stmtSetter);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param offset
     * @param count
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final int offset, final int count, final Connection conn, final String insertSQL,
            final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> stmtSetter) throws UncheckedSQLException {
        return importData(dataset, offset, count, conn, insertSQL, 200, 0, stmtSetter);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param offset
     * @param count
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @param batchSize
     * @param batchInterval
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final int offset, final int count, final Connection conn, final String insertSQL, final int batchSize,
            final int batchInterval, final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> stmtSetter) throws UncheckedSQLException {
        return importData(dataset, offset, count, Fn.alwaysTrue(), conn, insertSQL, batchSize, batchInterval, stmtSetter);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param <E>
     * @param dataset
     * @param offset
     * @param count
     * @param filter
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @param batchSize
     * @param batchInterval
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> int importData(final DataSet dataset, final int offset, final int count,
            final Throwables.Predicate<? super Object[], E> filter, final Connection conn, final String insertSQL, final int batchSize, final int batchInterval,
            final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> stmtSetter) throws UncheckedSQLException, E {
        PreparedStatement stmt = null;

        try {
            stmt = prepareStatement(conn, insertSQL);

            return importData(dataset, offset, count, filter, stmt, batchSize, batchInterval, stmtSetter);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.closeQuietly(stmt);
        }
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final PreparedStatement stmt) throws UncheckedSQLException {
        return importData(dataset, dataset.columnNameList(), stmt);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param selectColumnNames
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final Collection<String> selectColumnNames, final PreparedStatement stmt) throws UncheckedSQLException {
        return importData(dataset, selectColumnNames, 0, dataset.size(), stmt);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param selectColumnNames
     * @param offset
     * @param count
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final Collection<String> selectColumnNames, final int offset, final int count,
            final PreparedStatement stmt) throws UncheckedSQLException {
        return importData(dataset, selectColumnNames, offset, count, stmt, 200, 0);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param selectColumnNames
     * @param offset
     * @param count
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @param batchSize
     * @param batchInterval
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final Collection<String> selectColumnNames, final int offset, final int count,
            final PreparedStatement stmt, final int batchSize, final int batchInterval) throws UncheckedSQLException {
        return importData(dataset, selectColumnNames, offset, count, Fn.alwaysTrue(), stmt, batchSize, batchInterval);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param <E>
     * @param dataset
     * @param selectColumnNames
     * @param offset
     * @param count
     * @param filter
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @param batchSize
     * @param batchInterval
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> int importData(final DataSet dataset, final Collection<String> selectColumnNames, final int offset, final int count,
            final Throwables.Predicate<? super Object[], E> filter, final PreparedStatement stmt, final int batchSize, final int batchInterval)
            throws UncheckedSQLException, E {
        final Type<?> objType = N.typeOf(Object.class);
        final Map<String, Type<?>> columnTypeMap = new HashMap<>();

        for (String propName : selectColumnNames) {
            columnTypeMap.put(propName, objType);
        }

        return importData(dataset, offset, count, filter, stmt, batchSize, batchInterval, columnTypeMap);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @param columnTypeMap
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("rawtypes")
    public static int importData(final DataSet dataset, final PreparedStatement stmt, final Map<String, ? extends Type> columnTypeMap)
            throws UncheckedSQLException {
        return importData(dataset, 0, dataset.size(), stmt, columnTypeMap);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param offset
     * @param count
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @param columnTypeMap
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("rawtypes")
    public static int importData(final DataSet dataset, final int offset, final int count, final PreparedStatement stmt,
            final Map<String, ? extends Type> columnTypeMap) throws UncheckedSQLException {
        return importData(dataset, offset, count, stmt, 200, 0, columnTypeMap);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param offset
     * @param count
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @param batchSize
     * @param batchInterval
     * @param columnTypeMap
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("rawtypes")
    public static int importData(final DataSet dataset, final int offset, final int count, final PreparedStatement stmt, final int batchSize,
            final int batchInterval, final Map<String, ? extends Type> columnTypeMap) throws UncheckedSQLException {
        return importData(dataset, offset, count, Fn.alwaysTrue(), stmt, batchSize, batchInterval, columnTypeMap);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param <E>
     * @param dataset
     * @param offset
     * @param count
     * @param filter
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @param batchSize
     * @param batchInterval
     * @param columnTypeMap
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    @SuppressWarnings("rawtypes")
    public static <E extends Exception> int importData(final DataSet dataset, final int offset, final int count,
            final Throwables.Predicate<? super Object[], E> filter, final PreparedStatement stmt, final int batchSize, final int batchInterval,
            final Map<String, ? extends Type> columnTypeMap) throws UncheckedSQLException, E {
        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative", offset, count);
        N.checkArgument(batchSize > 0 && batchInterval >= 0, "'batchSize'=%s must be greater than 0 and 'batchInterval'=%s can't be negative", batchSize,
                batchInterval);

        int result = 0;

        try {
            final int columnCount = columnTypeMap.size();
            final List<String> columnNameList = dataset.columnNameList();
            final int[] columnIndexes = new int[columnCount];
            final Type<Object>[] columnTypes = new Type[columnCount];
            final Set<String> columnNameSet = N.newHashSet(columnCount);

            int idx = 0;
            for (String columnName : columnNameList) {
                if (columnTypeMap.containsKey(columnName)) {
                    columnIndexes[idx] = dataset.getColumnIndex(columnName);
                    columnTypes[idx] = columnTypeMap.get(columnName);
                    columnNameSet.add(columnName);
                    idx++;
                }
            }

            if (columnNameSet.size() != columnTypeMap.size()) {
                final List<String> keys = new ArrayList<>(columnTypeMap.keySet());
                keys.removeAll(columnNameSet);
                throw new RuntimeException(keys + " are not included in titles: " + N.toString(columnNameList));
            }

            final Object[] row = filter == null ? null : new Object[columnCount];
            for (int i = offset, size = dataset.size(); result < count && i < size; i++) {
                dataset.absolute(i);

                if (filter == null) {
                    for (int j = 0; j < columnCount; j++) {
                        columnTypes[j].set(stmt, j + 1, dataset.get(columnIndexes[j]));
                    }
                } else {
                    for (int j = 0; j < columnCount; j++) {
                        row[j] = dataset.get(columnIndexes[j]);
                    }

                    if (filter.test(row) == false) {
                        continue;
                    }

                    for (int j = 0; j < columnCount; j++) {
                        columnTypes[j].set(stmt, j + 1, row[j]);
                    }
                }

                stmt.addBatch();

                if ((++result % batchSize) == 0) {
                    executeBatch(stmt);

                    if (batchInterval > 0) {
                        N.sleep(batchInterval);
                    }
                }
            }

            if ((result % batchSize) > 0) {
                executeBatch(stmt);
            }
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }

        return result;
    }

    /**
     *
     * @param dataset
     * @param stmt
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final PreparedStatement stmt,
            final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> stmtSetter) throws UncheckedSQLException {
        return importData(dataset, 0, dataset.size(), stmt, stmtSetter);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param offset
     * @param count
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final int offset, final int count, final PreparedStatement stmt,
            final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> stmtSetter) throws UncheckedSQLException {
        return importData(dataset, offset, count, stmt, 200, 0, stmtSetter);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param offset
     * @param count
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @param batchSize
     * @param batchInterval
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final int offset, final int count, final PreparedStatement stmt, final int batchSize,
            final int batchInterval, final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> stmtSetter) throws UncheckedSQLException {
        return importData(dataset, offset, count, Fn.alwaysTrue(), stmt, batchSize, batchInterval, stmtSetter);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param <E>
     * @param dataset
     * @param offset
     * @param count
     * @param filter
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @param batchSize
     * @param batchInterval
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> int importData(final DataSet dataset, final int offset, final int count,
            final Throwables.Predicate<? super Object[], E> filter, final PreparedStatement stmt, final int batchSize, final int batchInterval,
            final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> stmtSetter) throws UncheckedSQLException, E {
        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative", offset, count);
        N.checkArgument(batchSize > 0 && batchInterval >= 0, "'batchSize'=%s must be greater than 0 and 'batchInterval'=%s can't be negative", batchSize,
                batchInterval);

        final int columnCount = dataset.columnNameList().size();
        final Object[] row = new Object[columnCount];
        int result = 0;

        try {
            for (int i = offset, size = dataset.size(); result < count && i < size; i++) {
                dataset.absolute(i);

                for (int j = 0; j < columnCount; j++) {
                    row[j] = dataset.get(j);
                }

                if (filter != null && filter.test(row) == false) {
                    continue;
                }

                stmtSetter.accept(stmt, row);

                stmt.addBatch();

                if ((++result % batchSize) == 0) {
                    executeBatch(stmt);

                    if (batchInterval > 0) {
                        N.sleep(batchInterval);
                    }
                }
            }

            if ((result % batchSize) > 0) {
                executeBatch(stmt);
            }
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }

        return result;
    }

    /**
     *
     * @param <E>
     * @param file
     * @param conn
     * @param insertSQL
     * @param func
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> long importData(final File file, final Connection conn, final String insertSQL,
            final Throwables.Function<String, Object[], E> func) throws UncheckedSQLException, E {
        return importData(file, 0, Long.MAX_VALUE, conn, insertSQL, 200, 0, func);
    }

    /**
     *
     * @param <E>
     * @param file
     * @param offset
     * @param count
     * @param conn
     * @param insertSQL
     * @param batchSize
     * @param batchInterval
     * @param func
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> long importData(final File file, final long offset, final long count, final Connection conn, final String insertSQL,
            final int batchSize, final int batchInterval, final Throwables.Function<String, Object[], E> func) throws UncheckedSQLException, E {
        PreparedStatement stmt = null;

        try {
            stmt = prepareStatement(conn, insertSQL);

            return importData(file, offset, count, stmt, batchSize, batchInterval, func);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.closeQuietly(stmt);
        }
    }

    /**
     *
     * @param <E>
     * @param file
     * @param stmt
     * @param func
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> long importData(final File file, final PreparedStatement stmt, final Throwables.Function<String, Object[], E> func)
            throws UncheckedSQLException, E {
        return importData(file, 0, Long.MAX_VALUE, stmt, 200, 0, func);
    }

    /**
     * Imports the data from file to database.
     *
     * @param <E>
     * @param file
     * @param offset
     * @param count
     * @param stmt
     * @param batchSize
     * @param batchInterval
     * @param func convert line to the parameters for record insert. Returns a <code>null</code> array to skip the line.
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> long importData(final File file, final long offset, final long count, final PreparedStatement stmt, final int batchSize,
            final int batchInterval, final Throwables.Function<String, Object[], E> func) throws UncheckedSQLException, E {
        Reader reader = null;

        try {
            reader = new FileReader(file);

            return importData(reader, offset, count, stmt, batchSize, batchInterval, func);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            IOUtil.close(reader);
        }
    }

    /**
     *
     * @param <E>
     * @param is
     * @param conn
     * @param insertSQL
     * @param func
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> long importData(final InputStream is, final Connection conn, final String insertSQL,
            final Throwables.Function<String, Object[], E> func) throws UncheckedSQLException, E {
        return importData(is, 0, Long.MAX_VALUE, conn, insertSQL, 200, 0, func);
    }

    /**
     *
     * @param <E>
     * @param is
     * @param offset
     * @param count
     * @param conn
     * @param insertSQL
     * @param batchSize
     * @param batchInterval
     * @param func
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> long importData(final InputStream is, final long offset, final long count, final Connection conn,
            final String insertSQL, final int batchSize, final int batchInterval, final Throwables.Function<String, Object[], E> func)
            throws UncheckedSQLException, E {
        PreparedStatement stmt = null;

        try {
            stmt = prepareStatement(conn, insertSQL);

            return importData(is, offset, count, stmt, batchSize, batchInterval, func);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.closeQuietly(stmt);
        }
    }

    /**
     *
     * @param <E>
     * @param is
     * @param stmt
     * @param func
     * @return
     * @throws E the e
     */
    public static <E extends Exception> long importData(final InputStream is, final PreparedStatement stmt, final Throwables.Function<String, Object[], E> func)
            throws E {
        return importData(is, 0, Long.MAX_VALUE, stmt, 200, 0, func);
    }

    /**
     * Imports the data from file to database.
     *
     * @param <E>
     * @param is
     * @param offset
     * @param count
     * @param stmt
     * @param batchSize
     * @param batchInterval
     * @param func convert line to the parameters for record insert. Returns a <code>null</code> array to skip the line.
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> long importData(final InputStream is, final long offset, final long count, final PreparedStatement stmt,
            final int batchSize, final int batchInterval, final Throwables.Function<String, Object[], E> func) throws UncheckedSQLException, E {
        final Reader reader = new InputStreamReader(is);

        return importData(reader, offset, count, stmt, batchSize, batchInterval, func);
    }

    /**
     *
     * @param <E>
     * @param reader
     * @param conn
     * @param insertSQL
     * @param func
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> long importData(final Reader reader, final Connection conn, final String insertSQL,
            final Throwables.Function<String, Object[], E> func) throws UncheckedSQLException, E {
        return importData(reader, 0, Long.MAX_VALUE, conn, insertSQL, 200, 0, func);
    }

    /**
     *
     * @param <E>
     * @param reader
     * @param offset
     * @param count
     * @param conn
     * @param insertSQL
     * @param batchSize
     * @param batchInterval
     * @param func
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> long importData(final Reader reader, final long offset, final long count, final Connection conn, final String insertSQL,
            final int batchSize, final int batchInterval, final Throwables.Function<String, Object[], E> func) throws UncheckedSQLException, E {
        PreparedStatement stmt = null;

        try {
            stmt = prepareStatement(conn, insertSQL);

            return importData(reader, offset, count, stmt, batchSize, batchInterval, func);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.closeQuietly(stmt);
        }
    }

    /**
     *
     * @param <E>
     * @param reader
     * @param stmt
     * @param func
     * @return
     * @throws E the e
     */
    public static <E extends Exception> long importData(final Reader reader, final PreparedStatement stmt, final Throwables.Function<String, Object[], E> func)
            throws E {
        return importData(reader, 0, Long.MAX_VALUE, stmt, 200, 0, func);
    }

    /**
     * Imports the data from file to database.
     *
     * @param <E>
     * @param reader
     * @param offset
     * @param count
     * @param stmt
     * @param batchSize
     * @param batchInterval
     * @param func convert line to the parameters for record insert. Returns a <code>null</code> array to skip the line.
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> long importData(final Reader reader, long offset, final long count, final PreparedStatement stmt, final int batchSize,
            final int batchInterval, final Throwables.Function<String, Object[], E> func) throws UncheckedSQLException, E {
        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative", offset, count);
        N.checkArgument(batchSize > 0 && batchInterval >= 0, "'batchSize'=%s must be greater than 0 and 'batchInterval'=%s can't be negative", batchSize,
                batchInterval);

        long result = 0;
        final BufferedReader br = Objectory.createBufferedReader(reader);

        try {
            while (offset-- > 0 && br.readLine() != null) {
            }

            String line = null;
            Object[] row = null;

            while (result < count && (line = br.readLine()) != null) {
                row = func.apply(line);

                if (row == null) {
                    continue;
                }

                for (int i = 0, len = row.length; i < len; i++) {
                    stmt.setObject(i + 1, row[i]);
                }

                stmt.addBatch();

                if ((++result % batchSize) == 0) {
                    executeBatch(stmt);

                    if (batchInterval > 0) {
                        N.sleep(batchInterval);
                    }
                }
            }

            if ((result % batchSize) > 0) {
                executeBatch(stmt);
            }
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            Objectory.recycle(br);
        }

        return result;
    }

    /**
     *
     * @param <T>
     * @param <E>
     * @param iter
     * @param conn
     * @param insertSQL
     * @param func
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <T, E extends Exception> long importData(final Iterator<T> iter, final Connection conn, final String insertSQL,
            final Throwables.Function<? super T, Object[], E> func) throws UncheckedSQLException, E {
        return importData(iter, 0, Long.MAX_VALUE, conn, insertSQL, 200, 0, func);
    }

    /**
     *
     * @param <T>
     * @param <E>
     * @param iter
     * @param offset
     * @param count
     * @param conn
     * @param insertSQL
     * @param batchSize
     * @param batchInterval
     * @param func
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <T, E extends Exception> long importData(final Iterator<T> iter, final long offset, final long count, final Connection conn,
            final String insertSQL, final int batchSize, final int batchInterval, final Throwables.Function<? super T, Object[], E> func)
            throws UncheckedSQLException, E {
        PreparedStatement stmt = null;

        try {
            stmt = prepareStatement(conn, insertSQL);

            return importData(iter, offset, count, stmt, batchSize, batchInterval, func);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.closeQuietly(stmt);
        }
    }

    /**
     *
     * @param <T>
     * @param <E>
     * @param iter
     * @param stmt
     * @param func
     * @return
     * @throws E the e
     */
    public static <T, E extends Exception> long importData(final Iterator<T> iter, final PreparedStatement stmt,
            final Throwables.Function<? super T, Object[], E> func) throws E {
        return importData(iter, 0, Long.MAX_VALUE, stmt, 200, 0, func);
    }

    /**
     * Imports the data from Iterator to database.
     *
     * @param <T>
     * @param <E>
     * @param iter
     * @param offset
     * @param count
     * @param stmt
     * @param batchSize
     * @param batchInterval
     * @param func convert element to the parameters for record insert. Returns a <code>null</code> array to skip the line.
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <T, E extends Exception> long importData(final Iterator<T> iter, long offset, final long count, final PreparedStatement stmt,
            final int batchSize, final int batchInterval, final Throwables.Function<? super T, Object[], E> func) throws UncheckedSQLException, E {
        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative", offset, count);
        N.checkArgument(batchSize > 0 && batchInterval >= 0, "'batchSize'=%s must be greater than 0 and 'batchInterval'=%s can't be negative", batchSize,
                batchInterval);

        long result = 0;

        try {
            while (offset-- > 0 && iter.hasNext()) {
                iter.next();
            }

            Object[] row = null;

            while (result < count && iter.hasNext()) {
                row = func.apply(iter.next());

                if (row == null) {
                    continue;
                }

                for (int i = 0, len = row.length; i < len; i++) {
                    stmt.setObject(i + 1, row[i]);
                }

                stmt.addBatch();

                if ((++result % batchSize) == 0) {
                    executeBatch(stmt);

                    if (batchInterval > 0) {
                        N.sleep(batchInterval);
                    }
                }
            }

            if ((result % batchSize) > 0) {
                executeBatch(stmt);
            }
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }

        return result;
    }

    /**
     *
     * @param <T>
     * @param iter
     * @param conn
     * @param insertSQL
     * @param stmtSetter
     * @return
     */
    public static <T> long importData(final Iterator<T> iter, final Connection conn, final String insertSQL,
            final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super T> stmtSetter) {
        return importData(iter, 0, Long.MAX_VALUE, conn, insertSQL, 200, 0, stmtSetter);
    }

    /**
     *
     * @param <T>
     * @param iter
     * @param offset
     * @param count
     * @param conn
     * @param insertSQL
     * @param batchSize
     * @param batchInterval
     * @param stmtSetter
     * @return
     */
    public static <T> long importData(final Iterator<T> iter, final long offset, final long count, final Connection conn, final String insertSQL,
            final int batchSize, final int batchInterval, final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super T> stmtSetter) {
        return importData(iter, offset, count, Fn.alwaysTrue(), conn, insertSQL, batchSize, batchInterval, stmtSetter);
    }

    /**
     *
     * @param <T>
     * @param <E>
     * @param iter
     * @param offset
     * @param count
     * @param filter
     * @param conn
     * @param insertSQL
     * @param batchSize
     * @param batchInterval
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <T, E extends Exception> long importData(final Iterator<T> iter, final long offset, final long count,
            final Throwables.Predicate<? super T, E> filter, final Connection conn, final String insertSQL, final int batchSize, final int batchInterval,
            final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super T> stmtSetter) throws UncheckedSQLException, E {
        PreparedStatement stmt = null;

        try {
            stmt = prepareStatement(conn, insertSQL);

            return importData(iter, offset, count, filter, stmt, batchSize, batchInterval, stmtSetter);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.closeQuietly(stmt);
        }
    }

    /**
     *
     * @param <T>
     * @param iter
     * @param stmt
     * @param stmtSetter
     * @return
     */
    public static <T> long importData(final Iterator<T> iter, final PreparedStatement stmt,
            final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super T> stmtSetter) {
        return importData(iter, 0, Long.MAX_VALUE, stmt, 200, 0, stmtSetter);
    }

    /**
     *
     * @param <T>
     * @param iter
     * @param offset
     * @param count
     * @param stmt
     * @param batchSize
     * @param batchInterval
     * @param stmtSetter
     * @return
     */
    public static <T> long importData(final Iterator<T> iter, long offset, final long count, final PreparedStatement stmt, final int batchSize,
            final int batchInterval, final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super T> stmtSetter) {
        return importData(iter, offset, count, Fn.alwaysTrue(), stmt, batchSize, batchInterval, stmtSetter);
    }

    /**
     * Imports the data from Iterator to database.
     *
     * @param <T>
     * @param <E>
     * @param iter
     * @param offset
     * @param count
     * @param filter
     * @param stmt
     * @param batchSize
     * @param batchInterval
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <T, E extends Exception> long importData(final Iterator<T> iter, long offset, final long count,
            final Throwables.Predicate<? super T, E> filter, final PreparedStatement stmt, final int batchSize, final int batchInterval,
            final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super T> stmtSetter) throws UncheckedSQLException, E {
        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative", offset, count);
        N.checkArgument(batchSize > 0 && batchInterval >= 0, "'batchSize'=%s must be greater than 0 and 'batchInterval'=%s can't be negative", batchSize,
                batchInterval);

        long result = 0;

        try {
            while (offset-- > 0 && iter.hasNext()) {
                iter.next();
            }
            T next = null;
            while (result < count && iter.hasNext()) {
                next = iter.next();

                if (filter != null && filter.test(next) == false) {
                    continue;
                }

                stmtSetter.accept(stmt, next);
                stmt.addBatch();

                if ((++result % batchSize) == 0) {
                    executeBatch(stmt);

                    if (batchInterval > 0) {
                        N.sleep(batchInterval);
                    }
                }
            }

            if ((result % batchSize) > 0) {
                executeBatch(stmt);
            }
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }

        return result;
    }

    /**
     *
     * @param <E>
     * @param conn
     * @param sql
     * @param rowParser
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> void parse(final Connection conn, final String sql, final Throwables.Consumer<Object[], E> rowParser)
            throws UncheckedSQLException, E {
        parse(conn, sql, rowParser, Fn.emptyAction());
    }

    /**
     *
     * @param <E>
     * @param <E2>
     * @param conn
     * @param sql
     * @param rowParser
     * @param onComplete
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     * @throws E2 the e2
     */
    public static <E extends Exception, E2 extends Exception> void parse(final Connection conn, final String sql,
            final Throwables.Consumer<Object[], E> rowParser, final Throwables.Runnable<E2> onComplete) throws UncheckedSQLException, E, E2 {
        parse(conn, sql, 0, Long.MAX_VALUE, rowParser, onComplete);
    }

    /**
     *
     * @param <E>
     * @param conn
     * @param sql
     * @param offset
     * @param count
     * @param rowParser
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> void parse(final Connection conn, final String sql, final long offset, final long count,
            final Throwables.Consumer<Object[], E> rowParser) throws UncheckedSQLException, E {
        parse(conn, sql, offset, count, rowParser, Fn.emptyAction());
    }

    /**
     *
     * @param <E>
     * @param <E2>
     * @param conn
     * @param sql
     * @param offset
     * @param count
     * @param rowParser
     * @param onComplete
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     * @throws E2 the e2
     */
    public static <E extends Exception, E2 extends Exception> void parse(final Connection conn, final String sql, final long offset, final long count,
            final Throwables.Consumer<Object[], E> rowParser, final Throwables.Runnable<E2> onComplete) throws UncheckedSQLException, E, E2 {
        parse(conn, sql, offset, count, 0, 0, rowParser, onComplete);
    }

    /**
     *
     * @param <E>
     * @param conn
     * @param sql
     * @param offset
     * @param count
     * @param processThreadNum
     * @param queueSize
     * @param rowParser
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> void parse(final Connection conn, final String sql, final long offset, final long count, final int processThreadNum,
            final int queueSize, final Throwables.Consumer<Object[], E> rowParser) throws UncheckedSQLException, E {
        parse(conn, sql, offset, count, processThreadNum, queueSize, rowParser, Fn.emptyAction());
    }

    /**
     * Parse the ResultSet obtained by executing query with the specified Connection and sql.
     *
     * @param <E>
     * @param <E2>
     * @param conn
     * @param sql
     * @param offset
     * @param count
     * @param processThreadNum new threads started to parse/process the lines/records
     * @param queueSize size of queue to save the processing records/lines loaded from source data. Default size is 1024.
     * @param rowParser
     * @param onComplete
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     * @throws E2 the e2
     */
    public static <E extends Exception, E2 extends Exception> void parse(final Connection conn, final String sql, final long offset, final long count,
            final int processThreadNum, final int queueSize, final Throwables.Consumer<Object[], E> rowParser, final Throwables.Runnable<E2> onComplete)
            throws UncheckedSQLException, E, E2 {
        PreparedStatement stmt = null;
        try {
            stmt = prepareStatement(conn, sql);

            stmt.setFetchDirection(ResultSet.FETCH_FORWARD);

            stmt.setFetchSize(200);

            parse(stmt, offset, count, processThreadNum, queueSize, rowParser, onComplete);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            closeQuietly(stmt);
        }
    }

    /**
     *
     * @param <E>
     * @param stmt
     * @param rowParser
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> void parse(final PreparedStatement stmt, final Throwables.Consumer<Object[], E> rowParser)
            throws UncheckedSQLException, E {
        parse(stmt, rowParser, Fn.emptyAction());
    }

    /**
     *
     * @param <E>
     * @param <E2>
     * @param stmt
     * @param rowParser
     * @param onComplete
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     * @throws E2 the e2
     */
    public static <E extends Exception, E2 extends Exception> void parse(final PreparedStatement stmt, final Throwables.Consumer<Object[], E> rowParser,
            final Throwables.Runnable<E2> onComplete) throws UncheckedSQLException, E, E2 {
        parse(stmt, 0, Long.MAX_VALUE, rowParser, onComplete);
    }

    /**
     *
     * @param <E>
     * @param stmt
     * @param offset
     * @param count
     * @param rowParser
     * @throws E the e
     */
    public static <E extends Exception> void parse(final PreparedStatement stmt, final long offset, final long count,
            final Throwables.Consumer<Object[], E> rowParser) throws E {
        parse(stmt, offset, count, rowParser, Fn.emptyAction());
    }

    /**
     *
     * @param <E>
     * @param <E2>
     * @param stmt
     * @param offset
     * @param count
     * @param rowParser
     * @param onComplete
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     * @throws E2 the e2
     */
    public static <E extends Exception, E2 extends Exception> void parse(final PreparedStatement stmt, final long offset, final long count,
            final Throwables.Consumer<Object[], E> rowParser, final Throwables.Runnable<E2> onComplete) throws UncheckedSQLException, E, E2 {
        parse(stmt, offset, count, 0, 0, rowParser, onComplete);
    }

    /**
     *
     * @param <E>
     * @param stmt
     * @param offset
     * @param count
     * @param processThreadNum
     * @param queueSize
     * @param rowParser
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> void parse(final PreparedStatement stmt, final long offset, final long count, final int processThreadNum,
            final int queueSize, final Throwables.Consumer<Object[], E> rowParser) throws UncheckedSQLException, E {
        parse(stmt, offset, count, processThreadNum, queueSize, rowParser, Fn.emptyAction());
    }

    /**
     * Parse the ResultSet obtained by executing query with the specified PreparedStatement.
     *
     * @param <E>
     * @param <E2>
     * @param stmt
     * @param offset
     * @param count
     * @param processThreadNum new threads started to parse/process the lines/records
     * @param queueSize size of queue to save the processing records/lines loaded from source data. Default size is 1024.
     * @param rowParser
     * @param onComplete
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     * @throws E2 the e2
     */
    public static <E extends Exception, E2 extends Exception> void parse(final PreparedStatement stmt, final long offset, final long count,
            final int processThreadNum, final int queueSize, final Throwables.Consumer<Object[], E> rowParser, final Throwables.Runnable<E2> onComplete)
            throws UncheckedSQLException, E, E2 {
        ResultSet rs = null;

        try {
            rs = executeQuery(stmt);

            parse(rs, offset, count, processThreadNum, queueSize, rowParser, onComplete);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            closeQuietly(rs);
        }
    }

    /**
     *
     * @param <E>
     * @param rs
     * @param rowParser
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> void parse(final ResultSet rs, final Throwables.Consumer<Object[], E> rowParser) throws UncheckedSQLException, E {
        parse(rs, rowParser, Fn.emptyAction());
    }

    /**
     *
     * @param <E>
     * @param <E2>
     * @param rs
     * @param rowParser
     * @param onComplete
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     * @throws E2 the e2
     */
    public static <E extends Exception, E2 extends Exception> void parse(final ResultSet rs, final Throwables.Consumer<Object[], E> rowParser,
            final Throwables.Runnable<E2> onComplete) throws UncheckedSQLException, E, E2 {
        parse(rs, 0, Long.MAX_VALUE, rowParser, onComplete);
    }

    /**
     *
     * @param <E>
     * @param rs
     * @param offset
     * @param count
     * @param rowParser
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> void parse(final ResultSet rs, long offset, long count, final Throwables.Consumer<Object[], E> rowParser)
            throws UncheckedSQLException, E {
        parse(rs, offset, count, rowParser, Fn.emptyAction());
    }

    /**
     *
     * @param <E>
     * @param <E2>
     * @param rs
     * @param offset
     * @param count
     * @param rowParser
     * @param onComplete
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     * @throws E2 the e2
     */
    public static <E extends Exception, E2 extends Exception> void parse(final ResultSet rs, long offset, long count,
            final Throwables.Consumer<Object[], E> rowParser, final Throwables.Runnable<E2> onComplete) throws UncheckedSQLException, E, E2 {
        parse(rs, offset, count, 0, 0, rowParser, onComplete);
    }

    /**
     *
     * @param <E>
     * @param rs
     * @param offset
     * @param count
     * @param processThreadNum
     * @param queueSize
     * @param rowParser
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> void parse(final ResultSet rs, long offset, long count, final int processThreadNum, final int queueSize,
            final Throwables.Consumer<Object[], E> rowParser) throws UncheckedSQLException, E {
        parse(rs, offset, count, processThreadNum, queueSize, rowParser, Fn.emptyAction());
    }

    /**
     * Parse the ResultSet.
     *
     * @param <E>
     * @param <E2>
     * @param rs
     * @param offset
     * @param count
     * @param processThreadNum new threads started to parse/process the lines/records
     * @param queueSize size of queue to save the processing records/lines loaded from source data. Default size is 1024.
     * @param rowParser
     * @param onComplete
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     * @throws E2 the e2
     */
    public static <E extends Exception, E2 extends Exception> void parse(final ResultSet rs, long offset, long count, final int processThreadNum,
            final int queueSize, final Throwables.Consumer<Object[], E> rowParser, final Throwables.Runnable<E2> onComplete)
            throws UncheckedSQLException, E, E2 {

        final Iterator<Object[]> iter = new ObjIterator<Object[]>() {
            private final JdbcUtil.BiRowMapper<Object[]> biFunc = BiRowMapper.TO_ARRAY;
            private List<String> columnLabels = null;
            private boolean hasNext;

            @Override
            public boolean hasNext() {
                if (hasNext == false) {
                    try {
                        hasNext = rs.next();
                    } catch (SQLException e) {
                        throw new UncheckedSQLException(e);
                    }
                }

                return hasNext;
            }

            @Override
            public Object[] next() {
                if (hasNext() == false) {
                    throw new NoSuchElementException();
                }

                hasNext = false;

                try {
                    if (columnLabels == null) {
                        columnLabels = JdbcUtil.getColumnLabelList(rs);
                    }

                    return biFunc.apply(rs, columnLabels);
                } catch (SQLException e) {
                    throw new UncheckedSQLException(e);
                }
            }
        };

        Iterables.parse(iter, offset, count, processThreadNum, queueSize, rowParser, onComplete);
    }

    /**
     *
     * @param sourceConn
     * @param selectSql
     * @param targetConn
     * @param insertSql
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static long copy(final Connection sourceConn, final String selectSql, final Connection targetConn, final String insertSql)
            throws UncheckedSQLException {
        return copy(sourceConn, selectSql, 200, 0, Integer.MAX_VALUE, targetConn, insertSql, DEFAULT_STMT_SETTER, 200, 0, false);
    }

    /**
     *
     * @param sourceConn
     * @param selectSql
     * @param fetchSize
     * @param offset
     * @param count
     * @param targetConn
     * @param insertSql
     * @param stmtSetter
     * @param batchSize
     * @param batchInterval
     * @param inParallel do the read and write in separated threads.
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static long copy(final Connection sourceConn, final String selectSql, final int fetchSize, final long offset, final long count,
            final Connection targetConn, final String insertSql, final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> stmtSetter,
            final int batchSize, final int batchInterval, final boolean inParallel) throws UncheckedSQLException {
        PreparedStatement selectStmt = null;
        PreparedStatement insertStmt = null;

        int result = 0;

        try {
            insertStmt = prepareStatement(targetConn, insertSql);

            selectStmt = prepareStatement(sourceConn, selectSql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            selectStmt.setFetchSize(fetchSize);

            copy(selectStmt, offset, count, insertStmt, stmtSetter, batchSize, batchInterval, inParallel);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            closeQuietly(selectStmt);
            closeQuietly(insertStmt);
        }

        return result;
    }

    /**
     *
     * @param selectStmt
     * @param insertStmt
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static long copy(final PreparedStatement selectStmt, final PreparedStatement insertStmt,
            final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> stmtSetter) throws UncheckedSQLException {
        return copy(selectStmt, 0, Integer.MAX_VALUE, insertStmt, stmtSetter, 200, 0, false);
    }

    /**
     *
     * @param selectStmt
     * @param offset
     * @param count
     * @param insertStmt
     * @param stmtSetter
     * @param batchSize
     * @param batchInterval
     * @param inParallel do the read and write in separated threads.
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static long copy(final PreparedStatement selectStmt, final long offset, final long count, final PreparedStatement insertStmt,
            final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> stmtSetter, final int batchSize, final int batchInterval,
            final boolean inParallel) throws UncheckedSQLException {
        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative", offset, count);
        N.checkArgument(batchSize > 0 && batchInterval >= 0, "'batchSize'=%s must be greater than 0 and 'batchInterval'=%s can't be negative", batchSize,
                batchInterval);

        @SuppressWarnings("rawtypes")
        final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> setter = (JdbcUtil.BiParametersSetter) (stmtSetter == null
                ? DEFAULT_STMT_SETTER
                : stmtSetter);
        final AtomicLong result = new AtomicLong();

        final Throwables.Consumer<Object[], RuntimeException> rowParser = new Throwables.Consumer<Object[], RuntimeException>() {
            @Override
            public void accept(Object[] row) {
                try {
                    setter.accept(insertStmt, row);

                    insertStmt.addBatch();
                    result.incrementAndGet();

                    if ((result.longValue() % batchSize) == 0) {
                        executeBatch(insertStmt);

                        if (batchInterval > 0) {
                            N.sleep(batchInterval);
                        }
                    }
                } catch (SQLException e) {
                    throw new UncheckedSQLException(e);
                }
            }
        };

        final Throwables.Runnable<RuntimeException> onComplete = new Throwables.Runnable<RuntimeException>() {
            @Override
            public void run() {
                if ((result.longValue() % batchSize) > 0) {
                    try {
                        executeBatch(insertStmt);
                    } catch (SQLException e) {
                        throw new UncheckedSQLException(e);
                    }
                }
            }
        };

        parse(selectStmt, offset, count, 0, inParallel ? DEFAULT_QUEUE_SIZE_FOR_ROW_PARSER : 0, rowParser, onComplete);

        return result.longValue();
    }

    /**
     * Checks if is table not exists exception.
     *
     * @param e
     * @return true, if is table not exists exception
     */
    static boolean isTableNotExistsException(final Throwable e) {
        if (e instanceof SQLException) {
            SQLException sqlException = (SQLException) e;

            if (sqlException.getSQLState() != null && sqlStateForTableNotExists.contains(sqlException.getSQLState())) {
                return true;
            }

            final String msg = N.defaultIfNull(e.getMessage(), "").toLowerCase();
            return N.notNullOrEmpty(msg) && (msg.contains("not exist") || msg.contains("doesn't exist") || msg.contains("not found"));
        } else if (e instanceof UncheckedSQLException) {
            UncheckedSQLException sqlException = (UncheckedSQLException) e;

            if (sqlException.getSQLState() != null && sqlStateForTableNotExists.contains(sqlException.getSQLState())) {
                return true;
            }

            final String msg = N.defaultIfNull(e.getMessage(), "").toLowerCase();
            return N.notNullOrEmpty(msg) && (msg.contains("not exist") || msg.contains("doesn't exist") || msg.contains("not found"));
        }

        return false;
    }

    static final ThreadLocal<Boolean> isSQLLogEnabled_TL = ThreadLocal.withInitial(() -> false);

    /**
     * Enable/Disable sql log in current thread.
     *
     * @param b {@code true} to enable, {@code false} to disable.
     */
    public static void enableSQLLog(boolean b) {
        // synchronized (isSQLLogEnabled_TL) {
        if (logger.isInfoEnabled() && isSQLLogEnabled_TL.get() != b) {
            if (b) {
                logger.info("Turn on [SQL] log");
            } else {
                logger.info("Turn off [SQL] log");
            }
        }

        isSQLLogEnabled_TL.set(b);
        // }
    }

    /**
     * Checks if sql log is enabled or not in current thread.
     *
     * @return {@code true} if it's enabled, otherwise {@code false} is returned.
     */
    public static boolean isSQLLogEnabled() {
        return isSQLLogEnabled_TL.get();
    }

    static final ThreadLocal<Long> minExecutionTimeForSQLPerfLog_TL = ThreadLocal.withInitial(() -> 1000L);

    /**
     * Set minimum execution time to log sql performance in current thread.
     *
     * @param minExecutionTimeForSQLPerfLog Default value is 1000 (milliseconds).
     */
    public static void setMinExecutionTimeForSQLPerfLog(long minExecutionTimeForSQLPerfLog) {
        // synchronized (minExecutionTimeForSQLPerfLog_TL) {
        if (logger.isInfoEnabled() && minExecutionTimeForSQLPerfLog_TL.get() != minExecutionTimeForSQLPerfLog) {
            if (minExecutionTimeForSQLPerfLog >= 0) {
                logger.info("set 'minExecutionTimeForSQLPerfLog' to: " + minExecutionTimeForSQLPerfLog);
            } else {
                logger.info("Turn off SQL perfermance log");
            }
        }

        minExecutionTimeForSQLPerfLog_TL.set(minExecutionTimeForSQLPerfLog);
        // }
    }

    /**
     * Return the minimum execution time in milliseconds to log SQL performance. Default value is 1000 (milliseconds).
     *
     * @return
     */
    public static long getMinExecutionTimeForSQLPerfLog() {
        return minExecutionTimeForSQLPerfLog_TL.get();
    }

    static final ThreadLocal<Boolean> isSpringTransactionalDisabled_TL = ThreadLocal.withInitial(() -> false);

    /**
     * Disable/enable {@code Spring Transactional} in current thread.
     *
     * {@code Spring Transactional} won't be used in fetching Connection if it's disabled.
     *
     * @param b {@code true} to disable, {@code false} to enable it again.
     */
    public static void disableSpringTransactional(boolean b) {
        // synchronized (isSpringTransactionalDisabled_TL) {
        if (isInSpring) {
            if (logger.isWarnEnabled() && isSpringTransactionalDisabled_TL.get() != b) {
                if (b) {
                    logger.warn("Disable Spring Transactional");
                } else {
                    logger.warn("Enable Spring Transactional again");
                }
            }

            isSpringTransactionalDisabled_TL.set(b);
        } else {
            logger.warn("Not in Spring or not able to retrieve Spring Transactional");
        }
        // }
    }

    /**
     * Check if {@code Spring Transactional} is disabled or not in current thread.
     *
     * @return {@code true} if it's disabled, otherwise {@code false} is returned.
     */
    public static boolean isSpringTransactionalDisabled() {
        return isSpringTransactionalDisabled_TL.get();
    }

    /**
     * Checks if is default id prop value.
     *
     * @param propValue
     * @return true, if is default id prop value
     * @deprecated for internal only.
     */
    @Deprecated
    @Internal
    public static boolean isDefaultIdPropValue(final Object propValue) {
        return (propValue == null) || (propValue instanceof Number && (((Number) propValue).longValue() == 0));
    }

    /**
     *
     * @param sqlCmd
     * @throws UncheckedSQLException
     */
    @Beta
    static void run(Throwables.Runnable<SQLException> sqlCmd) throws UncheckedSQLException {
        try {
            sqlCmd.run();
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     *
     * @param <R>
     * @param sqlCmd
     * @return
     * @throws UncheckedSQLException
     */
    @Beta
    static <R> R call(Throwables.Callable<R, SQLException> sqlCmd) throws UncheckedSQLException {
        try {
            return sqlCmd.call();
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     * The backed {@code PreparedStatement/CallableStatement} will be closed by default
     * after any execution methods(which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/list/execute/...).
     * except the {@code 'closeAfterExecution'} flag is set to {@code false} by calling {@code #closeAfterExecution(false)}.
     *
     * <br />
     * Generally, don't cache or reuse the instance of this class,
     * except the {@code 'closeAfterExecution'} flag is set to {@code false} by calling {@code #closeAfterExecution(false)}.
     *
     * <br />
     * Remember: parameter/column index in {@code PreparedStatement/ResultSet} starts from 1, not 0.
     *
     * @author haiyangl
     * @param <S>
     * @param <Q>
     */
    static abstract class AbstractPreparedQuery<S extends PreparedStatement, Q extends AbstractPreparedQuery<S, Q>> implements AutoCloseable {

        final S stmt;

        boolean isFetchDirectionSet = false;

        boolean isBatch = false;

        boolean isCloseAfterExecution = true;

        boolean isClosed = false;

        Runnable closeHandler;

        /**
         * Instantiates a new abstract prepared query.
         *
         * @param stmt
         */
        AbstractPreparedQuery(S stmt) {
            this.stmt = stmt;
        }

        //        /**
        //         * It's designed to void try-catch.
        //         * This method should be called immediately after {@code JdbcUtil#prepareCallableQuery/SQLExecutor#prepareQuery}.
        //         *
        //         * @return
        //         */
        //        public Try<Q> tried() {
        //            assertNotClosed();
        //
        //            return Try.of((Q) this);
        //        }

        /**
         *
         * @param closeAfterExecution default is {@code true}.
         * @return
         */
        public Q closeAfterExecution(boolean closeAfterExecution) {
            assertNotClosed();

            this.isCloseAfterExecution = closeAfterExecution;

            return (Q) this;
        }

        /**
         *
         * @return
         */
        boolean isCloseAfterExecution() {
            return isCloseAfterExecution;
        }

        /**
         *
         * @param closeHandler A task to execute after this {@code Query} is closed
         * @return
         */
        public Q onClose(final Runnable closeHandler) {
            checkArgNotNull(closeHandler, "closeHandler");
            assertNotClosed();

            if (this.closeHandler == null) {
                this.closeHandler = closeHandler;
            } else {
                final Runnable tmp = this.closeHandler;

                this.closeHandler = () -> {
                    try {
                        tmp.run();
                    } finally {
                        closeHandler.run();
                    }
                };
            }

            return (Q) this;
        }

        /**
         * Sets the null.
         *
         * @param parameterIndex starts from 1, not 0.
         * @param sqlType
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setNull(int parameterIndex, int sqlType) throws SQLException {
            stmt.setNull(parameterIndex, sqlType);

            return (Q) this;
        }

        /**
         * Sets the null.
         *
         * @param parameterIndex starts from 1, not 0.
         * @param sqlType
         * @param typeName
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
            stmt.setNull(parameterIndex, sqlType, typeName);

            return (Q) this;
        }

        /**
         * Sets the boolean.
         *
         * @param parameterIndex starts from 1, not 0.
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setBoolean(int parameterIndex, boolean x) throws SQLException {
            stmt.setBoolean(parameterIndex, x);

            return (Q) this;
        }

        /**
         * Sets the boolean.
         *
         * @param parameterIndex
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setBoolean(int parameterIndex, Boolean x) throws SQLException {
            stmt.setBoolean(parameterIndex, N.defaultIfNull(x));

            return (Q) this;
        }

        /**
         * Sets the byte.
         *
         * @param parameterIndex starts from 1, not 0.
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setByte(int parameterIndex, byte x) throws SQLException {
            stmt.setByte(parameterIndex, x);

            return (Q) this;
        }

        /**
         * Sets the byte.
         *
         * @param parameterIndex
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setByte(int parameterIndex, Byte x) throws SQLException {
            stmt.setByte(parameterIndex, N.defaultIfNull(x));

            return (Q) this;
        }

        /**
         * Sets the short.
         *
         * @param parameterIndex starts from 1, not 0.
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setShort(int parameterIndex, short x) throws SQLException {
            stmt.setShort(parameterIndex, x);

            return (Q) this;
        }

        /**
         * Sets the short.
         *
         * @param parameterIndex
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setShort(int parameterIndex, Short x) throws SQLException {
            stmt.setShort(parameterIndex, N.defaultIfNull(x));

            return (Q) this;
        }

        /**
         * Sets the int.
         *
         * @param parameterIndex starts from 1, not 0.
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setInt(int parameterIndex, int x) throws SQLException {
            stmt.setInt(parameterIndex, x);

            return (Q) this;
        }

        /**
         * Sets the int.
         *
         * @param parameterIndex
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setInt(int parameterIndex, Integer x) throws SQLException {
            stmt.setInt(parameterIndex, N.defaultIfNull(x));

            return (Q) this;
        }

        /**
         * Sets the long.
         *
         * @param parameterIndex starts from 1, not 0.
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setLong(int parameterIndex, long x) throws SQLException {
            stmt.setLong(parameterIndex, x);

            return (Q) this;
        }

        /**
         * Sets the long.
         *
         * @param parameterIndex
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setLong(int parameterIndex, Long x) throws SQLException {
            stmt.setLong(parameterIndex, N.defaultIfNull(x));

            return (Q) this;
        }

        /**
         * Sets the float.
         *
         * @param parameterIndex starts from 1, not 0.
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setFloat(int parameterIndex, float x) throws SQLException {
            stmt.setFloat(parameterIndex, x);

            return (Q) this;
        }

        /**
         * Sets the float.
         *
         * @param parameterIndex
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setFloat(int parameterIndex, Float x) throws SQLException {
            stmt.setFloat(parameterIndex, N.defaultIfNull(x));

            return (Q) this;
        }

        /**
         * Sets the double.
         *
         * @param parameterIndex starts from 1, not 0.
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setDouble(int parameterIndex, double x) throws SQLException {
            stmt.setDouble(parameterIndex, N.defaultIfNull(x));

            return (Q) this;
        }

        /**
         * Sets the double.
         *
         * @param parameterIndex
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setDouble(int parameterIndex, Double x) throws SQLException {
            stmt.setDouble(parameterIndex, x);

            return (Q) this;
        }

        /**
         * Sets the big decimal.
         *
         * @param parameterIndex starts from 1, not 0.
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
            stmt.setBigDecimal(parameterIndex, x);

            return (Q) this;
        }

        /**
         * Sets the string.
         *
         * @param parameterIndex starts from 1, not 0.
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setString(int parameterIndex, String x) throws SQLException {
            stmt.setString(parameterIndex, x);

            return (Q) this;
        }

        /**
         * Sets the date.
         *
         * @param parameterIndex starts from 1, not 0.
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setDate(int parameterIndex, java.sql.Date x) throws SQLException {
            stmt.setDate(parameterIndex, x);

            return (Q) this;
        }

        /**
         * Sets the date.
         *
         * @param parameterIndex starts from 1, not 0.
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setDate(int parameterIndex, java.util.Date x) throws SQLException {
            stmt.setDate(parameterIndex, x == null ? null : x instanceof java.sql.Date ? (java.sql.Date) x : new java.sql.Date(x.getTime()));

            return (Q) this;
        }

        /**
         * Sets the time.
         *
         * @param parameterIndex starts from 1, not 0.
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setTime(int parameterIndex, java.sql.Time x) throws SQLException {
            stmt.setTime(parameterIndex, x);

            return (Q) this;
        }

        /**
         * Sets the time.
         *
         * @param parameterIndex starts from 1, not 0.
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setTime(int parameterIndex, java.util.Date x) throws SQLException {
            stmt.setTime(parameterIndex, x == null ? null : x instanceof java.sql.Time ? (java.sql.Time) x : new java.sql.Time(x.getTime()));

            return (Q) this;
        }

        /**
         * Sets the timestamp.
         *
         * @param parameterIndex starts from 1, not 0.
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setTimestamp(int parameterIndex, java.sql.Timestamp x) throws SQLException {
            stmt.setTimestamp(parameterIndex, x);

            return (Q) this;
        }

        /**
         * Sets the timestamp.
         *
         * @param parameterIndex starts from 1, not 0.
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setTimestamp(int parameterIndex, java.util.Date x) throws SQLException {
            stmt.setTimestamp(parameterIndex,
                    x == null ? null : x instanceof java.sql.Timestamp ? (java.sql.Timestamp) x : new java.sql.Timestamp(x.getTime()));

            return (Q) this;
        }

        /**
         * Sets the bytes.
         *
         * @param parameterIndex
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setBytes(int parameterIndex, byte[] x) throws SQLException {
            stmt.setBytes(parameterIndex, x);

            return (Q) this;
        }

        /**
         * Sets the ascii stream.
         *
         * @param parameterIndex
         * @param inputStream
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setAsciiStream(int parameterIndex, InputStream inputStream) throws SQLException {
            stmt.setAsciiStream(parameterIndex, inputStream);

            return (Q) this;
        }

        /**
         * Sets the ascii stream.
         *
         * @param parameterIndex
         * @param inputStream
         * @param length
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setAsciiStream(int parameterIndex, InputStream inputStream, long length) throws SQLException {
            stmt.setAsciiStream(parameterIndex, inputStream, length);

            return (Q) this;
        }

        /**
         * Sets the binary stream.
         *
         * @param parameterIndex
         * @param inputStream
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setBinaryStream(int parameterIndex, InputStream inputStream) throws SQLException {
            stmt.setBinaryStream(parameterIndex, inputStream);

            return (Q) this;
        }

        /**
         * Sets the binary stream.
         *
         * @param parameterIndex
         * @param inputStream
         * @param length
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setBinaryStream(int parameterIndex, InputStream inputStream, long length) throws SQLException {
            stmt.setBinaryStream(parameterIndex, inputStream, length);

            return (Q) this;
        }

        /**
         * Sets the character stream.
         *
         * @param parameterIndex
         * @param reader
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
            stmt.setCharacterStream(parameterIndex, reader);

            return (Q) this;
        }

        /**
         * Sets the character stream.
         *
         * @param parameterIndex
         * @param reader
         * @param length
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {
            stmt.setCharacterStream(parameterIndex, reader, length);

            return (Q) this;
        }

        /**
         * Sets the N character stream.
         *
         * @param parameterIndex
         * @param reader
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setNCharacterStream(int parameterIndex, Reader reader) throws SQLException {
            stmt.setNCharacterStream(parameterIndex, reader);

            return (Q) this;
        }

        /**
         * Sets the N character stream.
         *
         * @param parameterIndex
         * @param reader
         * @param length
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setNCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {
            stmt.setNCharacterStream(parameterIndex, reader, length);

            return (Q) this;
        }

        /**
         * Sets the blob.
         *
         * @param parameterIndex
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setBlob(int parameterIndex, java.sql.Blob x) throws SQLException {
            stmt.setBlob(parameterIndex, x);

            return (Q) this;
        }

        /**
         * Sets the blob.
         *
         * @param parameterIndex
         * @param inputStream
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
            stmt.setBlob(parameterIndex, inputStream);

            return (Q) this;
        }

        /**
         * Sets the blob.
         *
         * @param parameterIndex
         * @param inputStream
         * @param length
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {
            stmt.setBlob(parameterIndex, inputStream, length);

            return (Q) this;
        }

        /**
         * Sets the clob.
         *
         * @param parameterIndex
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setClob(int parameterIndex, java.sql.Clob x) throws SQLException {
            stmt.setClob(parameterIndex, x);

            return (Q) this;
        }

        /**
         * Sets the clob.
         *
         * @param parameterIndex
         * @param reader
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setClob(int parameterIndex, Reader reader) throws SQLException {
            stmt.setClob(parameterIndex, reader);

            return (Q) this;
        }

        /**
         * Sets the clob.
         *
         * @param parameterIndex
         * @param reader
         * @param length
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setClob(int parameterIndex, Reader reader, long length) throws SQLException {
            stmt.setClob(parameterIndex, reader, length);

            return (Q) this;
        }

        /**
         * Sets the N clob.
         *
         * @param parameterIndex
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setNClob(int parameterIndex, java.sql.NClob x) throws SQLException {
            stmt.setNClob(parameterIndex, x);

            return (Q) this;
        }

        /**
         * Sets the N clob.
         *
         * @param parameterIndex
         * @param reader
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setNClob(int parameterIndex, Reader reader) throws SQLException {
            stmt.setNClob(parameterIndex, reader);

            return (Q) this;
        }

        /**
         * Sets the N clob.
         *
         * @param parameterIndex
         * @param reader
         * @param length
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
            stmt.setNClob(parameterIndex, reader, length);

            return (Q) this;
        }

        /**
         * Sets the URL.
         *
         * @param parameterIndex
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setURL(int parameterIndex, URL x) throws SQLException {
            stmt.setURL(parameterIndex, x);

            return (Q) this;
        }

        /**
         * Sets the array.
         *
         * @param parameterIndex
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setArray(int parameterIndex, java.sql.Array x) throws SQLException {
            stmt.setArray(parameterIndex, x);

            return (Q) this;
        }

        /**
         * Sets the SQLXML.
         *
         * @param parameterIndex
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setSQLXML(int parameterIndex, java.sql.SQLXML x) throws SQLException {
            stmt.setSQLXML(parameterIndex, x);

            return (Q) this;
        }

        /**
         * Sets the ref.
         *
         * @param parameterIndex
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setRef(int parameterIndex, java.sql.Ref x) throws SQLException {
            stmt.setRef(parameterIndex, x);

            return (Q) this;
        }

        /**
         * Sets the row id.
         *
         * @param parameterIndex
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setRowId(int parameterIndex, java.sql.RowId x) throws SQLException {
            stmt.setRowId(parameterIndex, x);

            return (Q) this;
        }

        /**
         * Sets the object.
         *
         * @param parameterIndex starts from 1, not 0.
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setObject(int parameterIndex, Object x) throws SQLException {
            if (x == null) {
                stmt.setObject(parameterIndex, x);
            } else {
                N.typeOf(x.getClass()).set(stmt, parameterIndex, x);
            }

            return (Q) this;
        }

        /**
         * Sets the object.
         *
         * @param parameterIndex starts from 1, not 0.
         * @param x
         * @param sqlType
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setObject(int parameterIndex, Object x, int sqlType) throws SQLException {
            stmt.setObject(parameterIndex, x, sqlType);

            return (Q) this;
        }

        /**
         * Sets the object.
         *
         * @param parameterIndex starts from 1, not 0.
         * @param x
         * @param sqlType
         * @param scaleOrLength
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setObject(int parameterIndex, Object x, int sqlType, int scaleOrLength) throws SQLException {
            stmt.setObject(parameterIndex, x, sqlType, scaleOrLength);

            return (Q) this;
        }

        /**
         * Sets the object.
         *
         * @param parameterIndex
         * @param x
         * @param sqlType
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setObject(int parameterIndex, Object x, SQLType sqlType) throws SQLException {
            stmt.setObject(parameterIndex, x, sqlType);

            return (Q) this;
        }

        /**
         * Sets the object.
         *
         * @param parameterIndex
         * @param x
         * @param sqlType
         * @param scaleOrLength
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setObject(int parameterIndex, Object x, SQLType sqlType, int scaleOrLength) throws SQLException {
            stmt.setObject(parameterIndex, x, sqlType, scaleOrLength);

            return (Q) this;
        }

        public Q setObject(int parameterIndex, Object x, Type<Object> type) throws SQLException {
            type.set(stmt, parameterIndex, x);

            return (Q) this;
        }

        /**
         * Sets the parameters.
         *
         * @param param1
         * @param param2
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setParameters(final String param1, final String param2) throws SQLException {
            stmt.setString(1, param1);
            stmt.setString(2, param2);

            return (Q) this;
        }

        /**
         * Sets the parameters.
         *
         * @param param1
         * @param param2
         * @param param3
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setParameters(final String param1, final String param2, final String param3) throws SQLException {
            stmt.setString(1, param1);
            stmt.setString(2, param2);
            stmt.setString(3, param3);

            return (Q) this;
        }

        /**
         * Sets the parameters.
         *
         * @param param1
         * @param param2
         * @param param3
         * @param param4
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setParameters(final String param1, final String param2, final String param3, final String param4) throws SQLException {
            stmt.setString(1, param1);
            stmt.setString(2, param2);
            stmt.setString(3, param3);
            stmt.setString(4, param4);

            return (Q) this;
        }

        /**
         * Sets the parameters.
         *
         * @param param1
         * @param param2
         * @param param3
         * @param param4
         * @param param5
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setParameters(final String param1, final String param2, final String param3, final String param4, final String param5) throws SQLException {
            stmt.setString(1, param1);
            stmt.setString(2, param2);
            stmt.setString(3, param3);
            stmt.setString(4, param4);
            stmt.setString(5, param5);

            return (Q) this;
        }

        /**
         * Sets the parameters.
         *
         * @param param1
         * @param param2
         * @param param3
         * @param param4
         * @param param5
         * @param param6
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setParameters(final String param1, final String param2, final String param3, final String param4, final String param5, final String param6)
                throws SQLException {
            stmt.setString(1, param1);
            stmt.setString(2, param2);
            stmt.setString(3, param3);
            stmt.setString(4, param4);
            stmt.setString(5, param5);
            stmt.setString(6, param6);

            return (Q) this;
        }

        /**
         * Sets the parameters.
         *
         * @param param1
         * @param param2
         * @param param3
         * @param param4
         * @param param5
         * @param param6
         * @param param7
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setParameters(final String param1, final String param2, final String param3, final String param4, final String param5, final String param6,
                final String param7) throws SQLException {
            stmt.setString(1, param1);
            stmt.setString(2, param2);
            stmt.setString(3, param3);
            stmt.setString(4, param4);
            stmt.setString(5, param5);
            stmt.setString(6, param6);
            stmt.setString(7, param7);

            return (Q) this;
        }

        /**
         * Sets the parameters.
         *
         * @param param1
         * @param param2
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setParameters(final Object param1, final Object param2) throws SQLException {
            setObject(1, param1);
            setObject(2, param2);

            return (Q) this;
        }

        /**
         * Sets the parameters.
         *
         * @param param1
         * @param param2
         * @param param3
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setParameters(final Object param1, final Object param2, final Object param3) throws SQLException {
            setObject(1, param1);
            setObject(2, param2);
            setObject(3, param3);

            return (Q) this;
        }

        /**
         * Sets the parameters.
         *
         * @param param1
         * @param param2
         * @param param3
         * @param param4
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setParameters(final Object param1, final Object param2, final Object param3, final Object param4) throws SQLException {
            setObject(1, param1);
            setObject(2, param2);
            setObject(3, param3);
            setObject(4, param4);

            return (Q) this;
        }

        /**
         * Sets the parameters.
         *
         * @param param1
         * @param param2
         * @param param3
         * @param param4
         * @param param5
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setParameters(final Object param1, final Object param2, final Object param3, final Object param4, final Object param5) throws SQLException {
            setObject(1, param1);
            setObject(2, param2);
            setObject(3, param3);
            setObject(4, param4);
            setObject(5, param5);

            return (Q) this;
        }

        /**
         * Sets the parameters.
         *
         * @param param1
         * @param param2
         * @param param3
         * @param param4
         * @param param5
         * @param param6
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setParameters(final Object param1, final Object param2, final Object param3, final Object param4, final Object param5, final Object param6)
                throws SQLException {
            setObject(1, param1);
            setObject(2, param2);
            setObject(3, param3);
            setObject(4, param4);
            setObject(5, param5);
            setObject(6, param6);

            return (Q) this;
        }

        /**
         * Sets the parameters.
         *
         * @param param1
         * @param param2
         * @param param3
         * @param param4
         * @param param5
         * @param param6
         * @param param7
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setParameters(final Object param1, final Object param2, final Object param3, final Object param4, final Object param5, final Object param6,
                final Object param7) throws SQLException {
            setObject(1, param1);
            setObject(2, param2);
            setObject(3, param3);
            setObject(4, param4);
            setObject(5, param5);
            setObject(6, param6);
            setObject(7, param7);

            return (Q) this;
        }

        /**
         * Sets the parameters.
         *
         * @param param1
         * @param param2
         * @param param3
         * @param param4
         * @param param5
         * @param param6
         * @param param7
         * @param param8
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setParameters(final Object param1, final Object param2, final Object param3, final Object param4, final Object param5, final Object param6,
                final Object param7, final Object param8) throws SQLException {
            setObject(1, param1);
            setObject(2, param2);
            setObject(3, param3);
            setObject(4, param4);
            setObject(5, param5);
            setObject(6, param6);
            setObject(7, param7);
            setObject(8, param8);

            return (Q) this;
        }

        /**
         * Sets the parameters.
         *
         * @param param1
         * @param param2
         * @param param3
         * @param param4
         * @param param5
         * @param param6
         * @param param7
         * @param param8
         * @param param9
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setParameters(final Object param1, final Object param2, final Object param3, final Object param4, final Object param5, final Object param6,
                final Object param7, final Object param8, final Object param9) throws SQLException {
            setObject(1, param1);
            setObject(2, param2);
            setObject(3, param3);
            setObject(4, param4);
            setObject(5, param5);
            setObject(6, param6);
            setObject(7, param7);
            setObject(8, param8);
            setObject(9, param9);

            return (Q) this;
        }

        /**
         * Sets the parameters.
         *
         * @param parameters
         * @return
         * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is null.
         * @throws SQLException the SQL exception
         */
        public Q setParameters(final Object[] parameters) throws IllegalArgumentException, SQLException {
            checkArgNotNull(parameters, "parameters");

            int idx = 1;

            for (Object param : parameters) {
                setObject(idx++, param);
            }

            return (Q) this;
        }

        /**
         * Sets the parameters.
         *
         * @param startParameterIndex
         * @param parameters
         * @return
         * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is null.
         * @throws SQLException the SQL exception
         */
        public Q setParameters(final Collection<?> parameters) throws IllegalArgumentException, SQLException {
            checkArgNotNull(parameters, "parameters");

            int idx = 1;

            for (Object param : parameters) {
                setObject(idx++, param);
            }

            return (Q) this;
        }

        /**
         * Sets the parameters.
         *
         * @param paramSetter
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setParameters(final ParametersSetter<? super S> paramSetter) throws SQLException {
            checkArgNotNull(paramSetter, "paramSetter");

            boolean noException = false;

            try {
                paramSetter.accept(stmt);

                noException = true;
            } finally {
                if (noException == false) {
                    close();
                }
            }

            return (Q) this;
        }

        /**
         * Sets the parameters.
         *
         * @param <T>
         * @param parameters
         * @param paramSetter
         * @return
         * @throws SQLException the SQL exception
         */
        public <T> Q setParameters(final T parameters, final BiParametersSetter<? super S, ? super T> paramSetter) throws SQLException {
            checkArgNotNull(paramSetter, "paramSetter");

            boolean noException = false;

            try {
                paramSetter.accept(stmt, parameters);

                noException = true;
            } finally {
                if (noException == false) {
                    close();
                }
            }

            return (Q) this;
        }

        /**
         * Sets the parameters.
         *
         * @param parameters
         * @return
         * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is null.
         * @throws SQLException the SQL exception
         */
        public Q settParameters(final int[] parameters) throws IllegalArgumentException, SQLException {
            return settParameters(1, parameters);
        }

        /**
         * Sets the parameters.
         *
         * @param startParameterIndex
         * @param parameters
         * @return
         * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is null.
         * @throws SQLException the SQL exception
         */
        public Q settParameters(int startParameterIndex, final int[] parameters) throws IllegalArgumentException, SQLException {
            checkArgNotNull(parameters, "parameters");

            for (int param : parameters) {
                stmt.setInt(startParameterIndex++, param);
            }

            return (Q) this;
        }

        /**
         * Sets the parameters.
         *
         * @param parameters
         * @return
         * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is null.
         * @throws SQLException the SQL exception
         */
        public Q settParameters(final long[] parameters) throws IllegalArgumentException, SQLException {
            return settParameters(1, parameters);
        }

        /**
         * Sets the parameters.
         *
         * @param startParameterIndex
         * @param parameters
         * @return
         * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is null.
         * @throws SQLException the SQL exception
         */
        public Q settParameters(int startParameterIndex, final long[] parameters) throws IllegalArgumentException, SQLException {
            checkArgNotNull(parameters, "parameters");

            for (long param : parameters) {
                stmt.setLong(startParameterIndex++, param);
            }

            return (Q) this;
        }

        /**
         * Sets the parameters.
         *
         * @param parameters
         * @return
         * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is null.
         * @throws SQLException the SQL exception
         */
        public Q settParameters(final String[] parameters) throws IllegalArgumentException, SQLException {
            return settParameters(1, parameters);
        }

        /**
         * Sets the parameters.
         *
         * @param startParameterIndex
         * @param parameters
         * @return
         * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is null.
         * @throws SQLException the SQL exception
         */
        public Q settParameters(int startParameterIndex, final String[] parameters) throws IllegalArgumentException, SQLException {
            checkArgNotNull(parameters, "parameters");

            for (String param : parameters) {
                stmt.setString(startParameterIndex++, param);
            }

            return (Q) this;
        }

        /**
         * Sets the parameters.
         *
         * @param <T>
         * @param parameters
         * @param type
         * @return
         * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is null.
         * @throws SQLException the SQL exception
         */
        public <T> Q settParameters(final T[] parameters, final Class<T> type) throws IllegalArgumentException, SQLException {
            return settParameters(1, parameters, type);
        }

        /**
         * Sets the parameters.
         *
         * @param <T>
         * @param startParameterIndex
         * @param parameters
         * @param type
         * @return
         * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is null.
         * @throws SQLException the SQL exception
         */
        public <T> Q settParameters(int startParameterIndex, final T[] parameters, final Class<T> type) throws IllegalArgumentException, SQLException {
            checkArgNotNull(parameters, "parameters");
            checkArgNotNull(type, "type");

            final Type<T> setter = N.typeOf(type);

            for (T param : parameters) {
                setter.set(stmt, startParameterIndex++, param);
            }

            return (Q) this;
        }

        /**
         * Sets the parameters.
         *
         * @param <T>
         * @param startParameterIndex
         * @param parameters
         * @param type
         * @return
         * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is null.
         * @throws SQLException the SQL exception
         */
        public <T> Q settParameters(final Collection<? extends T> parameters, final Class<T> type) throws IllegalArgumentException, SQLException {
            return settParameters(1, parameters, type);
        }

        /**
         * Sets the parameters.
         *
         * @param <T>
         * @param startParameterIndex
         * @param parameters
         * @param type
         * @return
         * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is null.
         * @throws SQLException the SQL exception
         */
        public <T> Q settParameters(int startParameterIndex, final Collection<? extends T> parameters, final Class<T> type)
                throws IllegalArgumentException, SQLException {
            checkArgNotNull(parameters, "parameters");
            checkArgNotNull(type, "type");

            final Type<T> setter = N.typeOf(type);

            for (T param : parameters) {
                setter.set(stmt, startParameterIndex++, param);
            }

            return (Q) this;
        }

        /**
         *
         * @param paramSetter
         * @return
         * @throws SQLException the SQL exception
         */
        public Q settParameters(ParametersSetter<? super Q> paramSetter) throws SQLException {
            checkArgNotNull(paramSetter, "paramSetter");

            boolean noException = false;

            try {
                paramSetter.accept((Q) this);

                noException = true;
            } finally {
                if (noException == false) {
                    close();
                }
            }

            return (Q) this;
        }

        /**
         *
         * @param <T>
         * @param parameter
         * @param paramSetter
         * @return
         * @throws SQLException the SQL exception
         */
        public <T> Q settParameters(final T parameter, BiParametersSetter<? super Q, ? super T> paramSetter) throws SQLException {
            checkArgNotNull(paramSetter, "paramSetter");

            boolean noException = false;

            try {
                paramSetter.accept((Q) this, parameter);

                noException = true;
            } finally {
                if (noException == false) {
                    close();
                }
            }

            return (Q) this;
        }

        //    /**
        //     * @param <T>
        //     * @param batchParameters
        //     * @param parametersSetter
        //     * @return
        //     * @throws SQLException the SQL exception
        //     */
        //    <T> Q setBatchParameters(final Collection<T> batchParameters, BiParametersSetter<? super Q, ? super T> parametersSetter) throws SQLException {
        //        return setBatchParameters(batchParameters.iterator(), parametersSetter);
        //    }
        //
        //    /**
        //     *
        //     * @param <T>
        //     * @param batchParameters
        //     * @param parametersSetter
        //     * @return
        //     * @throws SQLException the SQL exception
        //     */
        //    <T> Q setBatchParameters(final Iterator<T> batchParameters, BiParametersSetter<? super Q, ? super T> parametersSetter) throws SQLException {
        //        checkArgNotNull(batchParameters, "batchParameters");
        //        checkArgNotNull(parametersSetter, "parametersSetter");
        //
        //        boolean noException = false;
        //
        //        try {
        //            if (isBatch) {
        //                stmt.clearBatch();
        //            }
        //
        //            final Iterator<T> iter = batchParameters;
        //
        //            while (iter.hasNext()) {
        //                parametersSetter.accept((Q) this, iter.next());
        //                stmt.addBatch();
        //                isBatch = true;
        //            }
        //
        //            noException = true;
        //        } finally {
        //            if (noException == false) {
        //                close();
        //            }
        //        }
        //
        //        return (Q) this;
        //    }

        /**
         * @param <T>
         * @param batchParameters
         * @param parametersSetter
         * @return
         * @throws SQLException the SQL exception
         */
        @Beta
        public <T> Q addBatchParameters(final Collection<T> batchParameters, BiParametersSetter<? super Q, ? super T> parametersSetter) throws SQLException {
            checkArgNotNull(batchParameters, "batchParameters");
            checkArgNotNull(parametersSetter, "parametersSetter");

            return addBatchParameters(batchParameters.iterator(), parametersSetter);
        }

        /**
         *
         * @param <T>
         * @param batchParameters
         * @param parametersSetter
         * @return
         * @throws SQLException the SQL exception
         */
        @Beta
        public <T> Q addBatchParameters(final Iterator<T> batchParameters, BiParametersSetter<? super Q, ? super T> parametersSetter) throws SQLException {
            checkArgNotNull(batchParameters, "batchParameters");
            checkArgNotNull(parametersSetter, "parametersSetter");

            boolean noException = false;

            try {
                final Iterator<T> iter = batchParameters;

                while (iter.hasNext()) {
                    parametersSetter.accept((Q) this, iter.next());
                    stmt.addBatch();
                    isBatch = true;
                }

                noException = true;
            } finally {
                if (noException == false) {
                    close();
                }
            }

            return (Q) this;
        }

        /**
         * @param <T>
         * @param batchParameters
         * @param parametersSetter
         * @return
         * @throws SQLException the SQL exception
         */
        @Beta
        public <T> Q addBatchParameters2(final Collection<T> batchParameters, BiParametersSetter<? super S, ? super T> parametersSetter) throws SQLException {
            checkArgNotNull(batchParameters, "batchParameters");
            checkArgNotNull(parametersSetter, "parametersSetter");

            return addBatchParameters2(batchParameters.iterator(), parametersSetter);
        }

        /**
         *
         * @param <T>
         * @param batchParameters
         * @param parametersSetter
         * @return
         * @throws SQLException the SQL exception
         */
        @Beta
        public <T> Q addBatchParameters2(final Iterator<T> batchParameters, BiParametersSetter<? super S, ? super T> parametersSetter) throws SQLException {
            checkArgNotNull(batchParameters, "batchParameters");
            checkArgNotNull(parametersSetter, "parametersSetter");

            boolean noException = false;

            try {
                final Iterator<T> iter = batchParameters;

                while (iter.hasNext()) {
                    parametersSetter.accept(stmt, iter.next());
                    stmt.addBatch();
                    isBatch = true;
                }

                noException = true;
            } finally {
                if (noException == false) {
                    close();
                }
            }

            return (Q) this;
        }

        /**
         * Adds the batch.
         *
         * @return
         * @throws SQLException the SQL exception
         */
        public Q addBatch() throws SQLException {
            stmt.addBatch();
            isBatch = true;

            return (Q) this;
        }

        /**
         * Sets the fetch direction.
         *
         * @param direction one of <code>ResultSet.FETCH_FORWARD</code>,
         * <code>ResultSet.FETCH_REVERSE</code>, or <code>ResultSet.FETCH_UNKNOWN</code>
         * @return
         * @throws SQLException the SQL exception
         * @see {@link java.sql.Statement#setFetchDirection(int)}
         */
        public Q setFetchDirection(FetchDirection direction) throws SQLException {
            isFetchDirectionSet = true;

            stmt.setFetchDirection(direction.intValue);

            return (Q) this;
        }

        /**
         * Sets the fetch size.
         *
         * @param rows
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setFetchSize(int rows) throws SQLException {
            stmt.setFetchSize(rows);

            return (Q) this;
        }

        /**
         * Sets the max rows.
         *
         * @param max
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setMaxRows(int max) throws SQLException {
            stmt.setMaxRows(max);

            return (Q) this;
        }

        /**
         * Sets the large max rows.
         *
         * @param max
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setLargeMaxRows(long max) throws SQLException {
            stmt.setLargeMaxRows(max);

            return (Q) this;
        }

        /**
         * Sets the max field size.
         *
         * @param max
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setMaxFieldSize(int max) throws SQLException {
            stmt.setMaxFieldSize(max);

            return (Q) this;
        }

        /**
         * Sets the query timeout.
         *
         * @param seconds
         * @return
         * @throws SQLException the SQL exception
         */
        public Q setQueryTimeout(int seconds) throws SQLException {
            stmt.setQueryTimeout(seconds);

            return (Q) this;
        }

        /**
         * Query for boolean.
         *
         * @return
         * @throws SQLException the SQL exception
         */
        public OptionalBoolean queryForBoolean() throws SQLException {
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                return rs.next() ? OptionalBoolean.of(rs.getBoolean(1)) : OptionalBoolean.empty();
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /** The Constant charType. */
        private static final Type<Character> charType = TypeFactory.getType(char.class);

        /**
         * Query for char.
         *
         * @return
         * @throws SQLException the SQL exception
         */
        public OptionalChar queryForChar() throws SQLException {
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                if (rs.next()) {
                    return OptionalChar.of(charType.get(rs, 1));
                } else {
                    return OptionalChar.empty();
                }
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         * Query for byte.
         *
         * @return
         * @throws SQLException the SQL exception
         */
        public OptionalByte queryForByte() throws SQLException {
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                return rs.next() ? OptionalByte.of(rs.getByte(1)) : OptionalByte.empty();
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         * Query for short.
         *
         * @return
         * @throws SQLException the SQL exception
         */
        public OptionalShort queryForShort() throws SQLException {
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                return rs.next() ? OptionalShort.of(rs.getShort(1)) : OptionalShort.empty();
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         * Query for int.
         *
         * @return
         * @throws SQLException the SQL exception
         */
        public OptionalInt queryForInt() throws SQLException {
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                return rs.next() ? OptionalInt.of(rs.getInt(1)) : OptionalInt.empty();
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         * Query for long.
         *
         * @return
         * @throws SQLException the SQL exception
         */
        public OptionalLong queryForLong() throws SQLException {
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                return rs.next() ? OptionalLong.of(rs.getLong(1)) : OptionalLong.empty();
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         * Query for float.
         *
         * @return
         * @throws SQLException the SQL exception
         */
        public OptionalFloat queryForFloat() throws SQLException {
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                return rs.next() ? OptionalFloat.of(rs.getFloat(1)) : OptionalFloat.empty();
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         * Query for double.
         *
         * @return
         * @throws SQLException the SQL exception
         */
        public OptionalDouble queryForDouble() throws SQLException {
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                return rs.next() ? OptionalDouble.of(rs.getDouble(1)) : OptionalDouble.empty();
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         * Query for string.
         *
         * @return
         * @throws SQLException the SQL exception
         */
        public Nullable<String> queryForString() throws SQLException {
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                return rs.next() ? Nullable.of(rs.getString(1)) : Nullable.<String> empty();
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         * Query big decimal.
         *
         * @return
         * @throws SQLException the SQL exception
         */
        public Nullable<BigDecimal> queryBigDecimal() throws SQLException {
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                return rs.next() ? Nullable.of(rs.getBigDecimal(1)) : Nullable.<BigDecimal> empty();
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         * Query for date.
         *
         * @return
         * @throws SQLException the SQL exception
         */
        public Nullable<java.sql.Date> queryForDate() throws SQLException {
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                return rs.next() ? Nullable.of(rs.getDate(1)) : Nullable.<java.sql.Date> empty();
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         * Query for time.
         *
         * @return
         * @throws SQLException the SQL exception
         */
        public Nullable<java.sql.Time> queryForTime() throws SQLException {
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                return rs.next() ? Nullable.of(rs.getTime(1)) : Nullable.<java.sql.Time> empty();
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         * Query for timestamp.
         *
         * @return
         * @throws SQLException the SQL exception
         */
        public Nullable<java.sql.Timestamp> queryForTimestamp() throws SQLException {
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                return rs.next() ? Nullable.of(rs.getTimestamp(1)) : Nullable.<java.sql.Timestamp> empty();
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         * Returns a {@code Nullable} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
         *
         * @param <V> the value type
         * @param targetClass
         * @return
         * @throws SQLException the SQL exception
         */
        public <V> Nullable<V> queryForSingleResult(Class<V> targetClass) throws SQLException {
            checkArgNotNull(targetClass, "targetClass");
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                return rs.next() ? Nullable.of(N.convert(JdbcUtil.getColumnValue(rs, 1), targetClass)) : Nullable.<V> empty();
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         * Returns an {@code Optional} describing the value in the first row/column if it exists, otherwise return an empty {@code Optional}.
         *
         * @param <V> the value type
         * @param targetClass
         * @return
         * @throws SQLException the SQL exception
         */
        public <V> Optional<V> queryForSingleNonNull(Class<V> targetClass) throws SQLException {
            checkArgNotNull(targetClass, "targetClass");
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                return rs.next() ? Optional.of(N.convert(JdbcUtil.getColumnValue(rs, 1), targetClass)) : Optional.<V> empty();
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         * Returns a {@code Nullable} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
         * And throws {@code DuplicatedResultException} if more than one record found.
         *
         * @param <V> the value type
         * @param targetClass
         * @return
         * @throws DuplicatedResultException if more than one record found.
         * @throws SQLException the SQL exception
         */
        public <V> Nullable<V> queryForUniqueResult(Class<V> targetClass) throws DuplicatedResultException, SQLException {
            checkArgNotNull(targetClass, "targetClass");
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                final Nullable<V> result = rs.next() ? Nullable.of(N.convert(JdbcUtil.getColumnValue(rs, 1), targetClass)) : Nullable.<V> empty();

                if (result.isPresent() && rs.next()) {
                    throw new DuplicatedResultException(
                            "At least two results found: " + Strings.concat(result.get(), ", ", N.convert(JdbcUtil.getColumnValue(rs, 1), targetClass)));
                }

                return result;
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         * Returns an {@code Optional} describing the value in the first row/column if it exists, otherwise return an empty {@code Optional}.
         * And throws {@code DuplicatedResultException} if more than one record found.
         *
         * @param <V> the value type
         * @param targetClass
         * @return
         * @throws DuplicatedResultException if more than one record found.
         * @throws SQLException the SQL exception
         */
        public <V> Optional<V> queryForUniqueNonNull(Class<V> targetClass) throws DuplicatedResultException, SQLException {
            checkArgNotNull(targetClass, "targetClass");
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                final Optional<V> result = rs.next() ? Optional.of(N.convert(JdbcUtil.getColumnValue(rs, 1), targetClass)) : Optional.<V> empty();

                if (result.isPresent() && rs.next()) {
                    throw new DuplicatedResultException(
                            "At least two results found: " + Strings.concat(result.get(), ", ", N.convert(JdbcUtil.getColumnValue(rs, 1), targetClass)));
                }

                return result;
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         *
         * @param <T>
         * @param targetClass
         * @param rs
         * @return
         * @throws SQLException the SQL exception
         */
        private <T> T get(Class<T> targetClass, ResultSet rs) throws SQLException {
            final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

            return BiRowMapper.to(targetClass).apply(rs, columnLabels);
        }

        /**
         *
         * @return
         * @throws SQLException the SQL exception
         */
        public DataSet query() throws SQLException {
            return query(ResultExtractor.TO_DATA_SET);
        }

        /**
         *
         * @param <R>
         * @param resultExtrator
         * @return
         * @throws SQLException the SQL exception
         */
        public <R> R query(final ResultExtractor<R> resultExtrator) throws SQLException {
            checkArgNotNull(resultExtrator, "resultExtrator");
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                return checkNotResultSet(resultExtrator.apply(rs));
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         *
         * @param <R>
         * @param resultExtrator
         * @return
         * @throws SQLException the SQL exception
         */
        public <R> R query(final BiResultExtractor<R> resultExtrator) throws SQLException {
            checkArgNotNull(resultExtrator, "resultExtrator");
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                return checkNotResultSet(resultExtrator.apply(rs, getColumnLabelList(rs)));
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         *
         * @param <T>
         * @param targetClass
         * @return
         * @throws DuplicatedResultException If there are more than one record found by the query
         * @throws SQLException the SQL exception
         */
        public <T> Optional<T> get(final Class<T> targetClass) throws DuplicatedResultException, SQLException {
            return Optional.ofNullable(gett(targetClass));
        }

        /**
         *
         * @param <T>
         * @param rowMapper
         * @return
         * @throws DuplicatedResultException If there are more than one record found by the query
         * @throws SQLException the SQL exception
         */
        public <T> Optional<T> get(RowMapper<T> rowMapper) throws DuplicatedResultException, SQLException {
            return Optional.ofNullable(gett(rowMapper));
        }

        /**
         *
         * @param <T>
         * @param rowMapper
         * @return
         * @throws DuplicatedResultException If there are more than one record found by the query
         * @throws SQLException the SQL exception
         */
        public <T> Optional<T> get(BiRowMapper<T> rowMapper) throws DuplicatedResultException, SQLException {
            return Optional.ofNullable(gett(rowMapper));
        }

        /**
         * Gets the t.
         *
         * @param <T>
         * @param targetClass
         * @return
         * @throws DuplicatedResultException If there are more than one record found by the query
         * @throws SQLException the SQL exception
         */
        public <T> T gett(final Class<T> targetClass) throws DuplicatedResultException, SQLException {
            checkArgNotNull(targetClass, "targetClass");
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                if (rs.next()) {
                    final T result = Objects.requireNonNull(get(targetClass, rs));

                    if (rs.next()) {
                        throw new DuplicatedResultException("There are more than one record found by the query");
                    }

                    return result;
                } else {
                    return null;
                }
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         * Gets the t.
         *
         * @param <T>
         * @param rowMapper
         * @return
         * @throws DuplicatedResultException If there are more than one record found by the query
         * @throws SQLException the SQL exception
         */
        public <T> T gett(RowMapper<T> rowMapper) throws DuplicatedResultException, SQLException {
            checkArgNotNull(rowMapper, "rowMapper");
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                if (rs.next()) {
                    final T result = Objects.requireNonNull(rowMapper.apply(rs));

                    if (rs.next()) {
                        throw new DuplicatedResultException("There are more than one record found by the query");
                    }

                    return result;
                } else {
                    return null;
                }

            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         * Gets the t.
         *
         * @param <T>
         * @param rowMapper
         * @return
         * @throws DuplicatedResultException If there are more than one record found by the query
         * @throws SQLException the SQL exception
         */
        public <T> T gett(BiRowMapper<T> rowMapper) throws DuplicatedResultException, SQLException {
            checkArgNotNull(rowMapper, "rowMapper");
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                if (rs.next()) {
                    final T result = Objects.requireNonNull(rowMapper.apply(rs, JdbcUtil.getColumnLabelList(rs)));

                    if (rs.next()) {
                        throw new DuplicatedResultException("There are more than one record found by the query");
                    }

                    return result;
                } else {
                    return null;
                }

            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         *
         * @param <T>
         * @param targetClass
         * @return
         * @throws SQLException the SQL exception
         */
        public <T> Optional<T> findFirst(final Class<T> targetClass) throws SQLException {
            checkArgNotNull(targetClass, "targetClass");
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                if (rs.next()) {
                    return Optional.of(get(targetClass, rs));
                } else {
                    return Optional.empty();
                }
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         *
         * @param <T>
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        public <T> Optional<T> findFirst(RowMapper<T> rowMapper) throws SQLException {
            checkArgNotNull(rowMapper, "rowMapper");
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                return rs.next() ? Optional.of(rowMapper.apply(rs)) : Optional.<T> empty();
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         *
         * @param <T>
         * @param rowFilter
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        public <T> Optional<T> findFirst(final RowFilter rowFilter, RowMapper<T> rowMapper) throws SQLException {
            checkArgNotNull(rowFilter, "rowFilter");
            checkArgNotNull(rowMapper, "rowMapper");
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                while (rs.next()) {
                    if (rowFilter.test(rs)) {
                        return Optional.of(rowMapper.apply(rs));
                    }
                }

                return Optional.empty();
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         *
         * @param <T>
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        public <T> Optional<T> findFirst(BiRowMapper<T> rowMapper) throws SQLException {
            checkArgNotNull(rowMapper, "rowMapper");
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                return rs.next() ? Optional.of(rowMapper.apply(rs, JdbcUtil.getColumnLabelList(rs))) : Optional.<T> empty();
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         *
         * @param <T>
         * @param rowFilter
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        public <T> Optional<T> findFirst(final BiRowFilter rowFilter, BiRowMapper<T> rowMapper) throws SQLException {
            checkArgNotNull(rowFilter, "rowFilter");
            checkArgNotNull(rowMapper, "rowMapper");
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

                while (rs.next()) {
                    if (rowFilter.test(rs, columnLabels)) {
                        return Optional.of(rowMapper.apply(rs, columnLabels));
                    }
                }

                return Optional.empty();
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         *
         * @param <T>
         * @param targetClass
         * @return
         * @throws SQLException the SQL exception
         */
        public <T> List<T> list(final Class<T> targetClass) throws SQLException {
            return list(BiRowMapper.to(targetClass));
        }

        /**
         *
         * @param <T>
         * @param targetClass
         * @param maxResult
         * @return
         * @throws SQLException the SQL exception
         * @deprecated the result size should be limited in database server side by sql scripts.
         */
        @Deprecated
        public <T> List<T> list(final Class<T> targetClass, int maxResult) throws SQLException {
            return list(BiRowMapper.to(targetClass), maxResult);
        }

        /**
         *
         * @param <T>
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        public <T> List<T> list(RowMapper<T> rowMapper) throws SQLException {
            return list(rowMapper, Integer.MAX_VALUE);
        }

        /**
         *
         * @param <T>
         * @param rowMapper
         * @param maxResult
         * @return
         * @throws SQLException the SQL exception
         * @deprecated the result size should be limited in database server side by sql scripts.
         */
        @Deprecated
        public <T> List<T> list(RowMapper<T> rowMapper, int maxResult) throws SQLException {
            return list(RowFilter.ALWAYS_TRUE, rowMapper, maxResult);
        }

        /**
         *
         * @param <T>
         * @param rowFilter
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        public <T> List<T> list(final RowFilter rowFilter, RowMapper<T> rowMapper) throws SQLException {
            return list(rowFilter, rowMapper, Integer.MAX_VALUE);
        }

        /**
         *
         * @param <T>
         * @param rowFilter
         * @param rowMapper
         * @param maxResult
         * @return
         * @throws SQLException the SQL exception
         */
        public <T> List<T> list(final RowFilter rowFilter, RowMapper<T> rowMapper, int maxResult) throws SQLException {
            checkArgNotNull(rowFilter, "rowFilter");
            checkArgNotNull(rowMapper, "rowMapper");
            checkArg(maxResult >= 0, "'maxResult' can' be negative: " + maxResult);
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                final List<T> result = new ArrayList<>();

                while (maxResult > 0 && rs.next()) {
                    if (rowFilter.test(rs)) {
                        result.add(rowMapper.apply(rs));
                        maxResult--;
                    }
                }

                return result;
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         *
         * @param <T>
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        public <T> List<T> list(BiRowMapper<T> rowMapper) throws SQLException {
            return list(rowMapper, Integer.MAX_VALUE);
        }

        /**
         *
         * @param <T>
         * @param rowMapper
         * @param maxResult
         * @return
         * @throws SQLException the SQL exception
         * @deprecated the result size should be limited in database server side by sql scripts.
         */
        @Deprecated
        public <T> List<T> list(BiRowMapper<T> rowMapper, int maxResult) throws SQLException {
            return list(BiRowFilter.ALWAYS_TRUE, rowMapper, maxResult);
        }

        /**
         *
         * @param <T>
         * @param rowFilter
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        public <T> List<T> list(final BiRowFilter rowFilter, BiRowMapper<T> rowMapper) throws SQLException {
            return list(rowFilter, rowMapper, Integer.MAX_VALUE);
        }

        /**
         *
         * @param <T>
         * @param rowFilter
         * @param rowMapper
         * @param maxResult
         * @return
         * @throws SQLException the SQL exception
         */
        public <T> List<T> list(final BiRowFilter rowFilter, BiRowMapper<T> rowMapper, int maxResult) throws SQLException {
            checkArgNotNull(rowFilter, "rowFilter");
            checkArgNotNull(rowMapper, "rowMapper");
            checkArg(maxResult >= 0, "'maxResult' can' be negative: " + maxResult);
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);
                final List<T> result = new ArrayList<>();

                while (maxResult > 0 && rs.next()) {
                    if (rowFilter.test(rs, columnLabels)) {
                        result.add(rowMapper.apply(rs, columnLabels));
                        maxResult--;
                    }
                }

                return result;
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         *
         * @param <K>
         * @param <V>
         * @param keyMapper
         * @param valueMapper
         * @return
         * @throws DuplicatedResultException
         * @throws SQLException the SQL exception
         * @see ResultExtractor#toMap(RowMapper, RowMapper)
         */
        public <K, V> Map<K, V> listToMap(final RowMapper<K> keyMapper, final RowMapper<V> valueMapper) throws DuplicatedResultException, SQLException {
            return listToMap(keyMapper, valueMapper, Fn.<V> throwingMerger());
        }

        /**
         *
         * @param <K>
         * @param <V>
         * @param keyMapper
         * @param valueMapper
         * @param mergeFunction
         * @return
         * @throws SQLException the SQL exception
         * @see ResultExtractor#toMap(RowMapper, RowMapper, BinaryOperator)
         */
        public <K, V> Map<K, V> listToMap(final RowMapper<K> keyMapper, final RowMapper<V> valueMapper, final BinaryOperator<V> mergeFunction)
                throws SQLException {
            return listToMap(keyMapper, valueMapper, mergeFunction, Suppliers.<K, V> ofMap());
        }

        /**
         *
         * @param <K>
         * @param <V>
         * @param <M>
         * @param keyMapper
         * @param valueMapper
         * @param mapSupplier
         * @return
         * @throws DuplicatedResultException
         * @throws SQLException the SQL exception
         * @see ResultExtractor#toMap(RowMapper, RowMapper, Supplier)
         */
        public <K, V, M extends Map<K, V>> M listToMap(final RowMapper<K> keyMapper, final RowMapper<V> valueMapper, final Supplier<? extends M> mapSupplier)
                throws DuplicatedResultException, SQLException {
            return listToMap(keyMapper, valueMapper, Fn.<V> throwingMerger(), mapSupplier);
        }

        /**
         *
         * @param <K>
         * @param <V>
         * @param <M>
         * @param keyMapper
         * @param valueMapper
         * @param mergeFunction
         * @param mapSupplier
         * @return
         * @throws SQLException the SQL exception
         * @see ResultExtractor#toMap(RowMapper, RowMapper, BinaryOperator, Supplier)
         */
        public <K, V, M extends Map<K, V>> M listToMap(final RowMapper<K> keyMapper, final RowMapper<V> valueMapper, final BinaryOperator<V> mergeFunction,
                final Supplier<? extends M> mapSupplier) throws SQLException {
            checkArgNotNull(keyMapper, "keyMapper");
            checkArgNotNull(valueMapper, "valueMapper");
            checkArgNotNull(mergeFunction, "mergeFunction");
            checkArgNotNull(mapSupplier, "mapSupplier");
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                final M result = mapSupplier.get();

                while (rs.next()) {
                    merge(result, keyMapper.apply(rs), valueMapper.apply(rs), mergeFunction);
                }

                return result;
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        static <K, V> void merge(Map<K, V> map, K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
            final V oldValue = map.get(key);

            if (oldValue == null && map.containsKey(key) == false) {
                map.put(key, value);
            } else {
                map.put(key, remappingFunction.apply(oldValue, value));
            }
        }

        //    /**
        //     *
        //     * @param <K>
        //     * @param <V>
        //     * @param keyMapper
        //     * @param valueMapper
        //     * @return
        //     * @throws SQLException the SQL exception
        //     */
        //    public <K, V> ListMultimap<K, V> listToMultimap(final RowMapper<K> keyMapper, final RowMapper<V> valueMapper) throws SQLException {
        //        return listToMultimap(keyMapper, valueMapper, Suppliers.<K, V> ofListMultimap());
        //    }
        //
        //    /**
        //     *
        //     * @param <K>
        //     * @param <V>
        //     * @param <C>
        //     * @param <M>
        //     * @param keyMapper
        //     * @param valueMapper
        //     * @param mergeFunction
        //     * @param multimapSupplier
        //     * @return
        //     * @throws SQLException the SQL exception
        //     */
        //    public <K, V, C extends Collection<V>, M extends Multimap<K, V, C>> M listToMultimap(final RowMapper<K> keyMapper, final RowMapper<V> valueMapper,
        //            final Supplier<? extends M> multimapSupplier) throws SQLException {
        //        checkArgNotNull(keyMapper, "keyMapper");
        //        checkArgNotNull(valueMapper, "valueMapper");
        //        checkArgNotNull(multimapSupplier, "multimapSupplier");
        //        assertNotClosed();
        //
        //        try (ResultSet rs = executeQuery()) {
        //            final M result = multimapSupplier.get();
        //
        //            while (rs.next()) {
        //                result.put(keyMapper.apply(rs), valueMapper.apply(rs));
        //            }
        //
        //            return result;
        //        } finally {
        //            closeAfterExecutionIfAllowed();
        //        }
        //    }

        /**
         * lazy-execution, lazy-fetch.
         *
         * @param <T>
         * @param targetClass
         * @return
         * @throws SQLException the SQL exception
         */
        public <T> ExceptionalStream<T, SQLException> stream(final Class<T> targetClass) throws SQLException {
            return stream(BiRowMapper.to(targetClass));
        }

        /**
         * lazy-execution, lazy-fetch.
         *
         * @param <T>
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        public <T> ExceptionalStream<T, SQLException> stream(final RowMapper<T> rowMapper) throws SQLException {
            checkArgNotNull(rowMapper, "rowMapper");
            assertNotClosed();

            final ExceptionalIterator<T, SQLException> lazyIter = ExceptionalIterator
                    .of(new Throwables.Supplier<ExceptionalIterator<T, SQLException>, SQLException>() {
                        private ExceptionalIterator<T, SQLException> internalIter;

                        @Override
                        public ExceptionalIterator<T, SQLException> get() throws SQLException {
                            if (internalIter == null) {
                                ResultSet rs = null;

                                try {
                                    rs = executeQuery();
                                    final ResultSet resultSet = rs;

                                    internalIter = new ExceptionalIterator<T, SQLException>() {
                                        private boolean hasNext;

                                        @Override
                                        public boolean hasNext() throws SQLException {
                                            if (hasNext == false) {
                                                hasNext = resultSet.next();
                                            }

                                            return hasNext;
                                        }

                                        @Override
                                        public T next() throws SQLException {
                                            if (hasNext() == false) {
                                                throw new NoSuchElementException();
                                            }

                                            hasNext = false;

                                            return rowMapper.apply(resultSet);
                                        }

                                        @Override
                                        public void skip(long n) throws SQLException {
                                            N.checkArgNotNegative(n, "n");

                                            final long m = hasNext ? n - 1 : n;
                                            hasNext = false;

                                            JdbcUtil.skip(resultSet, m);
                                        }

                                        @Override
                                        public long count() throws SQLException {
                                            long cnt = hasNext ? 1 : 0;
                                            hasNext = false;

                                            while (resultSet.next()) {
                                                cnt++;
                                            }

                                            return cnt;
                                        }

                                        @Override
                                        public void close() throws SQLException {
                                            try {
                                                JdbcUtil.closeQuietly(resultSet);
                                            } finally {
                                                closeAfterExecutionIfAllowed();
                                            }
                                        }
                                    };
                                } finally {
                                    if (internalIter == null) {
                                        try {
                                            JdbcUtil.closeQuietly(rs);
                                        } finally {
                                            closeAfterExecutionIfAllowed();
                                        }
                                    }
                                }
                            }

                            return internalIter;
                        }
                    });

            return ExceptionalStream.newStream(lazyIter).onClose(new Throwables.Runnable<SQLException>() {
                @Override
                public void run() throws SQLException {
                    lazyIter.close();
                }
            });
        }

        /**
         * lazy-execution, lazy-fetch.
         *
         * @param <T>
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        public <T> ExceptionalStream<T, SQLException> stream(final BiRowMapper<T> rowMapper) throws SQLException {
            checkArgNotNull(rowMapper, "rowMapper");
            assertNotClosed();

            final ExceptionalIterator<T, SQLException> lazyIter = ExceptionalIterator
                    .of(new Throwables.Supplier<ExceptionalIterator<T, SQLException>, SQLException>() {
                        private ExceptionalIterator<T, SQLException> internalIter;

                        @Override
                        public ExceptionalIterator<T, SQLException> get() throws SQLException {
                            if (internalIter == null) {
                                ResultSet rs = null;

                                try {
                                    rs = executeQuery();
                                    final ResultSet resultSet = rs;

                                    internalIter = new ExceptionalIterator<T, SQLException>() {
                                        private List<String> columnLabels = null;
                                        private boolean hasNext;

                                        @Override
                                        public boolean hasNext() throws SQLException {
                                            if (hasNext == false) {
                                                hasNext = resultSet.next();
                                            }

                                            return hasNext;
                                        }

                                        @Override
                                        public T next() throws SQLException {
                                            if (hasNext() == false) {
                                                throw new NoSuchElementException();
                                            }

                                            hasNext = false;

                                            if (columnLabels == null) {
                                                columnLabels = JdbcUtil.getColumnLabelList(resultSet);
                                            }

                                            return rowMapper.apply(resultSet, columnLabels);
                                        }

                                        @Override
                                        public void skip(long n) throws SQLException {
                                            N.checkArgNotNegative(n, "n");

                                            final long m = hasNext ? n - 1 : n;
                                            hasNext = false;

                                            JdbcUtil.skip(resultSet, m);
                                        }

                                        @Override
                                        public long count() throws SQLException {
                                            long cnt = hasNext ? 1 : 0;
                                            hasNext = false;

                                            while (resultSet.next()) {
                                                cnt++;
                                            }

                                            return cnt;
                                        }

                                        @Override
                                        public void close() throws SQLException {
                                            try {
                                                JdbcUtil.closeQuietly(resultSet);
                                            } finally {
                                                closeAfterExecutionIfAllowed();
                                            }
                                        }
                                    };
                                } finally {
                                    if (internalIter == null) {
                                        try {
                                            JdbcUtil.closeQuietly(rs);
                                        } finally {
                                            closeAfterExecutionIfAllowed();
                                        }
                                    }
                                }
                            }

                            return internalIter;
                        }
                    });

            return ExceptionalStream.newStream(lazyIter).onClose(new Throwables.Runnable<SQLException>() {
                @Override
                public void run() throws SQLException {
                    lazyIter.close();
                }
            });
        }

        /**
         * lazy-execution, lazy-fetch.
         *
         * @param <T>
         * @param rowFilter
         * @param rowMapper
         * @return
         * @throws SQLException
         */
        public <T> ExceptionalStream<T, SQLException> stream(final RowFilter rowFilter, final RowMapper<T> rowMapper) throws SQLException {
            checkArgNotNull(rowFilter, "rowFilter");
            checkArgNotNull(rowMapper, "rowMapper");
            assertNotClosed();

            final ExceptionalIterator<T, SQLException> lazyIter = ExceptionalIterator
                    .of(new Throwables.Supplier<ExceptionalIterator<T, SQLException>, SQLException>() {
                        private ExceptionalIterator<T, SQLException> internalIter;

                        @Override
                        public ExceptionalIterator<T, SQLException> get() throws SQLException {
                            if (internalIter == null) {
                                ResultSet rs = null;

                                try {
                                    rs = executeQuery();
                                    final ResultSet resultSet = rs;

                                    internalIter = new ExceptionalIterator<T, SQLException>() {
                                        private boolean hasNext;

                                        @Override
                                        public boolean hasNext() throws SQLException {
                                            if (hasNext == false) {
                                                while (resultSet.next()) {
                                                    if (rowFilter.test(resultSet)) {
                                                        hasNext = true;
                                                        break;
                                                    }
                                                }
                                            }

                                            return hasNext;
                                        }

                                        @Override
                                        public T next() throws SQLException {
                                            if (hasNext() == false) {
                                                throw new NoSuchElementException();
                                            }

                                            hasNext = false;

                                            return rowMapper.apply(resultSet);
                                        }

                                        @Override
                                        public void close() throws SQLException {
                                            try {
                                                JdbcUtil.closeQuietly(resultSet);
                                            } finally {
                                                closeAfterExecutionIfAllowed();
                                            }
                                        }
                                    };
                                } finally {
                                    if (internalIter == null) {
                                        try {
                                            JdbcUtil.closeQuietly(rs);
                                        } finally {
                                            closeAfterExecutionIfAllowed();
                                        }
                                    }
                                }
                            }

                            return internalIter;
                        }
                    });

            return ExceptionalStream.newStream(lazyIter).onClose(new Throwables.Runnable<SQLException>() {
                @Override
                public void run() throws SQLException {
                    lazyIter.close();
                }
            });
        }

        /**
         * lazy-execution, lazy-fetch.
         *
         * @param <T>
         * @param rowFilter
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        public <T> ExceptionalStream<T, SQLException> stream(final BiRowFilter rowFilter, final BiRowMapper<T> rowMapper) throws SQLException {
            checkArgNotNull(rowFilter, "rowFilter");
            checkArgNotNull(rowMapper, "rowMapper");
            assertNotClosed();

            final ExceptionalIterator<T, SQLException> lazyIter = ExceptionalIterator
                    .of(new Throwables.Supplier<ExceptionalIterator<T, SQLException>, SQLException>() {
                        private ExceptionalIterator<T, SQLException> internalIter;

                        @Override
                        public ExceptionalIterator<T, SQLException> get() throws SQLException {
                            if (internalIter == null) {
                                ResultSet rs = null;

                                try {
                                    rs = executeQuery();
                                    final ResultSet resultSet = rs;

                                    internalIter = new ExceptionalIterator<T, SQLException>() {
                                        private List<String> columnLabels = null;
                                        private boolean hasNext;

                                        @Override
                                        public boolean hasNext() throws SQLException {
                                            if (columnLabels == null) {
                                                columnLabels = JdbcUtil.getColumnLabelList(resultSet);
                                            }

                                            if (hasNext == false) {
                                                while (resultSet.next()) {
                                                    if (rowFilter.test(resultSet, columnLabels)) {
                                                        hasNext = true;
                                                        break;
                                                    }
                                                }
                                            }

                                            return hasNext;
                                        }

                                        @Override
                                        public T next() throws SQLException {
                                            if (hasNext() == false) {
                                                throw new NoSuchElementException();
                                            }

                                            hasNext = false;

                                            return rowMapper.apply(resultSet, columnLabels);
                                        }

                                        @Override
                                        public void close() throws SQLException {
                                            try {
                                                JdbcUtil.closeQuietly(resultSet);
                                            } finally {
                                                closeAfterExecutionIfAllowed();
                                            }
                                        }
                                    };
                                } finally {
                                    if (internalIter == null) {
                                        try {
                                            JdbcUtil.closeQuietly(rs);
                                        } finally {
                                            closeAfterExecutionIfAllowed();
                                        }
                                    }
                                }
                            }

                            return internalIter;
                        }
                    });

            return ExceptionalStream.newStream(lazyIter).onClose(new Throwables.Runnable<SQLException>() {
                @Override
                public void run() throws SQLException {
                    lazyIter.close();
                }
            });
        }

        /**
         * Note: using {@code select 1 from ...}, not {@code select count(*) from ...}.
         *
         * @return true, if successful
         * @throws SQLException the SQL exception
         */
        public boolean exists() throws SQLException {
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                return rs.next();
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         *
         * @param rowConsumer
         * @throws SQLException the SQL exception
         */
        public void ifExists(final RowConsumer rowConsumer) throws SQLException {
            checkArgNotNull(rowConsumer, "rowConsumer");
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                if (rs.next()) {
                    rowConsumer.accept(rs);
                }
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         *
         * @param rowConsumer
         * @throws SQLException the SQL exception
         */
        public void ifExists(final BiRowConsumer rowConsumer) throws SQLException {
            checkArgNotNull(rowConsumer, "rowConsumer");
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                if (rs.next()) {
                    rowConsumer.accept(rs, JdbcUtil.getColumnLabelList(rs));
                }
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         * If exists or else.
         *
         * @param rowConsumer
         * @param orElseAction
         * @throws SQLException the SQL exception
         */
        public void ifExistsOrElse(final RowConsumer rowConsumer, Throwables.Runnable<SQLException> orElseAction) throws SQLException {
            checkArgNotNull(rowConsumer, "rowConsumer");
            checkArgNotNull(orElseAction, "orElseAction");
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                if (rs.next()) {
                    rowConsumer.accept(rs);
                } else {
                    orElseAction.run();
                }
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         * If exists or else.
         *
         * @param rowConsumer
         * @param orElseAction
         * @throws SQLException the SQL exception
         */
        public void ifExistsOrElse(final BiRowConsumer rowConsumer, Throwables.Runnable<SQLException> orElseAction) throws SQLException {
            checkArgNotNull(rowConsumer, "rowConsumer");
            checkArgNotNull(orElseAction, "orElseAction");
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                if (rs.next()) {
                    rowConsumer.accept(rs, JdbcUtil.getColumnLabelList(rs));
                } else {
                    orElseAction.run();
                }
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         * Note: using {@code select count(*) from ...}
         *
         * @return
         * @throws SQLException the SQL exception
         * @deprecated may be misused and it's inefficient.
         */
        @Deprecated
        public int count() throws SQLException {
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                int cnt = 0;

                while (rs.next()) {
                    cnt++;
                }

                return cnt;
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         *
         * @param rowFilter
         * @return
         * @throws SQLException the SQL exception
         */
        public int count(final RowFilter rowFilter) throws SQLException {
            checkArgNotNull(rowFilter, "rowFilter");
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                int cnt = 0;

                while (rs.next()) {
                    if (rowFilter.test(rs)) {
                        cnt++;
                    }
                }

                return cnt;
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         *
         * @param rowFilter
         * @return
         * @throws SQLException the SQL exception
         */
        public int count(final BiRowFilter rowFilter) throws SQLException {
            checkArgNotNull(rowFilter, "rowFilter");
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);
                int cnt = 0;

                while (rs.next()) {
                    if (rowFilter.test(rs, columnLabels)) {
                        cnt++;
                    }
                }

                return cnt;
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         *
         * @param rowFilter
         * @return true, if successful
         * @throws SQLException the SQL exception
         */
        public boolean anyMatch(final RowFilter rowFilter) throws SQLException {
            checkArgNotNull(rowFilter, "rowFilter");
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                while (rs.next()) {
                    if (rowFilter.test(rs)) {
                        return true;
                    }
                }

                return false;
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         *
         * @param rowFilter
         * @return true, if successful
         * @throws SQLException the SQL exception
         */
        public boolean anyMatch(final BiRowFilter rowFilter) throws SQLException {
            checkArgNotNull(rowFilter, "rowFilter");
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

                while (rs.next()) {
                    if (rowFilter.test(rs, columnLabels)) {
                        return true;
                    }
                }

                return false;
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         *
         * @param rowFilter
         * @return true, if successful
         * @throws SQLException the SQL exception
         */
        public boolean allMatch(final RowFilter rowFilter) throws SQLException {
            checkArgNotNull(rowFilter, "rowFilter");
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                while (rs.next()) {
                    if (rowFilter.test(rs) == false) {
                        return false;
                    }
                }

                return true;
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         *
         * @param rowFilter
         * @return true, if successful
         * @throws SQLException the SQL exception
         */
        public boolean allMatch(final BiRowFilter rowFilter) throws SQLException {
            checkArgNotNull(rowFilter, "rowFilter");
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

                while (rs.next()) {
                    if (rowFilter.test(rs, columnLabels) == false) {
                        return false;
                    }
                }

                return true;
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         *
         * @param rowFilter
         * @return true, if successful
         * @throws SQLException the SQL exception
         */
        public boolean noneMatch(final RowFilter rowFilter) throws SQLException {
            return anyMatch(rowFilter) == false;
        }

        /**
         *
         * @param rowFilter
         * @return true, if successful
         * @throws SQLException the SQL exception
         */
        public boolean noneMatch(final BiRowFilter rowFilter) throws SQLException {
            return anyMatch(rowFilter) == false;
        }

        /**
         *
         * @param rowConsumer
         * @throws SQLException the SQL exception
         */
        public void forEach(final RowConsumer rowConsumer) throws SQLException {
            checkArgNotNull(rowConsumer, "rowConsumer");
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {

                while (rs.next()) {
                    rowConsumer.accept(rs);
                }

            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         *
         * @param rowFilter
         * @param rowConsumer
         * @throws SQLException the SQL exception
         */
        public void forEach(final RowFilter rowFilter, final RowConsumer rowConsumer) throws SQLException {
            checkArgNotNull(rowFilter, "rowFilter");
            checkArgNotNull(rowConsumer, "rowConsumer");
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {

                while (rs.next()) {
                    if (rowFilter.test(rs)) {
                        rowConsumer.accept(rs);
                    }
                }
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         *
         * @param rowConsumer
         * @throws SQLException the SQL exception
         */
        public void forEach(final BiRowConsumer rowConsumer) throws SQLException {
            checkArgNotNull(rowConsumer, "rowConsumer");
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

                while (rs.next()) {
                    rowConsumer.accept(rs, columnLabels);
                }

            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         *
         * @param rowFilter
         * @param rowConsumer
         * @throws SQLException the SQL exception
         */
        public void forEach(final BiRowFilter rowFilter, final BiRowConsumer rowConsumer) throws SQLException {
            checkArgNotNull(rowFilter, "rowFilter");
            checkArgNotNull(rowConsumer, "rowConsumer");
            assertNotClosed();

            try (ResultSet rs = executeQuery()) {
                final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

                while (rs.next()) {
                    if (rowFilter.test(rs, columnLabels)) {
                        rowConsumer.accept(rs, columnLabels);
                    }
                }

            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         *
         * @return
         * @throws SQLException the SQL exception
         */
        private ResultSet executeQuery() throws SQLException {
            if (!isFetchDirectionSet) {
                stmt.setFetchDirection(ResultSet.FETCH_FORWARD);
            }

            return JdbcUtil.executeQuery(stmt);
        }

        /**
         * Returns the generated key if it exists.
         *
         * @param <ID>
         * @return
         * @throws SQLException the SQL exception
         */
        public <ID> Optional<ID> insert() throws SQLException {
            return insert((RowMapper<ID>) SINGLE_GENERATED_KEY_EXTRACTOR);
        }

        /**
         *
         * @param <ID>
         * @param autoGeneratedKeyExtractor
         * @return
         * @throws SQLException the SQL exception
         */
        public <ID> Optional<ID> insert(final RowMapper<ID> autoGeneratedKeyExtractor) throws SQLException {
            assertNotClosed();

            try {
                JdbcUtil.executeUpdate(stmt);

                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    return rs.next() ? Optional.ofNullable(autoGeneratedKeyExtractor.apply(rs)) : Optional.<ID> empty();
                }
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         *
         * @param <ID>
         * @param autoGeneratedKeyExtractor
         * @return
         * @throws SQLException the SQL exception
         */
        public <ID> Optional<ID> insert(final BiRowMapper<ID> autoGeneratedKeyExtractor) throws SQLException {
            assertNotClosed();

            try {
                JdbcUtil.executeUpdate(stmt);

                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    if (rs.next()) {
                        final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

                        return Optional.ofNullable(autoGeneratedKeyExtractor.apply(rs, columnLabels));
                    } else {
                        return Optional.<ID> empty();
                    }
                }
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         * Returns the generated key if it exists.
         *
         * @param <ID>
         * @return
         * @throws SQLException the SQL exception
         */
        public <ID> List<ID> batchInsert() throws SQLException {
            return batchInsert((RowMapper<ID>) SINGLE_GENERATED_KEY_EXTRACTOR);
        }

        /**
         *
         * @param <ID>
         * @param autoGeneratedKeyExtractor
         * @return
         * @throws SQLException the SQL exception
         */
        public <ID> List<ID> batchInsert(final RowMapper<ID> autoGeneratedKeyExtractor) throws SQLException {
            assertNotClosed();

            try {
                executeBatch(stmt);

                List<ID> ids = new ArrayList<>();

                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    while (rs.next()) {
                        ids.add(autoGeneratedKeyExtractor.apply(rs));
                    }

                    if (N.notNullOrEmpty(ids) && Stream.of(ids).allMatch(Fn.isNull())) {
                        ids = new ArrayList<>();
                    }

                    return ids;
                }
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         *
         * @param <ID>
         * @param autoGeneratedKeyExtractor
         * @return
         * @throws SQLException the SQL exception
         */
        public <ID> List<ID> batchInsert(final BiRowMapper<ID> autoGeneratedKeyExtractor) throws SQLException {
            assertNotClosed();

            try {
                executeBatch(stmt);

                List<ID> ids = new ArrayList<>();

                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

                    while (rs.next()) {
                        ids.add(autoGeneratedKeyExtractor.apply(rs, columnLabels));
                    }

                    if (N.notNullOrEmpty(ids) && Stream.of(ids).allMatch(Fn.isNull())) {
                        ids = new ArrayList<>();
                    }

                    return ids;
                }
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         *
         * @return
         * @throws SQLException the SQL exception
         */
        public int update() throws SQLException {
            assertNotClosed();

            try {
                return JdbcUtil.executeUpdate(stmt);
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         *
         * @return
         * @throws SQLException the SQL exception
         */
        public int[] batchUpdate() throws SQLException {
            assertNotClosed();

            try {
                return executeBatch(stmt);
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         *
         * @return
         * @throws SQLException the SQL exception
         */
        public long largeUpate() throws SQLException {
            assertNotClosed();

            try {
                return stmt.executeLargeUpdate();
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         * Large batch update.
         *
         * @return
         * @throws SQLException the SQL exception
         */
        public long[] largeBatchUpdate() throws SQLException {
            assertNotClosed();

            try {
                return executeLargeBatch(stmt);
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         *
         * @return true, if successful
         * @throws SQLException the SQL exception
         */
        public boolean execute() throws SQLException {
            assertNotClosed();

            try {
                return JdbcUtil.execute(stmt);
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         * Execute then apply.
         *
         * @param <R>
         * @param getter
         * @return
         * @throws SQLException the SQL exception
         */
        public <R> R executeThenApply(final Throwables.Function<? super S, ? extends R, SQLException> getter) throws SQLException {
            checkArgNotNull(getter, "getter");
            assertNotClosed();

            try {
                JdbcUtil.execute(stmt);

                return getter.apply(stmt);
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         * Execute then apply.
         *
         * @param <R>
         * @param getter
         * @return
         * @throws SQLException the SQL exception
         */
        public <R> R executeThenApply(final Throwables.BiFunction<Boolean, ? super S, ? extends R, SQLException> getter) throws SQLException {
            checkArgNotNull(getter, "getter");
            assertNotClosed();

            try {
                final boolean isFirstResultSet = JdbcUtil.execute(stmt);

                return getter.apply(isFirstResultSet, stmt);
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         * Execute then accept.
         *
         * @param consumer
         * @throws SQLException the SQL exception
         */
        public void executeThenAccept(final Throwables.Consumer<? super S, SQLException> consumer) throws SQLException {
            checkArgNotNull(consumer, "consumer");
            assertNotClosed();

            try {
                JdbcUtil.execute(stmt);

                consumer.accept(stmt);
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         * Execute then accept.
         *
         * @param consumer
         * @throws SQLException the SQL exception
         */
        public void executeThenAccept(final Throwables.BiConsumer<Boolean, ? super S, SQLException> consumer) throws SQLException {
            checkArgNotNull(consumer, "consumer");
            assertNotClosed();

            try {
                final boolean isFirstResultSet = JdbcUtil.execute(stmt);

                consumer.accept(isFirstResultSet, stmt);
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }

        /**
         *
         * @param <R>
         * @param func
         * @return
         */
        @Beta
        public <R> ContinuableFuture<R> asyncApply(final Throwables.Function<Q, R, SQLException> func) {
            checkArgNotNull(func, "func");
            assertNotClosed();

            final Q q = (Q) this;

            return asyncExecutor.execute(() -> func.apply(q));
        }

        /**
         *
         * @param <R>
         * @param func
         * @param executor
         * @return
         */
        @Beta
        public <R> ContinuableFuture<R> asyncApply(final Throwables.Function<Q, R, SQLException> func, final Executor executor) {
            checkArgNotNull(func, "func");
            checkArgNotNull(executor, "executor");
            assertNotClosed();

            final Q q = (Q) this;

            return ContinuableFuture.call(() -> func.apply(q), executor);
        }

        /**
         *
         * @param action
         * @return
         */
        @Beta
        public ContinuableFuture<Void> asyncAccept(final Throwables.Consumer<Q, SQLException> action) {
            checkArgNotNull(action, "action");
            assertNotClosed();

            final Q q = (Q) this;

            return asyncExecutor.execute(() -> action.accept(q));
        }

        /**
         *
         * @param action
         * @param executor
         * @return
         */
        @Beta
        public ContinuableFuture<Void> asyncAccept(final Throwables.Consumer<Q, SQLException> action, final Executor executor) {
            checkArgNotNull(action, "action");
            checkArgNotNull(executor, "executor");
            assertNotClosed();

            final Q q = (Q) this;

            return ContinuableFuture.run(() -> action.accept(q), executor);
        }

        /**
         * Check arg not null.
         *
         * @param arg
         * @param argName
         */
        protected void checkArgNotNull(Object arg, String argName) {
            if (arg == null) {
                try {
                    close();
                } catch (Exception e) {
                    logger.error("Failed to close PreparedQuery", e);
                }

                throw new IllegalArgumentException("'" + argName + "' can't be null");
            }
        }

        /**
         *
         * @param b
         * @param errorMsg
         */
        protected void checkArg(boolean b, String errorMsg) {
            if (b == false) {
                try {
                    close();
                } catch (Exception e) {
                    logger.error("Failed to close PreparedQuery", e);
                }

                throw new IllegalArgumentException(errorMsg);
            }
        }

        protected <R> R checkNotResultSet(R result) {
            if (result instanceof ResultSet) {
                throw new UnsupportedOperationException("The result value of ResultExtractor/BiResultExtractor.apply can't be ResultSet");
            }

            return result;
        }

        /**
         * Close.
         */
        @Override
        public void close() {
            if (isClosed) {
                return;
            }

            isClosed = true;

            if (closeHandler == null) {
                JdbcUtil.closeQuietly(stmt);
            } else {
                try {
                    JdbcUtil.closeQuietly(stmt);
                } finally {
                    closeHandler.run();
                }
            }
        }

        /**
         * Close after execution if allowed.
         *
         * @throws SQLException the SQL exception
         */
        void closeAfterExecutionIfAllowed() throws SQLException {
            if (isCloseAfterExecution) {
                close();
            }
        }

        /**
         * Assert not closed.
         */
        void assertNotClosed() {
            if (isClosed) {
                throw new IllegalStateException();
            }
        }
    }

    /**
     * The backed {@code PreparedStatement/CallableStatement} will be closed by default
     * after any execution methods(which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/list/execute/...).
     * except the {@code 'closeAfterExecution'} flag is set to {@code false} by calling {@code #closeAfterExecution(false)}.
     *
     * <br />
     * Generally, don't cache or reuse the instance of this class,
     * except the {@code 'closeAfterExecution'} flag is set to {@code false} by calling {@code #closeAfterExecution(false)}.
     *
     * <br />
     * The {@code ResultSet} returned by query will always be closed after execution, even {@code 'closeAfterExecution'} flag is set to {@code false}.
     *
     * <br />
     * Remember: parameter/column index in {@code PreparedStatement/ResultSet} starts from 1, not 0.
     *
     * @author haiyangl
     *
     * @see {@link com.landawn.abacus.annotation.ReadOnly}
     * @see {@link com.landawn.abacus.annotation.ReadOnlyId}
     * @see {@link com.landawn.abacus.annotation.NonUpdatable}
     * @see {@link com.landawn.abacus.annotation.Transient}
     * @see {@link com.landawn.abacus.annotation.Table}
     * @see {@link com.landawn.abacus.annotation.Column}
     *
     * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/Connection.html">http://docs.oracle.com/javase/8/docs/api/java/sql/Connection.html</a>
     * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/Statement.html">http://docs.oracle.com/javase/8/docs/api/java/sql/Statement.html</a>
     * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/PreparedStatement.html">http://docs.oracle.com/javase/8/docs/api/java/sql/PreparedStatement.html</a>
     * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/ResultSet.html">http://docs.oracle.com/javase/8/docs/api/java/sql/ResultSet.html</a>
     */
    public static class PreparedQuery extends AbstractPreparedQuery<PreparedStatement, PreparedQuery> {

        /**
         * Instantiates a new prepared query.
         *
         * @param stmt
         */
        PreparedQuery(PreparedStatement stmt) {
            super(stmt);
        }
    }

    /**
     * The backed {@code PreparedStatement/CallableStatement} will be closed by default
     * after any execution methods(which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/list/execute/...).
     * except the {@code 'closeAfterExecution'} flag is set to {@code false} by calling {@code #closeAfterExecution(false)}.
     *
     * <br />
     * Generally, don't cache or reuse the instance of this class,
     * except the {@code 'closeAfterExecution'} flag is set to {@code false} by calling {@code #closeAfterExecution(false)}.
     *
     * <br />
     * The {@code ResultSet} returned by query will always be closed after execution, even {@code 'closeAfterExecution'} flag is set to {@code false}.
     *
     * <br />
     * Remember: parameter/column index in {@code PreparedStatement/ResultSet} starts from 1, not 0.
     *
     * @author haiyangl
     *
     * @see {@link com.landawn.abacus.annotation.ReadOnly}
     * @see {@link com.landawn.abacus.annotation.ReadOnlyId}
     * @see {@link com.landawn.abacus.annotation.NonUpdatable}
     * @see {@link com.landawn.abacus.annotation.Transient}
     * @see {@link com.landawn.abacus.annotation.Table}
     * @see {@link com.landawn.abacus.annotation.Column}
     *
     * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/Connection.html">http://docs.oracle.com/javase/8/docs/api/java/sql/Connection.html</a>
     * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/Statement.html">http://docs.oracle.com/javase/8/docs/api/java/sql/Statement.html</a>
     * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/PreparedStatement.html">http://docs.oracle.com/javase/8/docs/api/java/sql/PreparedStatement.html</a>
     * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/ResultSet.html">http://docs.oracle.com/javase/8/docs/api/java/sql/ResultSet.html</a>
     */
    public static class PreparedCallableQuery extends AbstractPreparedQuery<CallableStatement, PreparedCallableQuery> {

        /** The stmt. */
        final CallableStatement stmt;

        /**
         * Instantiates a new prepared callable query.
         *
         * @param stmt
         */
        PreparedCallableQuery(CallableStatement stmt) {
            super(stmt);
            this.stmt = stmt;
        }

        /**
         * Sets the null.
         *
         * @param parameterName
         * @param sqlType
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setNull(String parameterName, int sqlType) throws SQLException {
            stmt.setNull(parameterName, sqlType);

            return this;
        }

        /**
         * Sets the null.
         *
         * @param parameterName
         * @param sqlType
         * @param typeName
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setNull(String parameterName, int sqlType, String typeName) throws SQLException {
            stmt.setNull(parameterName, sqlType, typeName);

            return this;
        }

        /**
         * Sets the boolean.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setBoolean(String parameterName, boolean x) throws SQLException {
            stmt.setBoolean(parameterName, x);

            return this;
        }

        /**
         * Sets the boolean.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setBoolean(String parameterName, Boolean x) throws SQLException {
            stmt.setBoolean(parameterName, N.defaultIfNull(x));

            return this;
        }

        /**
         * Sets the byte.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setByte(String parameterName, byte x) throws SQLException {
            stmt.setByte(parameterName, x);

            return this;
        }

        /**
         * Sets the byte.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setByte(String parameterName, Byte x) throws SQLException {
            stmt.setByte(parameterName, N.defaultIfNull(x));

            return this;
        }

        /**
         * Sets the short.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setShort(String parameterName, short x) throws SQLException {
            stmt.setShort(parameterName, x);

            return this;
        }

        /**
         * Sets the short.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setShort(String parameterName, Short x) throws SQLException {
            stmt.setShort(parameterName, N.defaultIfNull(x));

            return this;
        }

        /**
         * Sets the int.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setInt(String parameterName, int x) throws SQLException {
            stmt.setInt(parameterName, x);

            return this;
        }

        /**
         * Sets the int.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setInt(String parameterName, Integer x) throws SQLException {
            stmt.setInt(parameterName, N.defaultIfNull(x));

            return this;
        }

        /**
         * Sets the long.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setLong(String parameterName, long x) throws SQLException {
            stmt.setLong(parameterName, x);

            return this;
        }

        /**
         * Sets the long.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setLong(String parameterName, Long x) throws SQLException {
            stmt.setLong(parameterName, N.defaultIfNull(x));

            return this;
        }

        /**
         * Sets the float.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setFloat(String parameterName, float x) throws SQLException {
            stmt.setFloat(parameterName, x);

            return this;
        }

        /**
         * Sets the float.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setFloat(String parameterName, Float x) throws SQLException {
            stmt.setFloat(parameterName, N.defaultIfNull(x));

            return this;
        }

        /**
         * Sets the double.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setDouble(String parameterName, double x) throws SQLException {
            stmt.setDouble(parameterName, x);

            return this;
        }

        /**
         * Sets the double.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setDouble(String parameterName, Double x) throws SQLException {
            stmt.setDouble(parameterName, N.defaultIfNull(x));

            return this;
        }

        /**
         * Sets the big decimal.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setBigDecimal(String parameterName, BigDecimal x) throws SQLException {
            stmt.setBigDecimal(parameterName, x);

            return this;
        }

        /**
         * Sets the string.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setString(String parameterName, String x) throws SQLException {
            stmt.setString(parameterName, x);

            return this;
        }

        /**
         * Sets the date.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setDate(String parameterName, java.sql.Date x) throws SQLException {
            stmt.setDate(parameterName, x);

            return this;
        }

        /**
         * Sets the date.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setDate(String parameterName, java.util.Date x) throws SQLException {
            stmt.setDate(parameterName, x == null ? null : x instanceof java.sql.Date ? (java.sql.Date) x : new java.sql.Date(x.getTime()));

            return this;
        }

        /**
         * Sets the time.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setTime(String parameterName, java.sql.Time x) throws SQLException {
            stmt.setTime(parameterName, x);

            return this;
        }

        /**
         * Sets the time.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setTime(String parameterName, java.util.Date x) throws SQLException {
            stmt.setTime(parameterName, x == null ? null : x instanceof java.sql.Time ? (java.sql.Time) x : new java.sql.Time(x.getTime()));

            return this;
        }

        /**
         * Sets the timestamp.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setTimestamp(String parameterName, java.sql.Timestamp x) throws SQLException {
            stmt.setTimestamp(parameterName, x);

            return this;
        }

        /**
         * Sets the timestamp.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setTimestamp(String parameterName, java.util.Date x) throws SQLException {
            stmt.setTimestamp(parameterName, x == null ? null : x instanceof java.sql.Timestamp ? (java.sql.Timestamp) x : new java.sql.Timestamp(x.getTime()));

            return this;
        }

        /**
         * Sets the bytes.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setBytes(String parameterName, byte[] x) throws SQLException {
            stmt.setBytes(parameterName, x);

            return this;
        }

        /**
         * Sets the ascii stream.
         *
         * @param parameterName
         * @param inputStream
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setAsciiStream(String parameterName, InputStream inputStream) throws SQLException {
            stmt.setAsciiStream(parameterName, inputStream);

            return this;
        }

        /**
         * Sets the ascii stream.
         *
         * @param parameterName
         * @param inputStream
         * @param length
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setAsciiStream(String parameterName, InputStream inputStream, long length) throws SQLException {
            stmt.setAsciiStream(parameterName, inputStream, length);

            return this;
        }

        /**
         * Sets the binary stream.
         *
         * @param parameterName
         * @param inputStream
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setBinaryStream(String parameterName, InputStream inputStream) throws SQLException {
            stmt.setBinaryStream(parameterName, inputStream);

            return this;
        }

        /**
         * Sets the binary stream.
         *
         * @param parameterName
         * @param inputStream
         * @param length
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setBinaryStream(String parameterName, InputStream inputStream, long length) throws SQLException {
            stmt.setBinaryStream(parameterName, inputStream, length);

            return this;
        }

        /**
         * Sets the character stream.
         *
         * @param parameterName
         * @param reader
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setCharacterStream(String parameterName, Reader reader) throws SQLException {
            stmt.setCharacterStream(parameterName, reader);

            return this;
        }

        /**
         * Sets the character stream.
         *
         * @param parameterName
         * @param reader
         * @param length
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setCharacterStream(String parameterName, Reader reader, long length) throws SQLException {
            stmt.setCharacterStream(parameterName, reader, length);

            return this;
        }

        /**
         * Sets the N character stream.
         *
         * @param parameterName
         * @param reader
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setNCharacterStream(String parameterName, Reader reader) throws SQLException {
            stmt.setNCharacterStream(parameterName, reader);

            return this;
        }

        /**
         * Sets the N character stream.
         *
         * @param parameterName
         * @param reader
         * @param length
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setNCharacterStream(String parameterName, Reader reader, long length) throws SQLException {
            stmt.setNCharacterStream(parameterName, reader, length);

            return this;
        }

        /**
         * Sets the blob.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setBlob(String parameterName, java.sql.Blob x) throws SQLException {
            stmt.setBlob(parameterName, x);

            return this;
        }

        /**
         * Sets the blob.
         *
         * @param parameterName
         * @param inputStream
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setBlob(String parameterName, InputStream inputStream) throws SQLException {
            stmt.setBlob(parameterName, inputStream);

            return this;
        }

        /**
         * Sets the blob.
         *
         * @param parameterName
         * @param inputStream
         * @param length
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setBlob(String parameterName, InputStream inputStream, long length) throws SQLException {
            stmt.setBlob(parameterName, inputStream, length);

            return this;
        }

        /**
         * Sets the clob.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setClob(String parameterName, java.sql.Clob x) throws SQLException {
            stmt.setClob(parameterName, x);

            return this;
        }

        /**
         * Sets the clob.
         *
         * @param parameterName
         * @param reader
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setClob(String parameterName, Reader reader) throws SQLException {
            stmt.setClob(parameterName, reader);

            return this;
        }

        /**
         * Sets the clob.
         *
         * @param parameterName
         * @param reader
         * @param length
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setClob(String parameterName, Reader reader, long length) throws SQLException {
            stmt.setClob(parameterName, reader, length);

            return this;
        }

        /**
         * Sets the N clob.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setNClob(String parameterName, java.sql.NClob x) throws SQLException {
            stmt.setNClob(parameterName, x);

            return this;
        }

        /**
         * Sets the N clob.
         *
         * @param parameterName
         * @param reader
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setNClob(String parameterName, Reader reader) throws SQLException {
            stmt.setNClob(parameterName, reader);

            return this;
        }

        /**
         * Sets the N clob.
         *
         * @param parameterName
         * @param reader
         * @param length
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setNClob(String parameterName, Reader reader, long length) throws SQLException {
            stmt.setNClob(parameterName, reader, length);

            return this;
        }

        /**
         *
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setURL(String parameterName, URL x) throws SQLException {
            stmt.setURL(parameterName, x);

            return this;
        }

        /**
         *
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setSQLXML(String parameterName, java.sql.SQLXML x) throws SQLException {
            stmt.setSQLXML(parameterName, x);

            return this;
        }

        /**
         *
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setRowId(String parameterName, java.sql.RowId x) throws SQLException {
            stmt.setRowId(parameterName, x);

            return this;
        }

        /**
         * Sets the object.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setObject(String parameterName, Object x) throws SQLException {
            if (x == null) {
                stmt.setObject(parameterName, x);
            } else {
                N.typeOf(x.getClass()).set(stmt, parameterName, x);
            }

            return this;
        }

        /**
         * Sets the object.
         *
         * @param parameterName
         * @param x
         * @param sqlType
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setObject(String parameterName, Object x, int sqlType) throws SQLException {
            stmt.setObject(parameterName, x, sqlType);

            return this;
        }

        /**
         * Sets the object.
         *
         * @param parameterName
         * @param x
         * @param sqlType
         * @param scaleOrLength
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setObject(String parameterName, Object x, int sqlType, int scaleOrLength) throws SQLException {
            stmt.setObject(parameterName, x, sqlType, scaleOrLength);

            return this;
        }

        /**
         * Sets the parameters.
         *
         * @param parameters
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery setParameters(Map<String, Object> parameters) throws SQLException {
            checkArgNotNull(parameters, "parameters");

            for (Map.Entry<String, Object> entry : parameters.entrySet()) {
                setObject(entry.getKey(), entry.getValue());
            }

            return this;
        }

        /**
         * Sets the parameters.
         *
         * @param parameterNames
         * @param entity
         * @return
         * @throws SQLException the SQL exception
         * @see {@link ClassUtil#getPropNameList(Class)}
         * @see {@link ClassUtil#getPropNameListExclusively(Class, Set)}
         * @see {@link ClassUtil#getPropNameListExclusively(Class, Collection)}
         * @see {@link JdbcUtil#getNamedParameters(String)}
         */
        public PreparedCallableQuery setParameters(List<String> parameterNames, Object entity) throws SQLException {
            checkArgNotNull(parameterNames, "parameterNames");
            checkArgNotNull(entity, "entity");

            final Class<?> cls = entity.getClass();
            final EntityInfo entityInfo = ParserUtil.getEntityInfo(cls);
            PropInfo propInfo = null;

            for (String parameterName : parameterNames) {
                propInfo = entityInfo.getPropInfo(parameterName);
                propInfo.dbType.set(stmt, parameterName, propInfo.getPropValue(entity));
            }

            return this;
        }

        /**
         * Register out parameter.
         *
         * @param parameterIndex starts from 1, not 0.
         * @param sqlType
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery registerOutParameter(int parameterIndex, int sqlType) throws SQLException {
            stmt.registerOutParameter(parameterIndex, sqlType);

            return this;
        }

        /**
         * Register out parameter.
         *
         * @param parameterIndex starts from 1, not 0.
         * @param sqlType
         * @param scale
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery registerOutParameter(int parameterIndex, int sqlType, int scale) throws SQLException {
            stmt.registerOutParameter(parameterIndex, sqlType, scale);

            return this;
        }

        /**
         * Register out parameter.
         *
         * @param parameterIndex starts from 1, not 0.
         * @param sqlType
         * @param typeName
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery registerOutParameter(int parameterIndex, int sqlType, String typeName) throws SQLException {
            stmt.registerOutParameter(parameterIndex, sqlType, typeName);

            return this;
        }

        /**
         * Register out parameter.
         *
         * @param parameterName
         * @param sqlType
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery registerOutParameter(String parameterName, int sqlType) throws SQLException {
            stmt.registerOutParameter(parameterName, sqlType);

            return this;
        }

        /**
         * Register out parameter.
         *
         * @param parameterName
         * @param sqlType
         * @param scale
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery registerOutParameter(String parameterName, int sqlType, int scale) throws SQLException {
            stmt.registerOutParameter(parameterName, sqlType, scale);

            return this;
        }

        /**
         * Register out parameter.
         *
         * @param parameterName
         * @param sqlType
         * @param typeName
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery registerOutParameter(String parameterName, int sqlType, String typeName) throws SQLException {
            stmt.registerOutParameter(parameterName, sqlType, typeName);

            return this;
        }

        /**
         * Register out parameter.
         *
         * @param parameterIndex starts from 1, not 0.
         * @param sqlType
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery registerOutParameter(int parameterIndex, SQLType sqlType) throws SQLException {
            stmt.registerOutParameter(parameterIndex, sqlType);

            return this;
        }

        /**
         * Register out parameter.
         *
         * @param parameterIndex starts from 1, not 0.
         * @param sqlType
         * @param scale
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery registerOutParameter(int parameterIndex, SQLType sqlType, int scale) throws SQLException {
            stmt.registerOutParameter(parameterIndex, sqlType, scale);

            return this;
        }

        /**
         * Register out parameter.
         *
         * @param parameterIndex starts from 1, not 0.
         * @param sqlType
         * @param typeName
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery registerOutParameter(int parameterIndex, SQLType sqlType, String typeName) throws SQLException {
            stmt.registerOutParameter(parameterIndex, sqlType, typeName);

            return this;
        }

        /**
         * Register out parameter.
         *
         * @param parameterName
         * @param sqlType
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery registerOutParameter(String parameterName, SQLType sqlType) throws SQLException {
            stmt.registerOutParameter(parameterName, sqlType);

            return this;
        }

        /**
         * Register out parameter.
         *
         * @param parameterName
         * @param sqlType
         * @param scale
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery registerOutParameter(String parameterName, SQLType sqlType, int scale) throws SQLException {
            stmt.registerOutParameter(parameterName, sqlType, scale);

            return this;
        }

        /**
         * Register out parameter.
         *
         * @param parameterName
         * @param sqlType
         * @param typeName
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery registerOutParameter(String parameterName, SQLType sqlType, String typeName) throws SQLException {
            stmt.registerOutParameter(parameterName, sqlType, typeName);

            return this;
        }

        /**
         * Register out parameters.
         *
         * @param register
         * @return
         * @throws SQLException the SQL exception
         */
        public PreparedCallableQuery registerOutParameters(final ParametersSetter<? super CallableStatement> register) throws SQLException {
            checkArgNotNull(register, "register");

            boolean noException = false;

            try {
                register.accept(stmt);

                noException = true;
            } finally {
                if (noException == false) {
                    close();
                }
            }

            return this;
        }

        /**
         * Register out parameters.
         *
         * @param <T>
         * @param parameter
         * @param register
         * @return
         * @throws SQLException the SQL exception
         */
        public <T> PreparedCallableQuery registerOutParameters(final T parameter, final BiParametersSetter<? super T, ? super CallableStatement> register)
                throws SQLException {
            checkArgNotNull(register, "register");

            boolean noException = false;

            try {
                register.accept(parameter, stmt);

                noException = true;
            } finally {
                if (noException == false) {
                    close();
                }
            }

            return this;
        }

        /**
         *
         * @param <R1>
         * @param resultExtrator1
         * @return
         * @throws SQLException the SQL exception
         */
        public <R1> Optional<R1> call(final ResultExtractor<R1> resultExtrator1) throws SQLException {
            checkArgNotNull(resultExtrator1, "resultExtrator1");
            assertNotClosed();

            try {
                if (JdbcUtil.execute(stmt)) {
                    if (stmt.getUpdateCount() == -1) {
                        try (ResultSet rs = stmt.getResultSet()) {
                            return Optional.of(checkNotResultSet(resultExtrator1.apply(rs)));
                        }
                    }
                }
            } finally {
                closeAfterExecutionIfAllowed();
            }

            return Optional.empty();
        }

        /**
         *
         * @param <R1>
         * @param <R2>
         * @param resultExtrator1
         * @param resultExtrator2
         * @return
         * @throws SQLException the SQL exception
         */
        public <R1, R2> Tuple2<Optional<R1>, Optional<R2>> call(final ResultExtractor<R1> resultExtrator1, final ResultExtractor<R2> resultExtrator2)
                throws SQLException {
            checkArgNotNull(resultExtrator1, "resultExtrator1");
            checkArgNotNull(resultExtrator2, "resultExtrator2");
            assertNotClosed();

            Optional<R1> result1 = Optional.empty();
            Optional<R2> result2 = Optional.empty();

            try {
                if (JdbcUtil.execute(stmt)) {
                    if (stmt.getUpdateCount() == -1) {
                        try (ResultSet rs = stmt.getResultSet()) {
                            result1 = Optional.of(checkNotResultSet(resultExtrator1.apply(rs)));
                        }
                    }

                    if (stmt.getMoreResults() && stmt.getUpdateCount() == -1) {
                        try (ResultSet rs = stmt.getResultSet()) {
                            result2 = Optional.of(checkNotResultSet(resultExtrator2.apply(rs)));
                        }
                    }
                }
            } finally {
                closeAfterExecutionIfAllowed();
            }

            return Tuple.of(result1, result2);
        }

        /**
         *
         * @param <R1>
         * @param <R2>
         * @param <R3>
         * @param resultExtrator1
         * @param resultExtrator2
         * @param resultExtrator3
         * @return
         * @throws SQLException the SQL exception
         */
        public <R1, R2, R3> Tuple3<Optional<R1>, Optional<R2>, Optional<R3>> call(final ResultExtractor<R1> resultExtrator1,
                final ResultExtractor<R2> resultExtrator2, final ResultExtractor<R3> resultExtrator3) throws SQLException {
            checkArgNotNull(resultExtrator1, "resultExtrator1");
            checkArgNotNull(resultExtrator2, "resultExtrator2");
            checkArgNotNull(resultExtrator3, "resultExtrator3");
            assertNotClosed();

            Optional<R1> result1 = Optional.empty();
            Optional<R2> result2 = Optional.empty();
            Optional<R3> result3 = Optional.empty();

            try {
                if (JdbcUtil.execute(stmt)) {
                    if (stmt.getUpdateCount() == -1) {
                        try (ResultSet rs = stmt.getResultSet()) {
                            result1 = Optional.of(checkNotResultSet(resultExtrator1.apply(rs)));
                        }
                    }

                    if (stmt.getMoreResults() && stmt.getUpdateCount() == -1) {
                        try (ResultSet rs = stmt.getResultSet()) {
                            result2 = Optional.of(checkNotResultSet(resultExtrator2.apply(rs)));
                        }
                    }

                    if (stmt.getMoreResults() && stmt.getUpdateCount() == -1) {
                        try (ResultSet rs = stmt.getResultSet()) {
                            result3 = Optional.of(checkNotResultSet(resultExtrator3.apply(rs)));
                        }
                    }
                }
            } finally {
                closeAfterExecutionIfAllowed();
            }

            return Tuple.of(result1, result2, result3);
        }

        /**
         *
         * @param <R1>
         * @param <R2>
         * @param <R3>
         * @param <R4>
         * @param resultExtrator1
         * @param resultExtrator2
         * @param resultExtrator3
         * @param resultExtrator4
         * @return
         * @throws SQLException the SQL exception
         */
        public <R1, R2, R3, R4> Tuple4<Optional<R1>, Optional<R2>, Optional<R3>, Optional<R4>> call(final ResultExtractor<R1> resultExtrator1,
                final ResultExtractor<R2> resultExtrator2, final ResultExtractor<R3> resultExtrator3, final ResultExtractor<R4> resultExtrator4)
                throws SQLException {
            checkArgNotNull(resultExtrator1, "resultExtrator1");
            checkArgNotNull(resultExtrator2, "resultExtrator2");
            checkArgNotNull(resultExtrator3, "resultExtrator3");
            checkArgNotNull(resultExtrator4, "resultExtrator4");
            assertNotClosed();

            Optional<R1> result1 = Optional.empty();
            Optional<R2> result2 = Optional.empty();
            Optional<R3> result3 = Optional.empty();
            Optional<R4> result4 = Optional.empty();

            try {
                if (JdbcUtil.execute(stmt)) {
                    if (stmt.getUpdateCount() == -1) {
                        try (ResultSet rs = stmt.getResultSet()) {
                            result1 = Optional.of(checkNotResultSet(resultExtrator1.apply(rs)));
                        }
                    }

                    if (stmt.getMoreResults() && stmt.getUpdateCount() == -1) {
                        try (ResultSet rs = stmt.getResultSet()) {
                            result2 = Optional.of(checkNotResultSet(resultExtrator2.apply(rs)));
                        }
                    }

                    if (stmt.getMoreResults() && stmt.getUpdateCount() == -1) {
                        try (ResultSet rs = stmt.getResultSet()) {
                            result3 = Optional.of(checkNotResultSet(resultExtrator3.apply(rs)));
                        }
                    }

                    if (stmt.getMoreResults() && stmt.getUpdateCount() == -1) {
                        try (ResultSet rs = stmt.getResultSet()) {
                            result4 = Optional.of(checkNotResultSet(resultExtrator4.apply(rs)));
                        }
                    }
                }
            } finally {
                closeAfterExecutionIfAllowed();
            }

            return Tuple.of(result1, result2, result3, result4);
        }

        /**
         *
         * @param <R1>
         * @param <R2>
         * @param <R3>
         * @param <R4>
         * @param <R5>
         * @param resultExtrator1
         * @param resultExtrator2
         * @param resultExtrator3
         * @param resultExtrator4
         * @param resultExtrator5
         * @return
         * @throws SQLException the SQL exception
         */
        public <R1, R2, R3, R4, R5> Tuple5<Optional<R1>, Optional<R2>, Optional<R3>, Optional<R4>, Optional<R5>> call(final ResultExtractor<R1> resultExtrator1,
                final ResultExtractor<R2> resultExtrator2, final ResultExtractor<R3> resultExtrator3, final ResultExtractor<R4> resultExtrator4,
                final ResultExtractor<R5> resultExtrator5) throws SQLException {
            checkArgNotNull(resultExtrator1, "resultExtrator1");
            checkArgNotNull(resultExtrator2, "resultExtrator2");
            checkArgNotNull(resultExtrator3, "resultExtrator3");
            checkArgNotNull(resultExtrator4, "resultExtrator4");
            checkArgNotNull(resultExtrator5, "resultExtrator5");
            assertNotClosed();

            Optional<R1> result1 = Optional.empty();
            Optional<R2> result2 = Optional.empty();
            Optional<R3> result3 = Optional.empty();
            Optional<R4> result4 = Optional.empty();
            Optional<R5> result5 = Optional.empty();

            try {
                if (JdbcUtil.execute(stmt)) {
                    if (stmt.getUpdateCount() == -1) {
                        try (ResultSet rs = stmt.getResultSet()) {
                            result1 = Optional.of(checkNotResultSet(resultExtrator1.apply(rs)));
                        }
                    }

                    if (stmt.getMoreResults() && stmt.getUpdateCount() == -1) {
                        try (ResultSet rs = stmt.getResultSet()) {
                            result2 = Optional.of(checkNotResultSet(resultExtrator2.apply(rs)));
                        }
                    }

                    if (stmt.getMoreResults() && stmt.getUpdateCount() == -1) {
                        try (ResultSet rs = stmt.getResultSet()) {
                            result3 = Optional.of(checkNotResultSet(resultExtrator3.apply(rs)));
                        }
                    }

                    if (stmt.getMoreResults() && stmt.getUpdateCount() == -1) {
                        try (ResultSet rs = stmt.getResultSet()) {
                            result4 = Optional.of(checkNotResultSet(resultExtrator4.apply(rs)));
                        }
                    }

                    if (stmt.getMoreResults() && stmt.getUpdateCount() == -1) {
                        try (ResultSet rs = stmt.getResultSet()) {
                            result5 = Optional.of(checkNotResultSet(resultExtrator5.apply(rs)));
                        }
                    }
                }
            } finally {
                closeAfterExecutionIfAllowed();
            }

            return Tuple.of(result1, result2, result3, result4, result5);
        }

    }

    /**
     * The backed {@code PreparedStatement/CallableStatement} will be closed by default
     * after any execution methods(which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/list/execute/...).
     * except the {@code 'closeAfterExecution'} flag is set to {@code false} by calling {@code #closeAfterExecution(false)}.
     *
     * <br />
     * Generally, don't cache or reuse the instance of this class,
     * except the {@code 'closeAfterExecution'} flag is set to {@code false} by calling {@code #closeAfterExecution(false)}.
     *
     * <br />
     * The {@code ResultSet} returned by query will always be closed after execution, even {@code 'closeAfterExecution'} flag is set to {@code false}.
     *
     * <br />
     * Remember: parameter/column index in {@code PreparedStatement/ResultSet} starts from 1, not 0.
     *
     * @author haiyangl
     *
     * @see {@link com.landawn.abacus.annotation.ReadOnly}
     * @see {@link com.landawn.abacus.annotation.ReadOnlyId}
     * @see {@link com.landawn.abacus.annotation.NonUpdatable}
     * @see {@link com.landawn.abacus.annotation.Transient}
     * @see {@link com.landawn.abacus.annotation.Table}
     * @see {@link com.landawn.abacus.annotation.Column}
     *
     * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/Connection.html">http://docs.oracle.com/javase/8/docs/api/java/sql/Connection.html</a>
     * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/Statement.html">http://docs.oracle.com/javase/8/docs/api/java/sql/Statement.html</a>
     * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/PreparedStatement.html">http://docs.oracle.com/javase/8/docs/api/java/sql/PreparedStatement.html</a>
     * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/ResultSet.html">http://docs.oracle.com/javase/8/docs/api/java/sql/ResultSet.html</a>
     */
    public static class NamedQuery extends AbstractPreparedQuery<PreparedStatement, NamedQuery> {

        /** The named SQL. */
        private final NamedSQL namedSQL;

        /** The parameter names. */
        private final List<String> parameterNames;

        /** The parameter count. */
        private final int parameterCount;

        /** The param name index map. */
        private Map<String, IntList> paramNameIndexMap;

        /**
         * Instantiates a new named query.
         *
         * @param stmt
         * @param namedSQL
         */
        NamedQuery(final PreparedStatement stmt, final NamedSQL namedSQL) {
            super(stmt);
            this.namedSQL = namedSQL;
            this.parameterNames = namedSQL.getNamedParameters();
            this.parameterCount = namedSQL.getParameterCount();
        }

        /**
         * Inits the param name index map.
         */
        private void initParamNameIndexMap() {
            paramNameIndexMap = new HashMap<>(parameterCount);
            int index = 1;

            for (String paramName : parameterNames) {
                IntList indexes = paramNameIndexMap.get(paramName);

                if (indexes == null) {
                    indexes = new IntList(1);
                    paramNameIndexMap.put(paramName, indexes);
                }

                indexes.add(index++);
            }
        }

        /**
         * Sets the null.
         *
         * @param parameterName
         * @param sqlType
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setNull(String parameterName, int sqlType) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setNull(i + 1, sqlType);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setNull(indexes.get(0), sqlType);
                    } else if (indexes.size() == 2) {
                        setNull(indexes.get(0), sqlType);
                        setNull(indexes.get(1), sqlType);
                    } else if (indexes.size() == 3) {
                        setNull(indexes.get(0), sqlType);
                        setNull(indexes.get(1), sqlType);
                        setNull(indexes.get(2), sqlType);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setNull(indexes.get(i), sqlType);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the null.
         *
         * @param parameterName
         * @param sqlType
         * @param typeName
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setNull(String parameterName, int sqlType, String typeName) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setNull(i + 1, sqlType, typeName);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setNull(indexes.get(0), sqlType, typeName);
                    } else if (indexes.size() == 2) {
                        setNull(indexes.get(0), sqlType, typeName);
                        setNull(indexes.get(1), sqlType, typeName);
                    } else if (indexes.size() == 3) {
                        setNull(indexes.get(0), sqlType, typeName);
                        setNull(indexes.get(1), sqlType, typeName);
                        setNull(indexes.get(2), sqlType, typeName);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setNull(indexes.get(i), sqlType, typeName);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the boolean.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setBoolean(String parameterName, boolean x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setBoolean(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setBoolean(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setBoolean(indexes.get(0), x);
                        setBoolean(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setBoolean(indexes.get(0), x);
                        setBoolean(indexes.get(1), x);
                        setBoolean(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setBoolean(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the boolean.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setBoolean(String parameterName, Boolean x) throws SQLException {
            setBoolean(parameterName, N.defaultIfNull(x));

            return this;
        }

        /**
         * Sets the byte.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setByte(String parameterName, byte x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setByte(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setByte(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setByte(indexes.get(0), x);
                        setByte(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setByte(indexes.get(0), x);
                        setByte(indexes.get(1), x);
                        setByte(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setByte(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the byte.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setByte(String parameterName, Byte x) throws SQLException {
            setByte(parameterName, N.defaultIfNull(x));

            return this;
        }

        /**
         * Sets the short.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setShort(String parameterName, short x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setShort(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setShort(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setShort(indexes.get(0), x);
                        setShort(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setShort(indexes.get(0), x);
                        setShort(indexes.get(1), x);
                        setShort(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setShort(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the short.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setShort(String parameterName, Short x) throws SQLException {
            setShort(parameterName, N.defaultIfNull(x));

            return this;
        }

        /**
         * Sets the int.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setInt(String parameterName, int x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setInt(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setInt(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setInt(indexes.get(0), x);
                        setInt(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setInt(indexes.get(0), x);
                        setInt(indexes.get(1), x);
                        setInt(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setInt(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the int.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setInt(String parameterName, Integer x) throws SQLException {
            setInt(parameterName, N.defaultIfNull(x));

            return this;
        }

        /**
         * Sets the long.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setLong(String parameterName, long x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setLong(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setLong(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setLong(indexes.get(0), x);
                        setLong(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setLong(indexes.get(0), x);
                        setLong(indexes.get(1), x);
                        setLong(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setLong(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the long.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setLong(String parameterName, Long x) throws SQLException {
            setLong(parameterName, N.defaultIfNull(x));

            return this;
        }

        /**
         * Sets the float.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setFloat(String parameterName, float x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setFloat(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setFloat(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setFloat(indexes.get(0), x);
                        setFloat(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setFloat(indexes.get(0), x);
                        setFloat(indexes.get(1), x);
                        setFloat(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setFloat(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the float.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setFloat(String parameterName, Float x) throws SQLException {
            setFloat(parameterName, N.defaultIfNull(x));

            return this;
        }

        /**
         * Sets the double.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setDouble(String parameterName, double x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setDouble(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setDouble(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setDouble(indexes.get(0), x);
                        setDouble(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setDouble(indexes.get(0), x);
                        setDouble(indexes.get(1), x);
                        setDouble(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setDouble(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the double.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setDouble(String parameterName, Double x) throws SQLException {
            setDouble(parameterName, N.defaultIfNull(x));

            return this;
        }

        /**
         * Sets the big decimal.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setBigDecimal(String parameterName, BigDecimal x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setBigDecimal(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setBigDecimal(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setBigDecimal(indexes.get(0), x);
                        setBigDecimal(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setBigDecimal(indexes.get(0), x);
                        setBigDecimal(indexes.get(1), x);
                        setBigDecimal(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setBigDecimal(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the string.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setString(String parameterName, String x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setString(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setString(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setString(indexes.get(0), x);
                        setString(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setString(indexes.get(0), x);
                        setString(indexes.get(1), x);
                        setString(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setString(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the date.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setDate(String parameterName, java.sql.Date x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setDate(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setDate(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setDate(indexes.get(0), x);
                        setDate(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setDate(indexes.get(0), x);
                        setDate(indexes.get(1), x);
                        setDate(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setDate(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the date.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setDate(String parameterName, java.util.Date x) throws SQLException {
            setDate(parameterName, x == null ? null : x instanceof java.sql.Date ? (java.sql.Date) x : new java.sql.Date(x.getTime()));

            return this;
        }

        /**
         * Sets the time.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setTime(String parameterName, java.sql.Time x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setTime(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setTime(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setTime(indexes.get(0), x);
                        setTime(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setTime(indexes.get(0), x);
                        setTime(indexes.get(1), x);
                        setTime(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setTime(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the time.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setTime(String parameterName, java.util.Date x) throws SQLException {
            setTime(parameterName, x == null ? null : x instanceof java.sql.Time ? (java.sql.Time) x : new java.sql.Time(x.getTime()));

            return this;
        }

        /**
         * Sets the timestamp.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setTimestamp(String parameterName, java.sql.Timestamp x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setTimestamp(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setTimestamp(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setTimestamp(indexes.get(0), x);
                        setTimestamp(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setTimestamp(indexes.get(0), x);
                        setTimestamp(indexes.get(1), x);
                        setTimestamp(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setTimestamp(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the timestamp.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setTimestamp(String parameterName, java.util.Date x) throws SQLException {
            setTimestamp(parameterName, x == null ? null : x instanceof java.sql.Timestamp ? (java.sql.Timestamp) x : new java.sql.Timestamp(x.getTime()));

            return this;
        }

        /**
         * Sets the bytes.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setBytes(String parameterName, byte[] x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setBytes(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setBytes(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setBytes(indexes.get(0), x);
                        setBytes(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setBytes(indexes.get(0), x);
                        setBytes(indexes.get(1), x);
                        setBytes(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setBytes(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the ascii stream.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setAsciiStream(String parameterName, InputStream x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setAsciiStream(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setAsciiStream(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setAsciiStream(indexes.get(0), x);
                        setAsciiStream(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setAsciiStream(indexes.get(0), x);
                        setAsciiStream(indexes.get(1), x);
                        setAsciiStream(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setAsciiStream(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the ascii stream.
         *
         * @param parameterName
         * @param x
         * @param length
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setAsciiStream(String parameterName, InputStream x, long length) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setAsciiStream(i + 1, x, length);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setAsciiStream(indexes.get(0), x, length);
                    } else if (indexes.size() == 2) {
                        setAsciiStream(indexes.get(0), x, length);
                        setAsciiStream(indexes.get(1), x, length);
                    } else if (indexes.size() == 3) {
                        setAsciiStream(indexes.get(0), x, length);
                        setAsciiStream(indexes.get(1), x, length);
                        setAsciiStream(indexes.get(2), x, length);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setAsciiStream(indexes.get(i), x, length);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the binary stream.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setBinaryStream(String parameterName, InputStream x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setBinaryStream(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setBinaryStream(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setBinaryStream(indexes.get(0), x);
                        setBinaryStream(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setBinaryStream(indexes.get(0), x);
                        setBinaryStream(indexes.get(1), x);
                        setBinaryStream(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setBinaryStream(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the binary stream.
         *
         * @param parameterName
         * @param x
         * @param length
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setBinaryStream(String parameterName, InputStream x, long length) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setBinaryStream(i + 1, x, length);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setBinaryStream(indexes.get(0), x, length);
                    } else if (indexes.size() == 2) {
                        setBinaryStream(indexes.get(0), x, length);
                        setBinaryStream(indexes.get(1), x, length);
                    } else if (indexes.size() == 3) {
                        setBinaryStream(indexes.get(0), x, length);
                        setBinaryStream(indexes.get(1), x, length);
                        setBinaryStream(indexes.get(2), x, length);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setBinaryStream(indexes.get(i), x, length);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the character stream.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setCharacterStream(String parameterName, Reader x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setCharacterStream(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setCharacterStream(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setCharacterStream(indexes.get(0), x);
                        setCharacterStream(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setCharacterStream(indexes.get(0), x);
                        setCharacterStream(indexes.get(1), x);
                        setCharacterStream(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setCharacterStream(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the character stream.
         *
         * @param parameterName
         * @param x
         * @param length
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setCharacterStream(String parameterName, Reader x, long length) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setCharacterStream(i + 1, x, length);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setCharacterStream(indexes.get(0), x, length);
                    } else if (indexes.size() == 2) {
                        setCharacterStream(indexes.get(0), x, length);
                        setCharacterStream(indexes.get(1), x, length);
                    } else if (indexes.size() == 3) {
                        setCharacterStream(indexes.get(0), x, length);
                        setCharacterStream(indexes.get(1), x, length);
                        setCharacterStream(indexes.get(2), x, length);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setCharacterStream(indexes.get(i), x, length);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the N character stream.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setNCharacterStream(String parameterName, Reader x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setNCharacterStream(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setNCharacterStream(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setNCharacterStream(indexes.get(0), x);
                        setNCharacterStream(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setNCharacterStream(indexes.get(0), x);
                        setNCharacterStream(indexes.get(1), x);
                        setNCharacterStream(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setNCharacterStream(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the N character stream.
         *
         * @param parameterName
         * @param x
         * @param length
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setNCharacterStream(String parameterName, Reader x, long length) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setNCharacterStream(i + 1, x, length);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setNCharacterStream(indexes.get(0), x, length);
                    } else if (indexes.size() == 2) {
                        setNCharacterStream(indexes.get(0), x, length);
                        setNCharacterStream(indexes.get(1), x, length);
                    } else if (indexes.size() == 3) {
                        setNCharacterStream(indexes.get(0), x, length);
                        setNCharacterStream(indexes.get(1), x, length);
                        setNCharacterStream(indexes.get(2), x, length);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setNCharacterStream(indexes.get(i), x, length);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the blob.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setBlob(String parameterName, java.sql.Blob x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setBlob(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setBlob(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setBlob(indexes.get(0), x);
                        setBlob(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setBlob(indexes.get(0), x);
                        setBlob(indexes.get(1), x);
                        setBlob(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setBlob(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the blob.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setBlob(String parameterName, InputStream x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setBlob(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setBlob(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setBlob(indexes.get(0), x);
                        setBlob(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setBlob(indexes.get(0), x);
                        setBlob(indexes.get(1), x);
                        setBlob(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setBlob(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the blob.
         *
         * @param parameterName
         * @param x
         * @param length
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setBlob(String parameterName, InputStream x, long length) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setBlob(i + 1, x, length);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setBlob(indexes.get(0), x, length);
                    } else if (indexes.size() == 2) {
                        setBlob(indexes.get(0), x, length);
                        setBlob(indexes.get(1), x, length);
                    } else if (indexes.size() == 3) {
                        setBlob(indexes.get(0), x, length);
                        setBlob(indexes.get(1), x, length);
                        setBlob(indexes.get(2), x, length);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setBlob(indexes.get(i), x, length);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the clob.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setClob(String parameterName, java.sql.Clob x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setClob(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setClob(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setClob(indexes.get(0), x);
                        setClob(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setClob(indexes.get(0), x);
                        setClob(indexes.get(1), x);
                        setClob(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setClob(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the clob.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setClob(String parameterName, Reader x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setClob(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setClob(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setClob(indexes.get(0), x);
                        setClob(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setClob(indexes.get(0), x);
                        setClob(indexes.get(1), x);
                        setClob(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setClob(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the clob.
         *
         * @param parameterName
         * @param x
         * @param length
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setClob(String parameterName, Reader x, long length) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setClob(i + 1, x, length);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setClob(indexes.get(0), x, length);
                    } else if (indexes.size() == 2) {
                        setClob(indexes.get(0), x, length);
                        setClob(indexes.get(1), x, length);
                    } else if (indexes.size() == 3) {
                        setClob(indexes.get(0), x, length);
                        setClob(indexes.get(1), x, length);
                        setClob(indexes.get(2), x, length);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setClob(indexes.get(i), x, length);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the N clob.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setNClob(String parameterName, java.sql.NClob x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setNClob(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setNClob(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setNClob(indexes.get(0), x);
                        setNClob(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setNClob(indexes.get(0), x);
                        setNClob(indexes.get(1), x);
                        setNClob(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setNClob(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the N clob.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setNClob(String parameterName, Reader x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setNClob(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setNClob(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setNClob(indexes.get(0), x);
                        setNClob(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setNClob(indexes.get(0), x);
                        setNClob(indexes.get(1), x);
                        setNClob(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setNClob(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the N clob.
         *
         * @param parameterName
         * @param x
         * @param length
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setNClob(String parameterName, Reader x, long length) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setNClob(i + 1, x, length);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setNClob(indexes.get(0), x, length);
                    } else if (indexes.size() == 2) {
                        setNClob(indexes.get(0), x, length);
                        setNClob(indexes.get(1), x, length);
                    } else if (indexes.size() == 3) {
                        setNClob(indexes.get(0), x, length);
                        setNClob(indexes.get(1), x, length);
                        setNClob(indexes.get(2), x, length);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setNClob(indexes.get(i), x, length);
                        }
                    }
                }
            }

            return this;
        }

        /**
         *
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setURL(String parameterName, URL x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setURL(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setURL(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setURL(indexes.get(0), x);
                        setURL(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setURL(indexes.get(0), x);
                        setURL(indexes.get(1), x);
                        setURL(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setURL(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         *
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setSQLXML(String parameterName, java.sql.SQLXML x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setSQLXML(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setSQLXML(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setSQLXML(indexes.get(0), x);
                        setSQLXML(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setSQLXML(indexes.get(0), x);
                        setSQLXML(indexes.get(1), x);
                        setSQLXML(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setSQLXML(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         *
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setRowId(String parameterName, java.sql.RowId x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setRowId(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setRowId(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setRowId(indexes.get(0), x);
                        setRowId(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setRowId(indexes.get(0), x);
                        setRowId(indexes.get(1), x);
                        setRowId(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setRowId(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         *
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setRef(String parameterName, java.sql.Ref x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setRef(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setRef(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setRef(indexes.get(0), x);
                        setRef(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setRef(indexes.get(0), x);
                        setRef(indexes.get(1), x);
                        setRef(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setRef(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         *
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setArray(String parameterName, java.sql.Array x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setArray(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setArray(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setArray(indexes.get(0), x);
                        setArray(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setArray(indexes.get(0), x);
                        setArray(indexes.get(1), x);
                        setArray(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setArray(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the object.
         *
         * @param parameterName
         * @param x
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setObject(String parameterName, Object x) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setObject(i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setObject(indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        setObject(indexes.get(0), x);
                        setObject(indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        setObject(indexes.get(0), x);
                        setObject(indexes.get(1), x);
                        setObject(indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setObject(indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the object.
         *
         * @param parameterName
         * @param x
         * @param sqlType
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setObject(String parameterName, Object x, int sqlType) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setObject(i + 1, x, sqlType);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setObject(indexes.get(0), x, sqlType);
                    } else if (indexes.size() == 2) {
                        setObject(indexes.get(0), x, sqlType);
                        setObject(indexes.get(1), x, sqlType);
                    } else if (indexes.size() == 3) {
                        setObject(indexes.get(0), x, sqlType);
                        setObject(indexes.get(1), x, sqlType);
                        setObject(indexes.get(2), x, sqlType);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setObject(indexes.get(i), x, sqlType);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the object.
         *
         * @param parameterName
         * @param x
         * @param sqlType
         * @param scaleOrLength
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setObject(String parameterName, Object x, int sqlType, int scaleOrLength) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setObject(i + 1, x, sqlType, scaleOrLength);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setObject(indexes.get(0), x, sqlType, scaleOrLength);
                    } else if (indexes.size() == 2) {
                        setObject(indexes.get(0), x, sqlType, scaleOrLength);
                        setObject(indexes.get(1), x, sqlType, scaleOrLength);
                    } else if (indexes.size() == 3) {
                        setObject(indexes.get(0), x, sqlType, scaleOrLength);
                        setObject(indexes.get(1), x, sqlType, scaleOrLength);
                        setObject(indexes.get(2), x, sqlType, scaleOrLength);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setObject(indexes.get(i), x, sqlType, scaleOrLength);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the object.
         *
         * @param parameterName
         * @param x
         * @param sqlType
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setObject(String parameterName, Object x, SQLType sqlType) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setObject(i + 1, x, sqlType);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setObject(indexes.get(0), x, sqlType);
                    } else if (indexes.size() == 2) {
                        setObject(indexes.get(0), x, sqlType);
                        setObject(indexes.get(1), x, sqlType);
                    } else if (indexes.size() == 3) {
                        setObject(indexes.get(0), x, sqlType);
                        setObject(indexes.get(1), x, sqlType);
                        setObject(indexes.get(2), x, sqlType);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setObject(indexes.get(i), x, sqlType);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the object.
         *
         * @param parameterName
         * @param x
         * @param sqlType
         * @param scaleOrLength
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setObject(String parameterName, Object x, SQLType sqlType, int scaleOrLength) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        setObject(i + 1, x, sqlType, scaleOrLength);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        setObject(indexes.get(0), x, sqlType, scaleOrLength);
                    } else if (indexes.size() == 2) {
                        setObject(indexes.get(0), x, sqlType, scaleOrLength);
                        setObject(indexes.get(1), x, sqlType, scaleOrLength);
                    } else if (indexes.size() == 3) {
                        setObject(indexes.get(0), x, sqlType, scaleOrLength);
                        setObject(indexes.get(1), x, sqlType, scaleOrLength);
                        setObject(indexes.get(2), x, sqlType, scaleOrLength);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            setObject(indexes.get(i), x, sqlType, scaleOrLength);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the object.
         *
         * @param parameterName
         * @param x
         * @param type
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setObject(final String parameterName, final Object x, final Type<Object> type) throws SQLException {
            if (parameterCount < 5) {
                int cnt = 0;

                for (int i = 0; i < parameterCount; i++) {
                    if (parameterNames.get(i).equals(parameterName)) {
                        type.set(stmt, i + 1, x);
                        cnt++;
                    }
                }

                if (cnt == 0) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                }
            } else {
                if (paramNameIndexMap == null) {
                    initParamNameIndexMap();
                }

                final IntList indexes = paramNameIndexMap.get(parameterName);

                if (indexes == null) {
                    close();
                    throw new IllegalArgumentException("Not found named parameter: " + parameterName);
                } else {
                    if (indexes.size() == 1) {
                        type.set(stmt, indexes.get(0), x);
                    } else if (indexes.size() == 2) {
                        type.set(stmt, indexes.get(0), x);
                        type.set(stmt, indexes.get(1), x);
                    } else if (indexes.size() == 3) {
                        type.set(stmt, indexes.get(0), x);
                        type.set(stmt, indexes.get(1), x);
                        type.set(stmt, indexes.get(2), x);
                    } else {
                        for (int i = 0, size = indexes.size(); i < size; i++) {
                            type.set(stmt, indexes.get(i), x);
                        }
                    }
                }
            }

            return this;
        }

        /**
         * Sets the parameters.
         *
         * @param parameters
         * @return
         * @throws SQLException the SQL exception
         */
        public NamedQuery setParameters(final Map<String, ?> parameters) throws SQLException {
            checkArgNotNull(parameters, "parameters");

            for (String paramName : parameterNames) {
                if (parameters.containsKey(paramName)) {
                    setObject(paramName, parameters.get(paramName));
                }
            }

            return this;
        }

        /**
         * Sets the parameters.
         *
         * @param parameters with getter/setter methods
         * @return
         * @throws SQLException the SQL exception
         */
        @SuppressWarnings("rawtypes")
        public NamedQuery setParameters(final Object parameters) throws SQLException {
            checkArgNotNull(parameters, "entity");

            if (ClassUtil.isEntity(parameters.getClass())) {
                final Class<?> cls = parameters.getClass();
                final EntityInfo entityInfo = ParserUtil.getEntityInfo(cls);
                PropInfo propInfo = null;

                for (int i = 0; i < parameterCount; i++) {
                    propInfo = entityInfo.getPropInfo(parameterNames.get(i));

                    if (propInfo != null) {
                        propInfo.dbType.set(stmt, i + 1, propInfo.getPropValue(parameters));
                    }
                }
            } else if (parameters instanceof Map) {
                return setParameters((Map<String, ?>) parameters);
            } else if (parameters instanceof Collection) {
                return setParameters((Collection) parameters);
            } else if (parameters instanceof Object[]) {
                return setParameters((Object[]) parameters);
            } else if (parameters instanceof EntityId) {
                final EntityId entityId = (EntityId) parameters;

                for (String paramName : parameterNames) {
                    if (entityId.containsKey(paramName)) {
                        setObject(paramName, entityId.get(paramName));
                    }
                }
            } else if (parameterCount == 1) {
                setObject(1, parameters);
            } else {
                close();
                throw new IllegalArgumentException("Unsupported named parameter type: " + parameters.getClass() + " for named sql: " + namedSQL.getNamedSQL());
            }

            return this;
        }

        /**
         * Sets the parameters.
         *
         * @param <T>
         * @param paramaters
         * @param parametersSetter
         * @return
         * @throws SQLException the SQL exception
         */
        public <T> NamedQuery setParameters(final T paramaters, TriParametersSetter<NamedQuery, T> parametersSetter) throws SQLException {
            checkArgNotNull(parametersSetter, "parametersSetter");

            boolean noException = false;

            try {
                parametersSetter.accept(namedSQL, this, paramaters);

                noException = true;
            } finally {
                if (noException == false) {
                    close();
                }
            }

            return this;
        }

        /**
         * @param <T>
         * @param batchParameters
         * @return
         * @throws SQLException the SQL exception
         */
        public <T> NamedQuery addBatchParameters(final Collection<T> batchParameters) throws SQLException {
            checkArgNotNull(batchParameters, "batchParameters");

            return addBatchParameters(batchParameters.iterator());
        }

        /**
         *
         * @param <T>
         * @param batchParameters
         * @return
         * @throws SQLException the SQL exception
         */
        public <T> NamedQuery addBatchParameters(final Iterator<T> batchParameters) throws SQLException {
            checkArgNotNull(batchParameters, "batchParameters");

            boolean noException = false;

            try {
                final Iterator<T> iter = batchParameters;

                while (iter.hasNext()) {
                    setParameters(iter.next());
                    stmt.addBatch();
                    isBatch = true;
                }

                noException = true;
            } finally {
                if (noException == false) {
                    close();
                }
            }

            return this;
        }
    }

    /**
     * The Enum FetchDirection.
     */
    public enum FetchDirection {

        /** The forward. */
        FORWARD(ResultSet.FETCH_FORWARD),
        /** The reverse. */
        REVERSE(ResultSet.FETCH_REVERSE),
        /** The unknown. */
        UNKNOWN(ResultSet.FETCH_UNKNOWN);

        /** The int value. */
        private final int intValue;

        /**
         * Instantiates a new fetch direction.
         *
         * @param intValue
         */
        FetchDirection(int intValue) {
            this.intValue = intValue;
        }

        /**
         *
         * @param intValue
         * @return
         */
        public static FetchDirection valueOf(int intValue) {
            switch (intValue) {
                case ResultSet.FETCH_FORWARD:
                    return FORWARD;

                case ResultSet.FETCH_REVERSE:
                    return REVERSE;

                case ResultSet.FETCH_UNKNOWN:
                    return UNKNOWN;

                default:
                    throw new IllegalArgumentException("No FetchDirection mapping to int value: " + intValue);

            }
        }

        /**
         *
         * @return
         */
        public int intValue() {
            return intValue;
        }
    }

    /**
     * The Interface ParametersSetter.
     *
     * @param <QS>
     */
    public interface ParametersSetter<QS> extends Throwables.Consumer<QS, SQLException> {
        @SuppressWarnings("rawtypes")
        public static final ParametersSetter DO_NOTHING = new ParametersSetter<Object>() {
            @Override
            public void accept(Object preparedQuery) throws SQLException {
                // Do nothing.
            }
        };

        /**
         *
         * @param preparedQuery
         * @throws SQLException the SQL exception
         */
        @Override
        void accept(QS preparedQuery) throws SQLException;
    }

    /**
     * The Interface BiParametersSetter.
     *
     * @param <QS>
     * @param <T>
     */
    public interface BiParametersSetter<QS, T> extends Throwables.BiConsumer<QS, T, SQLException> {
        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter DO_NOTHING = new BiParametersSetter<Object, Object>() {
            @Override
            public void accept(Object preparedQuery, Object param) throws SQLException {
                // Do nothing.
            }
        };

        /**
         *
         * @param preparedQuery
         * @param param
         * @throws SQLException the SQL exception
         */
        @Override
        void accept(QS preparedQuery, T param) throws SQLException;
    }

    /**
     * The Interface TriParametersSetter.
     *
     * @param <QS>
     * @param <T>
     */
    public interface TriParametersSetter<QS, T> extends Throwables.TriConsumer<NamedSQL, QS, T, SQLException> {
        @SuppressWarnings("rawtypes")
        public static final TriParametersSetter DO_NOTHING = new TriParametersSetter<Object, Object>() {
            @Override
            public void accept(NamedSQL namedSQL, Object preparedQuery, Object param) throws SQLException {
                // Do nothing.
            }
        };

        /**
         *
         * @param namedSQL
         * @param preparedQuery
         * @param param
         * @throws SQLException the SQL exception
         */
        @Override
        void accept(NamedSQL namedSQL, QS preparedQuery, T param) throws SQLException;
    }

    /**
     * The Interface ResultExtractor.
     *
     * @param <T>
     */
    public interface ResultExtractor<T> extends Throwables.Function<ResultSet, T, SQLException> {

        /** The Constant TO_DATA_SET. */
        ResultExtractor<DataSet> TO_DATA_SET = new ResultExtractor<DataSet>() {
            @Override
            public DataSet apply(final ResultSet rs) throws SQLException {
                return JdbcUtil.extractData(rs);
            }
        };

        /**
         *
         * @param rs
         * @return
         * @throws SQLException the SQL exception
         */
        @Override
        T apply(ResultSet rs) throws SQLException;

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor
         * @param valueExtractor
         * @return
         */
        static <K, V> ResultExtractor<Map<K, V>> toMap(final RowMapper<K> keyExtractor, final RowMapper<V> valueExtractor) {
            return toMap(keyExtractor, valueExtractor, Suppliers.<K, V> ofMap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param supplier
         * @return
         */
        static <K, V, M extends Map<K, V>> ResultExtractor<M> toMap(final RowMapper<K> keyExtractor, final RowMapper<V> valueExtractor,
                final Supplier<? extends M> supplier) {
            return toMap(keyExtractor, valueExtractor, Fn.<V> throwingMerger(), supplier);
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor
         * @param valueExtractor
         * @param mergeFunction
         * @return
         * @see {@link Fn.throwingMerger()}
         * @see {@link Fn.replacingMerger()}
         * @see {@link Fn.ignoringMerger()}
         */
        static <K, V> ResultExtractor<Map<K, V>> toMap(final RowMapper<K> keyExtractor, final RowMapper<V> valueExtractor,
                final BinaryOperator<V> mergeFunction) {
            return toMap(keyExtractor, valueExtractor, mergeFunction, Suppliers.<K, V> ofMap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param mergeFunction
         * @param supplier
         * @return
         * @see {@link Fn.throwingMerger()}
         * @see {@link Fn.replacingMerger()}
         * @see {@link Fn.ignoringMerger()}
         */
        static <K, V, M extends Map<K, V>> ResultExtractor<M> toMap(final RowMapper<K> keyExtractor, final RowMapper<V> valueExtractor,
                final BinaryOperator<V> mergeFunction, final Supplier<? extends M> supplier) {
            N.checkArgNotNull(keyExtractor, "keyExtractor");
            N.checkArgNotNull(valueExtractor, "valueExtractor");
            N.checkArgNotNull(mergeFunction, "mergeFunction");
            N.checkArgNotNull(supplier, "supplier");

            return new ResultExtractor<M>() {
                @Override
                public M apply(final ResultSet rs) throws SQLException {
                    final M result = supplier.get();

                    while (rs.next()) {
                        Maps.merge(result, keyExtractor.apply(rs), valueExtractor.apply(rs), mergeFunction);
                    }

                    return result;
                }
            };
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <A>
         * @param <D>
         * @param keyExtractor
         * @param valueExtractor
         * @param downstream
         * @return
         */
        static <K, V, A, D> ResultExtractor<Map<K, D>> toMap(final RowMapper<K> keyExtractor, final RowMapper<V> valueExtractor,
                final Collector<? super V, A, D> downstream) {
            return toMap(keyExtractor, valueExtractor, downstream, Suppliers.<K, D> ofMap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <A>
         * @param <D>
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param downstream
         * @param supplier
         * @return
         */
        static <K, V, A, D, M extends Map<K, D>> ResultExtractor<M> toMap(final RowMapper<K> keyExtractor, final RowMapper<V> valueExtractor,
                final Collector<? super V, A, D> downstream, final Supplier<? extends M> supplier) {
            N.checkArgNotNull(keyExtractor, "keyExtractor");
            N.checkArgNotNull(valueExtractor, "valueExtractor");
            N.checkArgNotNull(downstream, "downstream");
            N.checkArgNotNull(supplier, "supplier");

            return new ResultExtractor<M>() {
                @Override
                public M apply(final ResultSet rs) throws SQLException {

                    final Supplier<A> downstreamSupplier = downstream.supplier();
                    final BiConsumer<A, ? super V> downstreamAccumulator = downstream.accumulator();
                    final Function<A, D> downstreamFinisher = downstream.finisher();

                    final M result = supplier.get();
                    final Map<K, A> tmp = (Map<K, A>) result;
                    K key = null;
                    A container = null;

                    while (rs.next()) {
                        key = keyExtractor.apply(rs);
                        container = tmp.get(key);

                        if (container == null) {
                            container = downstreamSupplier.get();
                            tmp.put(key, container);
                        }

                        downstreamAccumulator.accept(container, valueExtractor.apply(rs));
                    }

                    for (Map.Entry<K, D> entry : result.entrySet()) {
                        entry.setValue(downstreamFinisher.apply((A) entry.getValue()));
                    }

                    return result;
                }
            };
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor
         * @param valueExtractor
         * @return
         */
        static <K, V> ResultExtractor<ListMultimap<K, V>> toMultimap(final RowMapper<K> keyExtractor, final RowMapper<V> valueExtractor) {
            return toMultimap(keyExtractor, valueExtractor, Suppliers.<K, V> ofListMultimap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <C>
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param multimapSupplier
         * @return
         */
        static <K, V, C extends Collection<V>, M extends Multimap<K, V, C>> ResultExtractor<M> toMultimap(final RowMapper<K> keyExtractor,
                final RowMapper<V> valueExtractor, final Supplier<? extends M> multimapSupplier) {
            N.checkArgNotNull(keyExtractor, "keyExtractor");
            N.checkArgNotNull(valueExtractor, "valueExtractor");
            N.checkArgNotNull(multimapSupplier, "multimapSupplier");

            return new ResultExtractor<M>() {
                @Override
                public M apply(final ResultSet rs) throws SQLException {
                    final M result = multimapSupplier.get();

                    while (rs.next()) {
                        result.put(keyExtractor.apply(rs), valueExtractor.apply(rs));
                    }

                    return result;
                }
            };
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor
         * @param valueExtractor
         * @return
         * @throws SQLException the SQL exception
         */
        static <K, V> ResultExtractor<Map<K, List<V>>> groupTo(final RowMapper<K> keyExtractor, final RowMapper<V> valueExtractor) throws SQLException {
            return groupTo(keyExtractor, valueExtractor, Suppliers.<K, List<V>> ofMap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param supplier
         * @return
         */
        static <K, V, M extends Map<K, List<V>>> ResultExtractor<M> groupTo(final RowMapper<K> keyExtractor, final RowMapper<V> valueExtractor,
                final Supplier<? extends M> supplier) {
            N.checkArgNotNull(keyExtractor, "keyExtractor");
            N.checkArgNotNull(valueExtractor, "valueExtractor");
            N.checkArgNotNull(supplier, "supplier");

            return new ResultExtractor<M>() {
                @Override
                public M apply(final ResultSet rs) throws SQLException {

                    final M result = supplier.get();
                    K key = null;
                    List<V> value = null;

                    while (rs.next()) {
                        key = keyExtractor.apply(rs);
                        value = result.get(key);

                        if (value == null) {
                            value = new ArrayList<>();
                            result.put(key, value);
                        }

                        value.add(valueExtractor.apply(rs));
                    }

                    return result;
                }
            };
        }
    }

    /**
     * The Interface BiResultExtractor.
     *
     * @param <T>
     */
    public interface BiResultExtractor<T> extends Throwables.BiFunction<ResultSet, List<String>, T, SQLException> {

        /**
         *
         * @param rs
         * @param columnLabels
         * @return
         * @throws SQLException the SQL exception
         */
        @Override
        T apply(ResultSet rs, List<String> columnLabels) throws SQLException;

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor
         * @param valueExtractor
         * @return
         */
        static <K, V> BiResultExtractor<Map<K, V>> toMap(final BiRowMapper<K> keyExtractor, final BiRowMapper<V> valueExtractor) {
            return toMap(keyExtractor, valueExtractor, Suppliers.<K, V> ofMap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param supplier
         * @return
         */
        static <K, V, M extends Map<K, V>> BiResultExtractor<M> toMap(final BiRowMapper<K> keyExtractor, final BiRowMapper<V> valueExtractor,
                final Supplier<? extends M> supplier) {
            return toMap(keyExtractor, valueExtractor, Fn.<V> throwingMerger(), supplier);
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor
         * @param valueExtractor
         * @param mergeFunction
         * @return
         * @see {@link Fn.throwingMerger()}
         * @see {@link Fn.replacingMerger()}
         * @see {@link Fn.ignoringMerger()}
         */
        static <K, V> BiResultExtractor<Map<K, V>> toMap(final BiRowMapper<K> keyExtractor, final BiRowMapper<V> valueExtractor,
                final BinaryOperator<V> mergeFunction) {
            return toMap(keyExtractor, valueExtractor, mergeFunction, Suppliers.<K, V> ofMap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param mergeFunction
         * @param supplier
         * @return
         * @see {@link Fn.throwingMerger()}
         * @see {@link Fn.replacingMerger()}
         * @see {@link Fn.ignoringMerger()}
         */
        static <K, V, M extends Map<K, V>> BiResultExtractor<M> toMap(final BiRowMapper<K> keyExtractor, final BiRowMapper<V> valueExtractor,
                final BinaryOperator<V> mergeFunction, final Supplier<? extends M> supplier) {
            N.checkArgNotNull(keyExtractor, "keyExtractor");
            N.checkArgNotNull(valueExtractor, "valueExtractor");
            N.checkArgNotNull(mergeFunction, "mergeFunction");
            N.checkArgNotNull(supplier, "supplier");

            return new BiResultExtractor<M>() {
                @Override
                public M apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                    final M result = supplier.get();

                    while (rs.next()) {
                        Maps.merge(result, keyExtractor.apply(rs, columnLabels), valueExtractor.apply(rs, columnLabels), mergeFunction);
                    }

                    return result;
                }
            };
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <A>
         * @param <D>
         * @param keyExtractor
         * @param valueExtractor
         * @param downstream
         * @return
         */
        static <K, V, A, D> BiResultExtractor<Map<K, D>> toMap(final BiRowMapper<K> keyExtractor, final BiRowMapper<V> valueExtractor,
                final Collector<? super V, A, D> downstream) {
            return toMap(keyExtractor, valueExtractor, downstream, Suppliers.<K, D> ofMap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <A>
         * @param <D>
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param downstream
         * @param supplier
         * @return
         */
        static <K, V, A, D, M extends Map<K, D>> BiResultExtractor<M> toMap(final BiRowMapper<K> keyExtractor, final BiRowMapper<V> valueExtractor,
                final Collector<? super V, A, D> downstream, final Supplier<? extends M> supplier) {
            N.checkArgNotNull(keyExtractor, "keyExtractor");
            N.checkArgNotNull(valueExtractor, "valueExtractor");
            N.checkArgNotNull(downstream, "downstream");
            N.checkArgNotNull(supplier, "supplier");

            return new BiResultExtractor<M>() {
                @Override
                public M apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {

                    final Supplier<A> downstreamSupplier = downstream.supplier();
                    final BiConsumer<A, ? super V> downstreamAccumulator = downstream.accumulator();
                    final Function<A, D> downstreamFinisher = downstream.finisher();

                    final M result = supplier.get();
                    final Map<K, A> tmp = (Map<K, A>) result;
                    K key = null;
                    A container = null;

                    while (rs.next()) {
                        key = keyExtractor.apply(rs, columnLabels);
                        container = tmp.get(key);

                        if (container == null) {
                            container = downstreamSupplier.get();
                            tmp.put(key, container);
                        }

                        downstreamAccumulator.accept(container, valueExtractor.apply(rs, columnLabels));
                    }

                    for (Map.Entry<K, D> entry : result.entrySet()) {
                        entry.setValue(downstreamFinisher.apply((A) entry.getValue()));
                    }

                    return result;
                }
            };
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor
         * @param valueExtractor
         * @return
         */
        static <K, V> BiResultExtractor<ListMultimap<K, V>> toMultimap(final BiRowMapper<K> keyExtractor, final BiRowMapper<V> valueExtractor) {
            return toMultimap(keyExtractor, valueExtractor, Suppliers.<K, V> ofListMultimap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <C>
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param multimapSupplier
         * @return
         */
        static <K, V, C extends Collection<V>, M extends Multimap<K, V, C>> BiResultExtractor<M> toMultimap(final BiRowMapper<K> keyExtractor,
                final BiRowMapper<V> valueExtractor, final Supplier<? extends M> multimapSupplier) {
            N.checkArgNotNull(keyExtractor, "keyExtractor");
            N.checkArgNotNull(valueExtractor, "valueExtractor");
            N.checkArgNotNull(multimapSupplier, "multimapSupplier");

            return new BiResultExtractor<M>() {
                @Override
                public M apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                    final M result = multimapSupplier.get();

                    while (rs.next()) {
                        result.put(keyExtractor.apply(rs, columnLabels), valueExtractor.apply(rs, columnLabels));
                    }

                    return result;
                }
            };
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor
         * @param valueExtractor
         * @return
         * @throws SQLException the SQL exception
         */
        static <K, V> BiResultExtractor<Map<K, List<V>>> groupTo(final BiRowMapper<K> keyExtractor, final BiRowMapper<V> valueExtractor) throws SQLException {
            return groupTo(keyExtractor, valueExtractor, Suppliers.<K, List<V>> ofMap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param supplier
         * @return
         */
        static <K, V, M extends Map<K, List<V>>> BiResultExtractor<M> groupTo(final BiRowMapper<K> keyExtractor, final BiRowMapper<V> valueExtractor,
                final Supplier<? extends M> supplier) {
            N.checkArgNotNull(keyExtractor, "keyExtractor");
            N.checkArgNotNull(valueExtractor, "valueExtractor");
            N.checkArgNotNull(supplier, "supplier");

            return new BiResultExtractor<M>() {
                @Override
                public M apply(final ResultSet rs, List<String> columnLabels) throws SQLException {
                    final M result = supplier.get();
                    K key = null;
                    List<V> value = null;

                    while (rs.next()) {
                        key = keyExtractor.apply(rs, columnLabels);
                        value = result.get(key);

                        if (value == null) {
                            value = new ArrayList<>();
                            result.put(key, value);
                        }

                        value.add(valueExtractor.apply(rs, columnLabels));
                    }

                    return result;
                }
            };
        }
    }

    /** The Constant NO_GENERATED_KEY_EXTRACTOR. */
    static final RowMapper<Object> NO_GENERATED_KEY_EXTRACTOR = rs -> null;

    /** The Constant SINGLE_GENERATED_KEY_EXTRACTOR. */
    static final RowMapper<Object> SINGLE_GENERATED_KEY_EXTRACTOR = rs -> getColumnValue(rs, 1);

    /** The Constant MULTI_GENERATED_KEY_EXTRACTOR. */
    @SuppressWarnings("deprecation")
    static final RowMapper<Object> MULTI_GENERATED_KEY_EXTRACTOR = rs -> {
        final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

        if (columnLabels.size() == 1) {
            return getColumnValue(rs, 1);
        } else {
            final int columnCount = columnLabels.size();
            final Seid id = Seid.of(N.EMPTY_STRING);

            for (int i = 1; i <= columnCount; i++) {
                id.set(columnLabels.get(i - 1), getColumnValue(rs, i));
            }

            return id;
        }
    };

    /** The Constant NO_BI_GENERATED_KEY_EXTRACTOR. */
    static final BiRowMapper<Object> NO_BI_GENERATED_KEY_EXTRACTOR = (rs, columnLabels) -> null;

    /** The Constant SINGLE_BI_GENERATED_KEY_EXTRACTOR. */
    static final BiRowMapper<Object> SINGLE_BI_GENERATED_KEY_EXTRACTOR = (rs, columnLabels) -> getColumnValue(rs, 1);

    /** The Constant MULTI_BI_GENERATED_KEY_EXTRACTOR. */
    @SuppressWarnings("deprecation")
    static final BiRowMapper<Object> MULTI_BI_GENERATED_KEY_EXTRACTOR = (rs, columnLabels) -> {
        if (columnLabels.size() == 1) {
            return getColumnValue(rs, 1);
        } else {
            final int columnCount = columnLabels.size();
            final Seid id = Seid.of(N.EMPTY_STRING);

            for (int i = 1; i <= columnCount; i++) {
                id.set(columnLabels.get(i - 1), getColumnValue(rs, i));
            }

            return id;
        }
    };

    @SuppressWarnings("rawtypes")
    private static final Map<Type<?>, RowMapper> singleGetRowMapperPool = new ObjectPool<>(1024);

    /**
     * Don't use {@code RowMapper} in {@link PreparedQuery#list(RowMapper)} or any place where multiple records will be retrieved by it, if column labels/count are used in {@link RowMapper#apply(ResultSet)}.
     * Consider using {@code BiRowMapper} instead because it's more efficient to retrieve multiple records when column labels/count are used.
     *
     * @param <T>
     */
    public interface RowMapper<T> extends Throwables.Function<ResultSet, T, SQLException> {

        /** The Constant GET_BOOLEAN. */
        RowMapper<Boolean> GET_BOOLEAN = new RowMapper<Boolean>() {
            @Override
            public Boolean apply(final ResultSet rs) throws SQLException {
                return rs.getBoolean(1);
            }
        };

        /** The Constant GET_BYTE. */
        RowMapper<Byte> GET_BYTE = new RowMapper<Byte>() {
            @Override
            public Byte apply(final ResultSet rs) throws SQLException {
                return rs.getByte(1);
            }
        };

        /** The Constant GET_SHORT. */
        RowMapper<Short> GET_SHORT = new RowMapper<Short>() {
            @Override
            public Short apply(final ResultSet rs) throws SQLException {
                return rs.getShort(1);
            }
        };

        /** The Constant GET_INT. */
        RowMapper<Integer> GET_INT = new RowMapper<Integer>() {
            @Override
            public Integer apply(final ResultSet rs) throws SQLException {
                return rs.getInt(1);
            }
        };

        /** The Constant GET_LONG. */
        RowMapper<Long> GET_LONG = new RowMapper<Long>() {
            @Override
            public Long apply(final ResultSet rs) throws SQLException {
                return rs.getLong(1);
            }
        };

        /** The Constant GET_FLOAT. */
        RowMapper<Float> GET_FLOAT = new RowMapper<Float>() {
            @Override
            public Float apply(final ResultSet rs) throws SQLException {
                return rs.getFloat(1);
            }
        };

        /** The Constant GET_DOUBLE. */
        RowMapper<Double> GET_DOUBLE = new RowMapper<Double>() {
            @Override
            public Double apply(final ResultSet rs) throws SQLException {
                return rs.getDouble(1);
            }
        };

        /** The Constant GET_BIG_DECIMAL. */
        RowMapper<BigDecimal> GET_BIG_DECIMAL = new RowMapper<BigDecimal>() {
            @Override
            public BigDecimal apply(final ResultSet rs) throws SQLException {
                return rs.getBigDecimal(1);
            }
        };

        /** The Constant GET_STRING. */
        RowMapper<String> GET_STRING = new RowMapper<String>() {
            @Override
            public String apply(final ResultSet rs) throws SQLException {
                return rs.getString(1);
            }
        };

        /** The Constant GET_DATE. */
        RowMapper<Date> GET_DATE = new RowMapper<Date>() {
            @Override
            public Date apply(final ResultSet rs) throws SQLException {
                return rs.getDate(1);
            }
        };

        /** The Constant GET_TIME. */
        RowMapper<Time> GET_TIME = new RowMapper<Time>() {
            @Override
            public Time apply(final ResultSet rs) throws SQLException {
                return rs.getTime(1);
            }
        };

        /** The Constant GET_TIMESTAMP. */
        RowMapper<Timestamp> GET_TIMESTAMP = new RowMapper<Timestamp>() {
            @Override
            public Timestamp apply(final ResultSet rs) throws SQLException {
                return rs.getTimestamp(1);
            }
        };

        /** The Constant GET_OBJECT. */
        @SuppressWarnings("rawtypes")
        RowMapper GET_OBJECT = new RowMapper<Object>() {
            @Override
            public Object apply(final ResultSet rs) throws SQLException {
                return rs.getObject(1);
            }
        };

        /**
         *
         * @param rs
         * @return generally should not return {@code null}.
         * @throws SQLException the SQL exception
         */
        @Override
        T apply(ResultSet rs) throws SQLException;

        /**
         * Gets the values from the first column.
         *
         * @param <T>
         * @param firstColumnType
         * @return
         */
        static <T> RowMapper<T> get(final Class<? extends T> firstColumnType) {
            return get(N.typeOf(firstColumnType));
        }

        /**
         * Gets the values from the first column.
         *
         * @param <T>
         * @param firstColumnType
         * @return
         */
        static <T> RowMapper<T> get(final Type<? extends T> firstColumnType) {
            RowMapper<T> result = singleGetRowMapperPool.get(firstColumnType);

            if (result == null) {
                result = new RowMapper<T>() {
                    @Override
                    public T apply(final ResultSet rs) throws SQLException {
                        return firstColumnType.get(rs, 1);
                    }
                };

                singleGetRowMapperPool.put(firstColumnType, result);
            }

            return result;
        }

        static RowMapperBuilder builder() {
            return new RowMapperBuilder();
        }

        //    static RowMapperBuilder builder(final int columnCount) {
        //        return new RowMapperBuilder(columnCount);
        //    }

        public static class RowMapperBuilder {
            //    private int columnCount = -1;
            //    private ColumnGetter<?>[] columnGetters;
            private Map<Integer, ColumnGetter<?>> columnGetterMap;

            RowMapperBuilder() {
                columnGetterMap = new HashMap<>(9);
                columnGetterMap.put(0, ColumnGetter.GET_OBJECT);
            }

            //        RowMapperBuilder(final int columnCount) {
            //            this.columnCount = columnCount;
            //            columnGetters = new ColumnGetter[columnCount + 1];
            //            columnGetters[0] = ColumnGetter.DEFAULT;
            //        }

            /**
             * Set default column getter function.
             *
             * @param columnGetter
             * @return
             */
            public RowMapperBuilder defauLt(final ColumnGetter<?> columnGetter) {
                //        if (columnGetters == null) {
                //            columnGetterMap.put(0, columnGetter);
                //        } else {
                //            columnGetters[0] = columnGetter;
                //        }

                columnGetterMap.put(0, columnGetter);
                return this;
            }

            public RowMapperBuilder getBoolean(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_BOOLEAN);
            }

            public RowMapperBuilder getByte(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_BYTE);
            }

            public RowMapperBuilder getShort(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_SHORT);
            }

            public RowMapperBuilder getInt(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_INT);
            }

            public RowMapperBuilder getLong(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_LONG);
            }

            public RowMapperBuilder getFloat(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_FLOAT);
            }

            public RowMapperBuilder getDouble(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_DOUBLE);
            }

            public RowMapperBuilder getBigDecimal(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_BIG_DECIMAL);
            }

            public RowMapperBuilder getString(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_STRING);
            }

            public RowMapperBuilder getDate(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_DATE);
            }

            public RowMapperBuilder getTime(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_TIME);
            }

            public RowMapperBuilder getTimestamp(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_TIMESTAMP);
            }

            public RowMapperBuilder get(final int columnIndex, final ColumnGetter<?> columnGetter) {
                N.checkArgPositive(columnIndex, "columnIndex");
                N.checkArgNotNull(columnGetter, "columnGetter");

                //        if (columnGetters == null) {
                //            columnGetterMap.put(columnIndex, columnGetter);
                //        } else {
                //            columnGetters[columnIndex] = columnGetter;
                //        }

                columnGetterMap.put(columnIndex, columnGetter);
                return this;
            }

            /**
             *
             * Set column getter function for column[columnIndex].
             *
             * @param columnIndex start from 1.
             * @param columnGetter
             * @return
             * @deprecated replaced by {@link #get(int, ColumnGetter)}
             */
            @Deprecated
            public RowMapperBuilder column(final int columnIndex, final ColumnGetter<?> columnGetter) {
                return get(columnIndex, columnGetter);
            }

            //    /**
            //     * Set default column getter function.
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder __(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(0, columnGetter);
            //        } else {
            //            columnGetters[0] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     *
            //     * Set column getter function for column[columnIndex].
            //     *
            //     * @param columnIndex start from 1.
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder __(final int columnIndex, final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(columnIndex, columnGetter);
            //        } else {
            //            columnGetters[columnIndex] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     * Set column getter function for column[1].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder _1(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(1, columnGetter);
            //        } else {
            //            columnGetters[1] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     *
            //     * Set column getter function for column[1].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder _2(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(2, columnGetter);
            //        } else {
            //            columnGetters[2] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     *
            //     * Set column getter function for column[3].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder _3(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(3, columnGetter);
            //        } else {
            //            columnGetters[3] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     *
            //     * Set column getter function for column[4].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder _4(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(4, columnGetter);
            //        } else {
            //            columnGetters[4] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     *
            //     * Set column getter function for column[5].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder _5(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(5, columnGetter);
            //        } else {
            //            columnGetters[5] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     *
            //     * Set column getter function for column[6].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder _6(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(6, columnGetter);
            //        } else {
            //            columnGetters[6] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     *
            //     * Set column getter function for column[7].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder _7(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(7, columnGetter);
            //        } else {
            //            columnGetters[7] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     *
            //     * Set column getter function for column[8].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder _8(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(8, columnGetter);
            //        } else {
            //            columnGetters[8] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     *
            //     * Set column getter function for column[9].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder _9(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(9, columnGetter);
            //        } else {
            //            columnGetters[9] = columnGetter;
            //        }
            //
            //        return this;
            //    }

            //    void setDefaultColumnGetter() {
            //        if (columnGetters != null) {
            //            for (int i = 1, len = columnGetters.length; i < len; i++) {
            //                if (columnGetters[i] == null) {
            //                    columnGetters[i] = columnGetters[0];
            //                }
            //            }
            //        }
            //    }

            ColumnGetter<?>[] initColumnGetter(ResultSet rs) throws SQLException {
                final ColumnGetter<?>[] rsColumnGetters = new ColumnGetter<?>[rs.getMetaData().getColumnCount() + 1];
                rsColumnGetters[0] = columnGetterMap.get(0);

                for (int i = 1, len = rsColumnGetters.length; i < len; i++) {
                    rsColumnGetters[i] = columnGetterMap.getOrDefault(i, rsColumnGetters[0]);
                }

                return rsColumnGetters;
            }

            /**
             * Don't cache or reuse the returned {@code RowMapper} instance.
             *
             * @return
             */
            public RowMapper<Object[]> toArray() {
                // setDefaultColumnGetter();

                return new RowMapper<Object[]>() {
                    private volatile int rsColumnCount = -1;
                    private volatile ColumnGetter<?>[] rsColumnGetters = null;

                    @Override
                    public Object[] apply(ResultSet rs) throws SQLException {
                        ColumnGetter<?>[] rsColumnGetters = this.rsColumnGetters;

                        if (rsColumnGetters == null) {
                            rsColumnGetters = initColumnGetter(rs);
                            rsColumnCount = rsColumnGetters.length - 1;
                            this.rsColumnGetters = rsColumnGetters;
                        }

                        final Object[] row = new Object[rsColumnCount];

                        for (int i = 0; i < rsColumnCount;) {
                            row[i] = rsColumnGetters[++i].apply(i, rs);
                        }

                        return row;
                    }
                };
            }

            /**
             * Don't cache or reuse the returned {@code RowMapper} instance.
             *
             * @return
             */
            public RowMapper<List<Object>> toList() {
                // setDefaultColumnGetter();

                return new RowMapper<List<Object>>() {
                    private volatile int rsColumnCount = -1;
                    private volatile ColumnGetter<?>[] rsColumnGetters = null;

                    @Override
                    public List<Object> apply(ResultSet rs) throws SQLException {
                        ColumnGetter<?>[] rsColumnGetters = this.rsColumnGetters;

                        if (rsColumnGetters == null) {
                            rsColumnGetters = initColumnGetter(rs);
                            rsColumnCount = rsColumnGetters.length - 1;
                            this.rsColumnGetters = rsColumnGetters;
                        }

                        final List<Object> row = new ArrayList<>(rsColumnCount);

                        for (int i = 0; i < rsColumnCount;) {
                            row.add(rsColumnGetters[++i].apply(i, rs));
                        }

                        return row;
                    }
                };
            }
        }
    }

    /**
     * The Interface BiRowMapper.
     *
     * @param <T>
     */
    public interface BiRowMapper<T> extends Throwables.BiFunction<ResultSet, List<String>, T, SQLException> {

        /** The Constant GET_BOOLEAN. */
        BiRowMapper<Boolean> GET_BOOLEAN = new BiRowMapper<Boolean>() {
            @Override
            public Boolean apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                return rs.getBoolean(1);
            }
        };

        /** The Constant GET_BYTE. */
        BiRowMapper<Byte> GET_BYTE = new BiRowMapper<Byte>() {
            @Override
            public Byte apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                return rs.getByte(1);
            }
        };

        /** The Constant GET_SHORT. */
        BiRowMapper<Short> GET_SHORT = new BiRowMapper<Short>() {
            @Override
            public Short apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                return rs.getShort(1);
            }
        };

        /** The Constant GET_INT. */
        BiRowMapper<Integer> GET_INT = new BiRowMapper<Integer>() {
            @Override
            public Integer apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                return rs.getInt(1);
            }
        };

        /** The Constant GET_LONG. */
        BiRowMapper<Long> GET_LONG = new BiRowMapper<Long>() {
            @Override
            public Long apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                return rs.getLong(1);
            }
        };

        /** The Constant GET_FLOAT. */
        BiRowMapper<Float> GET_FLOAT = new BiRowMapper<Float>() {
            @Override
            public Float apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                return rs.getFloat(1);
            }
        };

        /** The Constant GET_DOUBLE. */
        BiRowMapper<Double> GET_DOUBLE = new BiRowMapper<Double>() {
            @Override
            public Double apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                return rs.getDouble(1);
            }
        };

        /** The Constant GET_BIG_DECIMAL. */
        BiRowMapper<BigDecimal> GET_BIG_DECIMAL = new BiRowMapper<BigDecimal>() {
            @Override
            public BigDecimal apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                return rs.getBigDecimal(1);
            }
        };

        /** The Constant GET_STRING. */
        BiRowMapper<String> GET_STRING = new BiRowMapper<String>() {
            @Override
            public String apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                return rs.getString(1);
            }
        };

        /** The Constant GET_DATE. */
        BiRowMapper<Date> GET_DATE = new BiRowMapper<Date>() {
            @Override
            public Date apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                return rs.getDate(1);
            }
        };

        /** The Constant GET_TIME. */
        BiRowMapper<Time> GET_TIME = new BiRowMapper<Time>() {
            @Override
            public Time apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                return rs.getTime(1);
            }
        };

        /** The Constant GET_TIMESTAMP. */
        BiRowMapper<Timestamp> GET_TIMESTAMP = new BiRowMapper<Timestamp>() {
            @Override
            public Timestamp apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                return rs.getTimestamp(1);
            }
        };

        /** The Constant TO_ARRAY. */
        BiRowMapper<Object[]> TO_ARRAY = new BiRowMapper<Object[]>() {
            @Override
            public Object[] apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                final int columnCount = columnLabels.size();
                final Object[] result = new Object[columnCount];

                for (int i = 1; i <= columnCount; i++) {
                    result[i - 1] = JdbcUtil.getColumnValue(rs, i);
                }

                return result;
            }
        };

        /** The Constant TO_LIST. */
        BiRowMapper<List<Object>> TO_LIST = new BiRowMapper<List<Object>>() {
            @Override
            public List<Object> apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                final int columnCount = columnLabels.size();
                final List<Object> result = new ArrayList<>(columnCount);

                for (int i = 1; i <= columnCount; i++) {
                    result.add(JdbcUtil.getColumnValue(rs, i));
                }

                return result;
            }
        };

        /** The Constant TO_MAP. */
        BiRowMapper<Map<String, Object>> TO_MAP = new BiRowMapper<Map<String, Object>>() {
            @Override
            public Map<String, Object> apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                final int columnCount = columnLabels.size();
                final Map<String, Object> result = new HashMap<>(columnCount);

                for (int i = 1; i <= columnCount; i++) {
                    result.put(columnLabels.get(i - 1), JdbcUtil.getColumnValue(rs, i));
                }

                return result;
            }
        };

        /** The Constant TO_LINKED_HASH_MAP. */
        BiRowMapper<Map<String, Object>> TO_LINKED_HASH_MAP = new BiRowMapper<Map<String, Object>>() {
            @Override
            public Map<String, Object> apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                final int columnCount = columnLabels.size();
                final Map<String, Object> result = new LinkedHashMap<>(columnCount);

                for (int i = 1; i <= columnCount; i++) {
                    result.put(columnLabels.get(i - 1), JdbcUtil.getColumnValue(rs, i));
                }

                return result;
            }
        };

        BiRowMapper<EntityId> TO_ENTITY_ID = new BiRowMapper<EntityId>() {
            @SuppressWarnings("deprecation")
            @Override
            public EntityId apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                final int columnCount = columnLabels.size();
                final Seid entityId = Seid.of(N.EMPTY_STRING);

                for (int i = 1; i <= columnCount; i++) {
                    entityId.set(columnLabels.get(i - 1), JdbcUtil.getColumnValue(rs, i));
                }

                return entityId;
            }
        };

        /**
         *
         * @param rs
         * @param columnLabels
         * @return generally should not return {@code null}.
         * @throws SQLException the SQL exception
         */
        @Override
        T apply(ResultSet rs, List<String> columnLabels) throws SQLException;

        /**
         * Don't cache or reuse the returned {@code BiRowMapper} instance.
         *
         * @param <T>
         * @param targetClass
         * @return
         */
        static <T> BiRowMapper<T> to(Class<? extends T> targetClass) {
            return to(targetClass, false);
        }

        /**
         * Don't cache or reuse the returned {@code BiRowMapper} instance.
         *
         * @param <T>
         * @param targetClass
         * @param ignoreNonMatchedColumns
         * @return
         */
        static <T> BiRowMapper<T> to(Class<? extends T> targetClass, final boolean ignoreNonMatchedColumns) {
            return new BiRowMapper<T>() {
                private Throwables.BiFunction<ResultSet, List<String>, T, SQLException> mapper = InternalJdbcUtil.to(targetClass, ignoreNonMatchedColumns);

                @Override
                public T apply(ResultSet rs, List<String> columnLabelList) throws SQLException {
                    return mapper.apply(rs, columnLabelList);
                }
            };
        }

        static BiRowMapperBuilder builder() {
            return new BiRowMapperBuilder();
        }

        //    static BiRowMapperBuilder builder(final int columnCount) {
        //        return new BiRowMapperBuilder(columnCount);
        //    }

        public static class BiRowMapperBuilder {
            private ColumnGetter<?> defaultColumnGetter = ColumnGetter.GET_OBJECT;
            private final Map<String, ColumnGetter<?>> columnGetterMap;

            BiRowMapperBuilder() {
                columnGetterMap = new HashMap<>(9);
            }

            //    BiRowMapperBuilder(final int columnCount) {
            //        columnGetterMap = new HashMap<>(Math.min(9, columnCount));
            //    }

            /**
             * Set default column getter function.
             *
             * @param columnGetter
             * @return
             */
            public BiRowMapperBuilder defauLt(final ColumnGetter<?> columnGetter) {
                defaultColumnGetter = columnGetter;

                return this;
            }

            public BiRowMapperBuilder getBoolean(final String columnName) {
                return get(columnName, ColumnGetter.GET_BOOLEAN);
            }

            public BiRowMapperBuilder getByte(final String columnName) {
                return get(columnName, ColumnGetter.GET_BYTE);
            }

            public BiRowMapperBuilder getShort(final String columnName) {
                return get(columnName, ColumnGetter.GET_SHORT);
            }

            public BiRowMapperBuilder getInt(final String columnName) {
                return get(columnName, ColumnGetter.GET_INT);
            }

            public BiRowMapperBuilder getLong(final String columnName) {
                return get(columnName, ColumnGetter.GET_LONG);
            }

            public BiRowMapperBuilder getFloat(final String columnName) {
                return get(columnName, ColumnGetter.GET_FLOAT);
            }

            public BiRowMapperBuilder getDouble(final String columnName) {
                return get(columnName, ColumnGetter.GET_DOUBLE);
            }

            public BiRowMapperBuilder getBigDecimal(final String columnName) {
                return get(columnName, ColumnGetter.GET_BIG_DECIMAL);
            }

            public BiRowMapperBuilder getString(final String columnName) {
                return get(columnName, ColumnGetter.GET_STRING);
            }

            public BiRowMapperBuilder getDate(final String columnName) {
                return get(columnName, ColumnGetter.GET_DATE);
            }

            public BiRowMapperBuilder getTime(final String columnName) {
                return get(columnName, ColumnGetter.GET_TIME);
            }

            public BiRowMapperBuilder getTimestamp(final String columnName) {
                return get(columnName, ColumnGetter.GET_TIMESTAMP);
            }

            public BiRowMapperBuilder get(final String columnName, final ColumnGetter<?> columnGetter) {
                N.checkArgNotNull(columnName, "columnName");
                N.checkArgNotNull(columnGetter, "columnGetter");

                columnGetterMap.put(columnName, columnGetter);

                return this;
            }

            /**
             *
             * @param columnName
             * @param columnGetter
             * @return
             * @deprecated replaced by {@link #get(String, ColumnGetter)}
             */
            @Deprecated
            public BiRowMapperBuilder column(final String columnName, final ColumnGetter<?> columnGetter) {
                return get(columnName, columnGetter);
            }

            //    /**
            //     * Set default column getter function.
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public BiRowMapperBuilder __(final ColumnGetter<?> columnGetter) {
            //        defaultColumnGetter = columnGetter;
            //
            //        return this;
            //    }
            //
            //    /**
            //     * Set column getter function for column[columnName].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public BiRowMapperBuilder __(final String columnName, final ColumnGetter<?> columnGetter) {
            //        columnGetterMap.put(columnName, columnGetter);
            //
            //        return this;
            //    }

            ColumnGetter<?>[] initColumnGetter(final List<String> columnLabelList) {
                final int rsColumnCount = columnLabelList.size();
                final ColumnGetter<?>[] rsColumnGetters = new ColumnGetter<?>[rsColumnCount + 1];
                rsColumnGetters[0] = defaultColumnGetter;

                int cnt = 0;
                ColumnGetter<?> columnGetter = null;

                for (int i = 0; i < rsColumnCount; i++) {
                    columnGetter = columnGetterMap.get(columnLabelList.get(i));

                    if (columnGetter != null) {
                        cnt++;
                    }

                    rsColumnGetters[i + 1] = columnGetter == null ? defaultColumnGetter : columnGetter;
                }

                if (cnt < columnGetterMap.size()) {
                    final List<String> tmp = new ArrayList<>(columnGetterMap.keySet());
                    tmp.removeAll(columnLabelList);
                    throw new IllegalArgumentException("ColumnGetters for " + tmp + " are not found in ResultSet columns: " + columnLabelList);
                }

                return rsColumnGetters;
            }

            public <T> BiRowMapper<T> to(final Class<? extends T> targetClass) {
                return to(targetClass, false);
            }

            public <T> BiRowMapper<T> to(final Class<? extends T> targetClass, final boolean ignoreNonMatchedColumns) {
                if (Object[].class.isAssignableFrom(targetClass)) {
                    return new BiRowMapper<T>() {
                        private volatile int rsColumnCount = -1;
                        private volatile ColumnGetter<?>[] rsColumnGetters = null;

                        @Override
                        public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                            ColumnGetter<?>[] rsColumnGetters = this.rsColumnGetters;

                            if (rsColumnGetters == null) {
                                rsColumnCount = columnLabelList.size();
                                rsColumnGetters = initColumnGetter(columnLabelList);
                                this.rsColumnGetters = rsColumnGetters;
                            }

                            final Object[] a = Array.newInstance(targetClass.getComponentType(), rsColumnCount);

                            for (int i = 0; i < rsColumnCount;) {
                                a[i] = rsColumnGetters[++i].apply(i, rs);
                            }

                            return (T) a;
                        }
                    };
                } else if (List.class.isAssignableFrom(targetClass)) {
                    return new BiRowMapper<T>() {
                        private final boolean isListOrArrayList = targetClass.equals(List.class) || targetClass.equals(ArrayList.class);

                        private volatile int rsColumnCount = -1;
                        private volatile ColumnGetter<?>[] rsColumnGetters = null;

                        @Override
                        public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                            ColumnGetter<?>[] rsColumnGetters = this.rsColumnGetters;

                            if (rsColumnGetters == null) {
                                rsColumnCount = columnLabelList.size();
                                rsColumnGetters = initColumnGetter(columnLabelList);
                                this.rsColumnGetters = rsColumnGetters;
                            }

                            final List<Object> c = isListOrArrayList ? new ArrayList<>(rsColumnCount) : (List<Object>) N.newInstance(targetClass);

                            for (int i = 0; i < rsColumnCount;) {
                                c.add(rsColumnGetters[++i].apply(i, rs));
                            }

                            return (T) c;
                        }
                    };
                } else if (Map.class.isAssignableFrom(targetClass)) {
                    return new BiRowMapper<T>() {
                        private final boolean isMapOrHashMap = targetClass.equals(Map.class) || targetClass.equals(HashMap.class);
                        private final boolean isLinkedHashMap = targetClass.equals(LinkedHashMap.class);

                        private volatile int rsColumnCount = -1;
                        private volatile ColumnGetter<?>[] rsColumnGetters = null;
                        private String[] columnLabels = null;

                        @Override
                        public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                            ColumnGetter<?>[] rsColumnGetters = this.rsColumnGetters;

                            if (rsColumnGetters == null) {
                                rsColumnCount = columnLabelList.size();
                                rsColumnGetters = initColumnGetter(columnLabelList);
                                this.rsColumnGetters = rsColumnGetters;

                                columnLabels = columnLabelList.toArray(new String[rsColumnCount]);
                            }

                            final Map<String, Object> m = isMapOrHashMap ? new HashMap<>(rsColumnCount)
                                    : (isLinkedHashMap ? new LinkedHashMap<>(rsColumnCount) : (Map<String, Object>) N.newInstance(targetClass));

                            for (int i = 0; i < rsColumnCount;) {
                                m.put(columnLabels[i], rsColumnGetters[++i].apply(i, rs));
                            }

                            return (T) m;
                        }
                    };
                } else if (ClassUtil.isEntity(targetClass)) {
                    return new BiRowMapper<T>() {
                        private final boolean isDirtyMarker = DirtyMarkerUtil.isDirtyMarker(targetClass);
                        private final EntityInfo entityInfo = ParserUtil.getEntityInfo(targetClass);

                        private volatile int rsColumnCount = -1;
                        private volatile ColumnGetter<?>[] rsColumnGetters = null;
                        private volatile String[] columnLabels = null;
                        private volatile PropInfo[] propInfos;

                        @Override
                        public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                            ColumnGetter<?>[] rsColumnGetters = this.rsColumnGetters;

                            if (rsColumnGetters == null) {
                                rsColumnCount = columnLabelList.size();
                                rsColumnGetters = initColumnGetter(columnLabelList);
                                this.rsColumnGetters = rsColumnGetters;

                                columnLabels = columnLabelList.toArray(new String[rsColumnCount]);
                                final PropInfo[] propInfos = new PropInfo[rsColumnCount];

                                final Map<String, String> column2FieldNameMap = InternalJdbcUtil.getColumn2FieldNameMap(targetClass);

                                for (int i = 0; i < rsColumnCount; i++) {
                                    propInfos[i] = entityInfo.getPropInfo(columnLabels[i]);

                                    if (propInfos[i] == null) {
                                        String fieldName = column2FieldNameMap.get(columnLabels[i]);

                                        if (N.isNullOrEmpty(fieldName)) {
                                            fieldName = column2FieldNameMap.get(columnLabels[i].toLowerCase());
                                        }

                                        if (N.notNullOrEmpty(fieldName)) {
                                            propInfos[i] = entityInfo.getPropInfo(fieldName);
                                        }
                                    }

                                    if (propInfos[i] == null) {
                                        if (ignoreNonMatchedColumns) {
                                            columnLabels[i] = null;
                                        } else {
                                            throw new IllegalArgumentException("No property in class: " + ClassUtil.getCanonicalClassName(targetClass)
                                                    + " mapping to column: " + columnLabels[i]);
                                        }
                                    } else {
                                        if (rsColumnGetters[i + 1] == ColumnGetter.GET_OBJECT) {
                                            rsColumnGetters[i + 1] = ColumnGetter.get(entityInfo.getPropInfo(columnLabels[i]).dbType);
                                        }
                                    }
                                }

                                this.propInfos = propInfos;
                            }

                            final Object entity = N.newInstance(targetClass);

                            for (int i = 0; i < rsColumnCount;) {
                                if (columnLabels[i] == null) {
                                    continue;
                                }

                                propInfos[i].setPropValue(entity, rsColumnGetters[++i].apply(i, rs));
                            }

                            if (isDirtyMarker) {
                                DirtyMarkerUtil.markDirty((DirtyMarker) entity, false);
                            }

                            return (T) entity;
                        }
                    };
                } else {
                    return new BiRowMapper<T>() {
                        private volatile int rsColumnCount = -1;
                        private volatile ColumnGetter<?>[] rsColumnGetters = null;

                        @Override
                        public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                            ColumnGetter<?>[] rsColumnGetters = this.rsColumnGetters;

                            if (rsColumnGetters == null) {
                                rsColumnCount = columnLabelList.size();
                                rsColumnGetters = initColumnGetter(columnLabelList);

                                if (rsColumnGetters[1] == ColumnGetter.GET_OBJECT) {
                                    rsColumnGetters[1] = ColumnGetter.get(N.typeOf(targetClass));
                                }

                                this.rsColumnGetters = rsColumnGetters;
                            }

                            if (rsColumnCount != 1 && (rsColumnCount = columnLabelList.size()) != 1) {
                                throw new IllegalArgumentException(
                                        "It's not supported to retrieve value from multiple columns: " + columnLabelList + " for type: " + targetClass);
                            }

                            return (T) rsColumnGetters[1].apply(1, rs);
                        }
                    };
                }
            }
        }
    }

    /**
     * Don't use {@code RowConsumer} in {@link PreparedQuery#forEach(RowConsumer)} or any place where multiple records will be consumed by it, if column labels/count are used in {@link RowConsumer#accept(ResultSet)}.
     * Consider using {@code BiRowConsumer} instead because it's more efficient to consume multiple records when column labels/count are used.
     *
     */
    public interface RowConsumer extends Throwables.Consumer<ResultSet, SQLException> {

        static final RowConsumer DO_NOTHING = rs -> {
        };

        /**
         *
         * @param rs
         * @throws SQLException the SQL exception
         */
        @Override
        void accept(ResultSet rs) throws SQLException;
    }

    /**
     * The Interface BiRowConsumer.
     */
    public interface BiRowConsumer extends Throwables.BiConsumer<ResultSet, List<String>, SQLException> {

        static final BiRowConsumer DO_NOTHING = (rs, cls) -> {
        };

        /**
         *
         * @param rs
         * @param columnLabels
         * @throws SQLException the SQL exception
         */
        @Override
        void accept(ResultSet rs, List<String> columnLabels) throws SQLException;
    }

    /**
     * Generally, the result should be filtered in database side by SQL scripts.
     * Only user {@code RowFilter/BiRowFilter} if there is a specific reason or the filter can't be done by SQL scripts in database server side.
     * Consider using {@code BiRowConsumer} instead because it's more efficient to test multiple records when column labels/count are used.
     *
     */
    public interface RowFilter extends Throwables.Predicate<ResultSet, SQLException> {

        /** The Constant ALWAYS_TRUE. */
        RowFilter ALWAYS_TRUE = new RowFilter() {
            @Override
            public boolean test(ResultSet rs) throws SQLException {
                return true;
            }
        };

        /** The Constant ALWAYS_FALSE. */
        RowFilter ALWAYS_FALSE = new RowFilter() {
            @Override
            public boolean test(ResultSet rs) throws SQLException {
                return false;
            }
        };

        /**
         *
         * @param rs
         * @return true, if successful
         * @throws SQLException the SQL exception
         */
        @Override
        boolean test(ResultSet rs) throws SQLException;
    }

    /**
     * Generally, the result should be filtered in database side by SQL scripts.
     * Only user {@code RowFilter/BiRowFilter} if there is a specific reason or the filter can't be done by SQL scripts in database server side.
     *
     */
    public interface BiRowFilter extends Throwables.BiPredicate<ResultSet, List<String>, SQLException> {

        /** The Constant ALWAYS_TRUE. */
        BiRowFilter ALWAYS_TRUE = new BiRowFilter() {
            @Override
            public boolean test(ResultSet rs, List<String> columnLabels) throws SQLException {
                return true;
            }
        };

        /** The Constant ALWAYS_FALSE. */
        BiRowFilter ALWAYS_FALSE = new BiRowFilter() {
            @Override
            public boolean test(ResultSet rs, List<String> columnLabels) throws SQLException {
                return false;
            }
        };

        /**
         *
         * @param rs
         * @param columnLabels
         * @return true, if successful
         * @throws SQLException the SQL exception
         */
        @Override
        boolean test(ResultSet rs, List<String> columnLabels) throws SQLException;
    }

    private static final ObjectPool<Type<?>, ColumnGetter<?>> COLUMN_GETTER_POOL = new ObjectPool<>(1024);

    static {
        COLUMN_GETTER_POOL.put(N.typeOf(boolean.class), ColumnGetter.GET_BOOLEAN);
        COLUMN_GETTER_POOL.put(N.typeOf(Boolean.class), ColumnGetter.GET_BOOLEAN);
        COLUMN_GETTER_POOL.put(N.typeOf(byte.class), ColumnGetter.GET_BYTE);
        COLUMN_GETTER_POOL.put(N.typeOf(Byte.class), ColumnGetter.GET_BYTE);
        COLUMN_GETTER_POOL.put(N.typeOf(short.class), ColumnGetter.GET_SHORT);
        COLUMN_GETTER_POOL.put(N.typeOf(Short.class), ColumnGetter.GET_SHORT);
        COLUMN_GETTER_POOL.put(N.typeOf(int.class), ColumnGetter.GET_INT);
        COLUMN_GETTER_POOL.put(N.typeOf(Integer.class), ColumnGetter.GET_INT);
        COLUMN_GETTER_POOL.put(N.typeOf(long.class), ColumnGetter.GET_LONG);
        COLUMN_GETTER_POOL.put(N.typeOf(Long.class), ColumnGetter.GET_LONG);
        COLUMN_GETTER_POOL.put(N.typeOf(float.class), ColumnGetter.GET_FLOAT);
        COLUMN_GETTER_POOL.put(N.typeOf(Float.class), ColumnGetter.GET_FLOAT);
        COLUMN_GETTER_POOL.put(N.typeOf(double.class), ColumnGetter.GET_DOUBLE);
        COLUMN_GETTER_POOL.put(N.typeOf(Double.class), ColumnGetter.GET_DOUBLE);
        COLUMN_GETTER_POOL.put(N.typeOf(BigDecimal.class), ColumnGetter.GET_BIG_DECIMAL);
        COLUMN_GETTER_POOL.put(N.typeOf(String.class), ColumnGetter.GET_STRING);
        COLUMN_GETTER_POOL.put(N.typeOf(java.sql.Date.class), ColumnGetter.GET_DATE);
        COLUMN_GETTER_POOL.put(N.typeOf(java.sql.Time.class), ColumnGetter.GET_TIME);
        COLUMN_GETTER_POOL.put(N.typeOf(java.sql.Timestamp.class), ColumnGetter.GET_TIMESTAMP);
        COLUMN_GETTER_POOL.put(N.typeOf(Object.class), ColumnGetter.GET_OBJECT);
    }

    public interface ColumnGetter<V> {

        ColumnGetter<Object> GET_OBJECT = new ColumnGetter<Object>() {
            @Override
            public Object apply(final int columnIndex, final ResultSet rs) throws SQLException {
                return InternalJdbcUtil.getColumnValue(rs, columnIndex);
            }
        };

        ColumnGetter<Boolean> GET_BOOLEAN = new ColumnGetter<Boolean>() {
            @Override
            public Boolean apply(final int columnIndex, final ResultSet rs) throws SQLException {
                return rs.getBoolean(columnIndex);
            }
        };

        ColumnGetter<Byte> GET_BYTE = new ColumnGetter<Byte>() {
            @Override
            public Byte apply(final int columnIndex, final ResultSet rs) throws SQLException {
                return rs.getByte(columnIndex);
            }
        };

        ColumnGetter<Short> GET_SHORT = new ColumnGetter<Short>() {
            @Override
            public Short apply(final int columnIndex, final ResultSet rs) throws SQLException {
                return rs.getShort(columnIndex);
            }
        };

        ColumnGetter<Integer> GET_INT = new ColumnGetter<Integer>() {
            @Override
            public Integer apply(final int columnIndex, final ResultSet rs) throws SQLException {
                return rs.getInt(columnIndex);
            }
        };

        ColumnGetter<Long> GET_LONG = new ColumnGetter<Long>() {
            @Override
            public Long apply(final int columnIndex, final ResultSet rs) throws SQLException {
                return rs.getLong(columnIndex);
            }
        };

        ColumnGetter<Float> GET_FLOAT = new ColumnGetter<Float>() {
            @Override
            public Float apply(final int columnIndex, final ResultSet rs) throws SQLException {
                return rs.getFloat(columnIndex);
            }
        };

        ColumnGetter<Double> GET_DOUBLE = new ColumnGetter<Double>() {
            @Override
            public Double apply(final int columnIndex, final ResultSet rs) throws SQLException {
                return rs.getDouble(columnIndex);
            }
        };

        ColumnGetter<BigDecimal> GET_BIG_DECIMAL = new ColumnGetter<BigDecimal>() {
            @Override
            public BigDecimal apply(final int columnIndex, final ResultSet rs) throws SQLException {
                return rs.getBigDecimal(columnIndex);
            }
        };

        ColumnGetter<String> GET_STRING = new ColumnGetter<String>() {
            @Override
            public String apply(final int columnIndex, final ResultSet rs) throws SQLException {
                return rs.getString(columnIndex);
            }
        };

        ColumnGetter<java.sql.Date> GET_DATE = new ColumnGetter<java.sql.Date>() {
            @Override
            public java.sql.Date apply(final int columnIndex, final ResultSet rs) throws SQLException {
                return rs.getDate(columnIndex);
            }
        };

        ColumnGetter<java.sql.Time> GET_TIME = new ColumnGetter<java.sql.Time>() {
            @Override
            public java.sql.Time apply(final int columnIndex, final ResultSet rs) throws SQLException {
                return rs.getTime(columnIndex);
            }
        };

        ColumnGetter<java.sql.Timestamp> GET_TIMESTAMP = new ColumnGetter<java.sql.Timestamp>() {
            @Override
            public java.sql.Timestamp apply(final int columnIndex, final ResultSet rs) throws SQLException {
                return rs.getTimestamp(columnIndex);
            }
        };

        /**
         *
         * @param columnIndex start from 1
         * @param rs
         * @return
         * @throws SQLException
         */
        V apply(int columnIndex, ResultSet rs) throws SQLException;

        static <T> ColumnGetter<T> get(final Class<? extends T> cls) {
            return get(N.typeOf(cls));
        }

        static <T> ColumnGetter<T> get(final Type<? extends T> type) {
            ColumnGetter<?> columnGetter = COLUMN_GETTER_POOL.get(type);

            if (columnGetter == null) {
                columnGetter = new ColumnGetter<T>() {
                    @Override
                    public T apply(int columnIndex, ResultSet rs) throws SQLException {
                        return type.get(rs, columnIndex);
                    }
                };

                COLUMN_GETTER_POOL.put(type, columnGetter);
            }

            return (ColumnGetter<T>) columnGetter;
        }
    }

    /**
     * This interface is designed to share/manager SQL queries by Java APIs/methods with static parameter types and return type, while hiding the SQL scripts.
     * It's a gift from nature and created by thoughts.
     *
     * <br />
     * Note: Setting parameters by 'ParametersSetter' or Retrieving result/record by 'ResultExtractor/BiResultExtractor/RowMapper/BiRowMapper' is not enabled at present.
     *
     * <br />
     *
     * <li>The SQL operations/methods should be annotated with SQL scripts by {@code @Select/@Insert/@Update/@Delete/@NamedSelect/@NamedInsert/@NamedUpdate/@NamedDelete}.</li>
     *
     * <li>The Order of the parameters in the method should be consistent with parameter order in SQL scripts for parameterized SQL.
     * For named parameterized SQL, the parameters must be binded with names through {@code @Bind}, or {@code Map/Entity} with getter/setter methods.</li>
     *
     * <li>SQL parameters can be set through input method parameters(by multiple parameters or a {@code Collection}, or a {@code Map/Entity} for named sql), or by {@code JdbcUtil.ParametersSetter<PreparedQuery/PreparedCallabeQuery...>}.</li>
     *
     * <li>{@code ResultExtractor/BiResultExtractor/RowMapper/BiRowMapper} can be specified by the last parameter of the method.</li>
     *
     * <li>The return type of the method must be same as the return type of {@code ResultExtractor/BiResultExtractor} if it's specified by the last parameter of the method.</li>
     *
     * <li>The return type of update/delete operations only can int/Integer/long/Long/boolean/Boolean/void. If it's long/Long, {@code PreparedQuery#largeUpdate()} will be called,
     * otherwise, {@code PreparedQuery#update()} will be called.</li>
     *
     * <li>Remember declaring {@code throws SQLException} in the method.</li>
     * <br />
     * <li>Which underline {@code PreparedQuery/PreparedCallableQuery} method to call for SQL methods/operations annotated with {@code @Select/@NamedSelect}:
     * <ul>
     *   <li>If {@code ResultExtractor/BiResultExtractor} is specified by the last parameter of the method, {@code PreparedQuery#query(ResultExtractor/BiResultExtractor)} will be called.</li>
     *   <li>Or else if {@code RowMapper/BiRowMapper} is specified by the last parameter of the method:</li>
     *      <ul>
     *          <li>If the return type of the method is {@code List} and one of below conditions is matched, {@code PreparedQuery#list(RowMapper/BiRowMapper)} will be called:</li>
     *          <ul>
     *              <li>The return type of the method is raw {@code List} without parameterized type, and the method name doesn't start with {@code "get"/"findFirst"/"findOne"}.</li>
     *          </ul>
     *          <ul>
     *              <li>The last parameter type is raw {@code RowMapper/BiRowMapper} without parameterized type, and the method name doesn't start with {@code "get"/"findFirst"/"findOne"}.</li>
     *          </ul>
     *          <ul>
     *              <li>The return type of the method is generic {@code List} with parameterized type and The last parameter type is generic {@code RowMapper/BiRowMapper} with parameterized types, but They're not same.</li>
     *          </ul>
     *      </ul>
     *      <ul>
     *          <li>Or else if the return type of the method is {@code ExceptionalStream/Stream}, {@code PreparedQuery#stream(RowMapper/BiRowMapper)} will be called.</li>
     *      </ul>
     *      <ul>
     *          <li>Or else if the return type of the method is {@code Optional}, {@code PreparedQuery#findFirst(RowMapper/BiRowMapper)} will be called.</li>
     *      </ul>
     *      <ul>
     *          <li>Or else, {@code PreparedQuery#findFirst(RowMapper/BiRowMapper).orElse(N.defaultValueOf(returnType))} will be called.</li>
     *      </ul>
     *   <li>Or else:</li>
     *      <ul>
     *          <li>If the return type of the method is {@code DataSet}, {@code PreparedQuery#query()} will be called.</li>
     *      </ul>
     *      <ul>
     *          <li>Or else if the return type of the method is {@code ExceptionalStream/Stream}, {@code PreparedQuery#stream(Class)} will be called.</li>
     *      </ul>
     *      <ul>
     *          <li>Or else if the return type of the method is {@code Map} or {@code Entity} class with {@code getter/setter} methods, {@code PreparedQuery#findFirst(Class).orNull()} will be called.</li>
     *      </ul>
     *      <ul>
     *          <li>Or else if the return type of the method is {@code Optional}:</li>
     *          <ul>
     *              <li>If the value type of {@code Optional} is {@code Map}, or {@code Entity} class with {@code getter/setter} methods, or {@code List}, or {@code Object[]}, {@code PreparedQuery#findFirst(Class)} will be called.</li>
     *          </ul>
     *          <ul>
     *              <li>Or else, {@code PreparedQuery#queryForSingleNonNull(Class)} will be called.</li>
     *          </ul>
     *      </ul>
     *      <ul>
     *          <li>Or else if the return type of the method is {@code Nullable}:</li>
     *          <ul>
     *              <li>If the value type of {@code Nullable} is {@code Map}, or {@code Entity} class with {@code getter/setter} methods, or {@code List}, or {@code Object[]}, {@code PreparedQuery#findFirst(Class)} will be called.</li>
     *          </ul>
     *          <ul>
     *              <li>Or else, {@code PreparedQuery#queryForSingleResult(Class)} will be called.</li>
     *          </ul>
     *      </ul>
     *      <ul>
     *          <li>Or else if the return type of the method is {@code OptionalBoolean/Byte/.../Double}, {@code PreparedQuery#queryForBoolean/Byte/...Double()} will called.</li>
     *      </ul>
     *      <ul>
     *          <li>Or else if the return type of the method is {@code List}, and the method name doesn't start with {@code "get"/"findFirst"/"findOne"}, {@code PreparedQuery#list(Class)} will be called.</li>
     *      </ul>
     *      <ul>
     *          <li>Or else, {@code PreparedQuery#queryForSingleResult(Class).orElse(N.defaultValueOf(returnType)} will be called.</li>
     *      </ul>
     * </ul>
     *
     * <br />
     * <br />
     *
     * Here is a simple {@code UserDao} sample.
     *
     * <pre>
     * <code>
     * public static interface UserDao extends JdbcUtil.CrudDao<User, Long, SQLBuilder.PSC> {
     *     &#064NamedInsert("INSERT INTO user (id, first_name, last_name, email) VALUES (:id, :firstName, :lastName, :email)")
     *     void insertWithId(User user) throws SQLException;
     *
     *     &#064NamedUpdate("UPDATE user SET first_name = :firstName, last_name = :lastName WHERE id = :id")
     *     int updateFirstAndLastName(@Bind("firstName") String newFirstName, @Bind("lastName") String newLastName, @Bind("id") long id) throws SQLException;
     *
     *     &#064NamedSelect("SELECT first_name, last_name FROM user WHERE id = :id")
     *     User getFirstAndLastNameBy(@Bind("id") long id) throws SQLException;
     *
     *     &#064NamedSelect("SELECT id, first_name, last_name, email FROM user")
     *     Stream<User> allUsers() throws SQLException;
     * }
     * </code>
     * </pre>
     *
     * Here is the generate way to work with transaction started by {@code SQLExecutor}.
     *
     * <pre>
     * <code>
     * static final UserDao userDao = Dao.newInstance(UserDao.class, dataSource);
     * ...
     *
     * final SQLTransaction tran = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
     *
     * try {
     *      userDao.getById(id);
     *      userDao.update(...);
     *      // more...
     *
     *      tran.commit();
     * } finally {
     *      // The connection will be automatically closed after the transaction is committed or rolled back.
     *      tran.rollbackIfNotCommitted();
     * }
     * </code>
     * </pre>
     *
     * @param <T>
     * @param <SB> {@code SQLBuilder} used to generate sql scripts. Only can be {@code SQLBuilder.PSC/PAC/PLC}
     * @param <TD>
     * @see {@link com.landawn.abacus.condition.ConditionFactory}
     * @see {@link com.landawn.abacus.condition.ConditionFactory.CF}
     * @see JdbcUtil#prepareQuery(javax.sql.DataSource, String)
     * @see JdbcUtil#prepareNamedQuery(javax.sql.DataSource, String)
     * @see JdbcUtil#beginTransaction(javax.sql.DataSource, IsolationLevel, boolean)
     * @see Dao
     * @See CrudDao
     * @see SQLExecutor.Mapper
     * @see com.landawn.abacus.annotation.AccessFieldByMethod
     * @see com.landawn.abacus.condition.ConditionFactory
     * @see com.landawn.abacus.condition.ConditionFactory.CF
     */
    public interface Dao<T, SB extends SQLBuilder, TD extends Dao<T, SB, TD>> {
        /**
         * The Interface Select.
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        public static @interface Select {

            /**
             *
             * @return
             * @deprecated using sql="SELECT ... FROM ..." for explicit call.
             */
            @Deprecated
            String value()

            default "";

            /**
             *
             * @return
             */
            String sql()

            default "";

            /**
             *
             * @return
             */
            int fetchSize() default -1;

            /**
             * Unit is seconds.
             *
             * @return
             */
            int queryTimeout() default -1;
        }

        /**
         * The Interface Insert.
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        public static @interface Insert {

            /**
             *
             * @return
             * @deprecated using sql="SELECT ... FROM ..." for explicit call.
             */
            @Deprecated
            String value()

            default "";

            /**
             *
             * @return
             */
            String sql() default "";

            /**
             * Unit is seconds.
             *
             * @return
             */
            int queryTimeout() default -1;
        }

        /**
         * The Interface Update.
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        public static @interface Update {

            /**
             *
             * @return
             * @deprecated using sql="SELECT ... FROM ..." for explicit call.
             */
            @Deprecated
            String value()

            default "";

            /**
             *
             * @return
             */
            String sql() default "";

            /**
             * Unit is seconds.
             *
             * @return
             */
            int queryTimeout() default -1;
        }

        /**
         * The Interface Delete.
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        public static @interface Delete {

            /**
             *
             * @return
             * @deprecated using sql="SELECT ... FROM ..." for explicit call.
             */
            @Deprecated
            String value()

            default "";

            /**
             *
             * @return
             */
            String sql() default "";

            /**
             * Unit is seconds.
             *
             * @return
             */
            int queryTimeout() default -1;
        }

        /**
         * The Interface NamedSelect.
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        public static @interface NamedSelect {

            /**
             *
             * @return
             * @deprecated using sql="SELECT ... FROM ..." for explicit call.
             */
            @Deprecated
            String value()

            default "";

            /**
             *
             * @return
             */
            String sql()

            default "";

            /**
             *
             * @return
             */
            int fetchSize() default -1;

            /**
             * Unit is seconds.
             *
             * @return
             */
            int queryTimeout() default -1;
        }

        /**
         * The Interface NamedInsert.
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        public static @interface NamedInsert {

            /**
             *
             * @return
             * @deprecated using sql="SELECT ... FROM ..." for explicit call.
             */
            @Deprecated
            String value()

            default "";

            /**
             *
             * @return
             */
            String sql() default "";

            /**
             *
             * @return
             */
            boolean isBatch() default false;

            /**
             *
             * @return
             */
            int batchSize() default 0;

            /**
             * Unit is seconds.
             *
             * @return
             */
            int queryTimeout() default -1;
        }

        /**
         * The Interface NamedUpdate.
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        public static @interface NamedUpdate {

            /**
             *
             * @return
             * @deprecated using sql="SELECT ... FROM ..." for explicit call.
             */
            @Deprecated
            String value()

            default "";

            /**
             *
             * @return
             */
            String sql() default "";

            /**
             *
             * @return
             */
            boolean isBatch() default false;

            /**
             *
             * @return
             */
            int batchSize() default 0;

            /**
             * Unit is seconds.
             *
             * @return
             */
            int queryTimeout() default -1;
        }

        /**
         * The Interface NamedDelete.
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        public static @interface NamedDelete {

            /**
             *
             * @return
             * @deprecated using sql="SELECT ... FROM ..." for explicit call.
             */
            @Deprecated
            String value()

            default "";

            /**
             *
             * @return
             */
            String sql() default "";

            /**
             *
             * @return
             */
            boolean isBatch() default false;

            /**
             *
             * @return
             */
            int batchSize() default 0;

            /**
             * Unit is seconds.
             *
             * @return
             */
            int queryTimeout() default -1;
        }

        /**
         * The Interface Update.
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        public static @interface Call {

            /**
             *
             * @return
             * @deprecated using sql="call " for explicit call.
             */
            @Deprecated
            String value()

            default "";

            /**
             *
             * @return
             */
            String sql() default "";

            /**
             * Unit is seconds.
             *
             * @return
             */
            int queryTimeout() default -1;
        }

        /**
         * It's only for methods with default implementation in {@code Dao} interfaces. Don't use it for the abstract methods.
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        public static @interface Sqls {

            /**
             *
             * @return
             */
            String[] value() default "";
        }

        /**
         * The Interface Bind.
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.PARAMETER)
        public static @interface Bind {

            /**
             *
             * @return
             */
            String value() default "";
        }

        /**
         *
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        public static @interface Transactional {
            Propagation propagation() default Propagation.REQUIRED;
        }

        /**
         * It's only for methods with default implementation in {@code Dao} interfaces. Don't use it for the abstract methods.
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(value = { ElementType.METHOD, ElementType.TYPE })
        public static @interface PerfLog {
            /**
             * start to log performance for sql if the execution time >= the specified(or default) execution time in milliseconds.
             *
             * @return
             */
            long minExecutionTimeForSql() default 1000;

            /**
             * start to log performance for Dao operation/method if the execution time >= the specified(or default) execution time in milliseconds.
             * @return
             */
            long minExecutionTimeForOperation() default 3000;
        }

        /**
         *
         * @return
         */
        Class<T> targetEntityClass();

        /**
         *
         * @return
         */
        javax.sql.DataSource dataSource();

        // SQLExecutor sqlExecutor();

        SQLMapper sqlMapper();

        Executor executor();

        //    /**
        //     *
        //     * @param isolationLevel
        //     * @return
        //     * @throws UncheckedSQLException
        //     */
        //    default SQLTransaction beginTransaction(final IsolationLevel isolationLevel) throws UncheckedSQLException {
        //        return beginTransaction(isolationLevel, false);
        //    }
        //
        //    /**
        //     * The connection opened in the transaction will be automatically closed after the transaction is committed or rolled back.
        //     * DON'T close it again by calling the close method.
        //     * <br />
        //     * <br />
        //     * The transaction will be shared cross the instances of {@code SQLExecutor/Dao} by the methods called in the same thread with same {@code DataSource}.
        //     *
        //     * <br />
        //     * <br />
        //     *
        //     * The general programming way with SQLExecutor/Dao is to execute sql scripts(generated by SQLBuilder) with array/list/map/entity by calling (batch)insert/update/delete/query/... methods.
        //     * If Transaction is required, it can be started:
        //     *
        //     * <pre>
        //     * <code>
        //     *   final SQLTransaction tran = someDao.beginTransaction(IsolationLevel.READ_COMMITTED);
        //     *   try {
        //     *       // sqlExecutor.insert(...);
        //     *       // sqlExecutor.update(...);
        //     *       // sqlExecutor.query(...);
        //     *
        //     *       tran.commit();
        //     *   } finally {
        //     *       // The connection will be automatically closed after the transaction is committed or rolled back.
        //     *       tran.rollbackIfNotCommitted();
        //     *   }
        //     * </code>
        //     * </pre>
        //     *
        //     * @param isolationLevel
        //     * @param forUpdateOnly
        //     * @return
        //     * @throws UncheckedSQLException
        //     * @see {@link SQLExecutor#beginTransaction(IsolationLevel, boolean)}
        //     */
        //    default SQLTransaction beginTransaction(final IsolationLevel isolationLevel, final boolean forUpdateOnly) throws UncheckedSQLException {
        //        N.checkArgNotNull(isolationLevel, "isolationLevel");
        //
        //        final javax.sql.DataSource ds = dataSource();
        //        SQLTransaction tran = SQLTransaction.getTransaction(ds);
        //
        //        if (tran == null) {
        //            Connection conn = null;
        //            boolean noException = false;
        //
        //            try {
        //                conn = getConnection(ds);
        //                tran = new SQLTransaction(ds, conn, isolationLevel, true, true);
        //                tran.incrementAndGetRef(isolationLevel, forUpdateOnly);
        //
        //                noException = true;
        //            } catch (SQLException e) {
        //                throw new UncheckedSQLException(e);
        //            } finally {
        //                if (noException == false) {
        //                    JdbcUtil.releaseConnection(conn, ds);
        //                }
        //            }
        //
        //            logger.info("Create a new SQLTransaction(id={})", tran.id());
        //            SQLTransaction.putTransaction(ds, tran);
        //        } else {
        //            logger.info("Reusing the existing SQLTransaction(id={})", tran.id());
        //            tran.incrementAndGetRef(isolationLevel, forUpdateOnly);
        //        }
        //
        //        return tran;
        //    }

        /**
         *
         * @param query
         * @return
         * @throws SQLException
         */
        default PreparedQuery prepareQuery(final String query) throws SQLException {
            return JdbcUtil.prepareQuery(dataSource(), query);
        }

        /**
         *
         * @param query
         * @param generateKeys
         * @return
         * @throws SQLException
         */
        default PreparedQuery prepareQuery(final String query, final boolean generateKeys) throws SQLException {
            return JdbcUtil.prepareQuery(dataSource(), query, generateKeys);
        }

        /**
         *
         * @param query
         * @param returnColumnIndexes
         * @return
         * @throws SQLException
         */
        default PreparedQuery prepareQuery(final String query, final int[] returnColumnIndexes) throws SQLException {
            return JdbcUtil.prepareQuery(dataSource(), query, returnColumnIndexes);
        }

        /**
         *
         * @param query
         * @param returnColumnIndexes
         * @return
         * @throws SQLException
         */
        default PreparedQuery prepareQuery(final String query, final String[] returnColumnNames) throws SQLException {
            return JdbcUtil.prepareQuery(dataSource(), query, returnColumnNames);
        }

        /**
         *
         * @param sql
         * @param stmtCreator
         * @return
         * @throws SQLException
         */
        default PreparedQuery prepareQuery(final String sql, final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator)
                throws SQLException {
            return JdbcUtil.prepareQuery(dataSource(), sql, stmtCreator);
        }

        /**
         *
         * @param namedQuery
         * @return
         * @throws SQLException
         */
        default NamedQuery prepareNamedQuery(final String namedQuery) throws SQLException {
            return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery);
        }

        /**
         *
         * @param namedQuery
         * @param generateKeys
         * @return
         * @throws SQLException
         */
        default NamedQuery prepareNamedQuery(final String namedQuery, final boolean generateKeys) throws SQLException {
            return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, generateKeys);
        }

        /**
         *
         * @param namedQuery
         * @param returnColumnIndexes
         * @return
         * @throws SQLException
         */
        default NamedQuery prepareNamedQuery(final String namedQuery, final int[] returnColumnIndexes) throws SQLException {
            return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, returnColumnIndexes);
        }

        /**
         *
         * @param namedQuery
         * @param returnColumnNames
         * @return
         * @throws SQLException
         */
        default NamedQuery prepareNamedQuery(final String namedQuery, final String[] returnColumnNames) throws SQLException {
            return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, returnColumnNames);
        }

        /**
         *
         * @param namedQuery
         * @param stmtCreator
         * @return
         * @throws SQLException
         */
        default NamedQuery prepareNamedQuery(final String namedQuery,
                final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
            return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, stmtCreator);
        }

        /**
         *
         * @param namedSQL the named query
         * @return
         * @throws SQLException
         */
        default NamedQuery prepareNamedQuery(final NamedSQL namedSQL) throws SQLException {
            return JdbcUtil.prepareNamedQuery(dataSource(), namedSQL);
        }

        /**
         *
         * @param namedSQL the named query
         * @param generateKeys
         * @return
         * @throws SQLException
         */
        default NamedQuery prepareNamedQuery(final NamedSQL namedSQL, final boolean generateKeys) throws SQLException {
            return JdbcUtil.prepareNamedQuery(dataSource(), namedSQL, generateKeys);
        }

        /**
         *
         * @param namedQuery
         * @param returnColumnIndexes
         * @return
         * @throws SQLException
         */
        default NamedQuery prepareNamedQuery(final NamedSQL namedQuery, final int[] returnColumnIndexes) throws SQLException {
            return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, returnColumnIndexes);
        }

        /**
         *
         * @param namedQuery
         * @param returnColumnNames
         * @return
         * @throws SQLException
         */
        default NamedQuery prepareNamedQuery(final NamedSQL namedQuery, final String[] returnColumnNames) throws SQLException {
            return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, returnColumnNames);
        }

        /**
         *
         * @param namedSQL the named query
         * @param stmtCreator
         * @return
         * @throws SQLException
         */
        default NamedQuery prepareNamedQuery(final NamedSQL namedSQL,
                final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
            return JdbcUtil.prepareNamedQuery(dataSource(), namedSQL, stmtCreator);
        }

        /**
         *
         * @param query
         * @return
         * @throws SQLException
         */
        default PreparedCallableQuery prepareCallableQuery(final String query) throws SQLException {
            return JdbcUtil.prepareCallableQuery(dataSource(), query);
        }

        /**
         *
         * @param sql
         * @param stmtCreator
         * @return
         * @throws SQLException
         */
        default PreparedCallableQuery prepareCallableQuery(final String sql,
                final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator) throws SQLException {
            return JdbcUtil.prepareCallableQuery(dataSource(), sql, stmtCreator);
        }

        /**
         *
         * @param entityToSave
         * @return
         * @throws SQLException the SQL exception
         */
        void save(final T entityToSave) throws SQLException;

        /**
         *
         * @param entityToSave
         * @param propNamesToSave
         * @return
         * @throws SQLException the SQL exception
         */
        void save(final T entityToSave, final Collection<String> propNamesToSave) throws SQLException;

        /**
         *
         * @param namedInsertSQL
         * @param entityToSave
         * @return
         * @throws SQLException the SQL exception
         */
        void save(final String namedInsertSQL, final T entityToSave) throws SQLException;

        /**
         * Insert the specified entities to database by batch.
         *
         * @param entitiesToSave
         * @return
         * @throws SQLException the SQL exception
         * @see CrudDao#batchInsert(Collection)
         */
        default void saveAll(final Collection<? extends T> entitiesToSave) throws SQLException {
            saveAll(entitiesToSave, JdbcUtil.DEFAULT_BATCH_SIZE);
        }

        /**
         * Insert the specified entities to database by batch.
         *
         * @param entitiesToSave
         * @param batchSize
         * @return
         * @throws SQLException the SQL exception
         * @see CrudDao#batchInsert(Collection)
         */
        void saveAll(final Collection<? extends T> entitiesToSave, final int batchSize) throws SQLException;

        /**
         * Insert the specified entities to database by batch.
         *
         * @param namedInsertSQL
         * @param entitiesToSave
         * @return
         * @throws SQLException the SQL exception
         * @see CrudDao#batchInsert(Collection)
         */
        @Beta
        default void saveAll(final String namedInsertSQL, final Collection<? extends T> entitiesToSave) throws SQLException {
            saveAll(namedInsertSQL, entitiesToSave, JdbcUtil.DEFAULT_BATCH_SIZE);
        }

        /**
         * Insert the specified entities to database by batch.
         *
         * @param namedInsertSQL
         * @param entitiesToSave
         * @param batchSize
         * @return
         * @throws SQLException the SQL exception
         * @see CrudDao#batchInsert(Collection)
         */
        @Beta
        void saveAll(final String namedInsertSQL, final Collection<? extends T> entitiesToSave, final int batchSize) throws SQLException;

        /**
         *
         * @param cond
         * @return true, if successful
         * @throws SQLException the SQL exception
         */
        boolean exists(final Condition cond) throws SQLException;

        /**
         *
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        int count(final Condition cond) throws SQLException;

        /**
         *
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        Optional<T> findFirst(final Condition cond) throws SQLException;

        /**
         * @param cond
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> Optional<R> findFirst(final Condition cond, final JdbcUtil.RowMapper<R> rowMapper) throws SQLException;

        /**
         * @param cond
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> Optional<R> findFirst(final Condition cond, final JdbcUtil.BiRowMapper<R> rowMapper) throws SQLException;

        /**
         *
         * @param selectPropNames
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        Optional<T> findFirst(final Collection<String> selectPropNames, final Condition cond) throws SQLException;

        /**
         *
         * @param selectPropNames
         * @param cond
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> Optional<R> findFirst(final Collection<String> selectPropNames, final Condition cond, final JdbcUtil.RowMapper<R> rowMapper) throws SQLException;

        /**
         *
         * @param selectPropNames
         * @param cond
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> Optional<R> findFirst(final Collection<String> selectPropNames, final Condition cond, final JdbcUtil.BiRowMapper<R> rowMapper) throws SQLException;

        /**
         * Query for boolean.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        OptionalBoolean queryForBoolean(final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for char.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        OptionalChar queryForChar(final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for byte.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        OptionalByte queryForByte(final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for short.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        OptionalShort queryForShort(final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for int.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        OptionalInt queryForInt(final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for long.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        OptionalLong queryForLong(final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for float.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        OptionalFloat queryForFloat(final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for double.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        OptionalDouble queryForDouble(final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for string.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        Nullable<String> queryForString(final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for date.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        Nullable<java.sql.Date> queryForDate(final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for time.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        Nullable<java.sql.Time> queryForTime(final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for timestamp.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        Nullable<java.sql.Timestamp> queryForTimestamp(final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for single result.
         *
         * @param <V> the value type
         * @param targetValueClass
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        <V> Nullable<V> queryForSingleResult(final Class<V> targetValueClass, final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for single non null.
         *
         * @param <V> the value type
         * @param targetValueClass
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws DuplicatedResultException if more than one record found.
         * @throws SQLException the SQL exception
         */
        <V> Optional<V> queryForSingleNonNull(final Class<V> targetValueClass, final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for unique result.
         *
         * @param <V> the value type
         * @param targetValueClass
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws DuplicatedResultException if more than one record found.
         * @throws SQLException the SQL exception
         */
        <V> Nullable<V> queryForUniqueResult(final Class<V> targetValueClass, final String singleSelectPropName, final Condition cond)
                throws DuplicatedResultException, SQLException;

        /**
         * Query for unique non null.
         *
         * @param <V> the value type
         * @param targetValueClass
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        <V> Optional<V> queryForUniqueNonNull(final Class<V> targetValueClass, final String singleSelectPropName, final Condition cond)
                throws DuplicatedResultException, SQLException;

        /**
         *
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        DataSet query(final Condition cond) throws SQLException;

        /**
         *
         * @param selectPropNames
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        DataSet query(final Collection<String> selectPropNames, final Condition cond) throws SQLException;

        /**
         *
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        List<T> list(final Condition cond) throws SQLException;

        /**
         *
         * @param cond
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> List<R> list(final Condition cond, final JdbcUtil.RowMapper<R> rowMapper) throws SQLException;

        /**
         *
         * @param cond
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> List<R> list(final Condition cond, final JdbcUtil.BiRowMapper<R> rowMapper) throws SQLException;

        /**
         *
         * @param cond
         * @param rowFilter
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> List<R> list(final Condition cond, final JdbcUtil.RowFilter rowFilter, final JdbcUtil.RowMapper<R> rowMapper) throws SQLException;

        /**
         *
         * @param cond
         * @param rowFilter
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> List<R> list(final Condition cond, final JdbcUtil.BiRowFilter rowFilter, final JdbcUtil.BiRowMapper<R> rowMapper) throws SQLException;

        /**
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        default <R> List<R> list(final String singleSelectPropName, final Condition cond) throws SQLException {
            final PropInfo propInfo = ParserUtil.getEntityInfo(targetEntityClass()).getPropInfo(singleSelectPropName);
            final RowMapper<R> rowMapper = propInfo == null ? RowMapper.GET_OBJECT : RowMapper.get((Type<R>) propInfo.dbType);

            return list(singleSelectPropName, cond, rowMapper);
        }

        /**
         *
         * @param singleSelectPropName
         * @param cond
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        default <R> List<R> list(final String singleSelectPropName, final Condition cond, final JdbcUtil.RowMapper<R> rowMapper) throws SQLException {
            return list(N.asList(singleSelectPropName), cond, rowMapper);
        }

        /**
         *
         * @param selectPropNames
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        List<T> list(final Collection<String> selectPropNames, final Condition cond) throws SQLException;

        /**
         *
         * @param selectPropNames
         * @param cond
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final JdbcUtil.RowMapper<R> rowMapper) throws SQLException;

        /**
         *
         * @param selectPropNames
         * @param cond
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final JdbcUtil.BiRowMapper<R> rowMapper) throws SQLException;

        /**
         *
         * @param selectPropNames
         * @param cond
         * @param rowFilter
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final JdbcUtil.RowFilter rowFilter,
                final JdbcUtil.RowMapper<R> rowMapper) throws SQLException;

        /**
         *
         * @param selectPropNames
         * @param cond
         * @param rowFilter
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final JdbcUtil.BiRowFilter rowFilter,
                final JdbcUtil.BiRowMapper<R> rowMapper) throws SQLException;

        // Will it cause confusion if it's called in transaction?
        /**
         * lazy-execution, lazy-fetch.
         *
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        ExceptionalStream<T, SQLException> stream(final Condition cond) throws SQLException;

        // Will it cause confusion if it's called in transaction?
        /**
         * lazy-execution, lazy-fetch.
         *
         * @param cond
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> ExceptionalStream<R, SQLException> stream(final Condition cond, final JdbcUtil.RowMapper<R> rowMapper) throws SQLException;

        // Will it cause confusion if it's called in transaction?
        /**
         * lazy-execution, lazy-fetch.
         *
         * @param cond
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> ExceptionalStream<R, SQLException> stream(final Condition cond, final JdbcUtil.BiRowMapper<R> rowMapper) throws SQLException;

        /**
         * lazy-execution, lazy-fetch.
         *
         * @param cond
         * @param rowFilter
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> ExceptionalStream<R, SQLException> stream(final Condition cond, final JdbcUtil.RowFilter rowFilter, final JdbcUtil.RowMapper<R> rowMapper)
                throws SQLException;

        /**
         * lazy-execution, lazy-fetch.
         *
         * @param cond
         * @param rowFilter
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> ExceptionalStream<R, SQLException> stream(final Condition cond, final JdbcUtil.BiRowFilter rowFilter, final JdbcUtil.BiRowMapper<R> rowMapper)
                throws SQLException;

        /**
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        default <R> ExceptionalStream<R, SQLException> stream(final String singleSelectPropName, final Condition cond) throws SQLException {
            final PropInfo propInfo = ParserUtil.getEntityInfo(targetEntityClass()).getPropInfo(singleSelectPropName);
            final RowMapper<R> rowMapper = propInfo == null ? RowMapper.GET_OBJECT : RowMapper.get((Type<R>) propInfo.dbType);

            return stream(singleSelectPropName, cond, rowMapper);
        }

        /**
         *
         * @param singleSelectPropName
         * @param cond
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        default <R> ExceptionalStream<R, SQLException> stream(final String singleSelectPropName, final Condition cond, final JdbcUtil.RowMapper<R> rowMapper)
                throws SQLException {
            return stream(N.asList(singleSelectPropName), cond, rowMapper);
        }

        // Will it cause confusion if it's called in transaction?
        /**
         * lazy-execution, lazy-fetch.
         *
         * @param selectPropNames
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        ExceptionalStream<T, SQLException> stream(final Collection<String> selectPropNames, final Condition cond) throws SQLException;

        // Will it cause confusion if it's called in transaction?
        /**
         * lazy-execution, lazy-fetch.
         *
         * @param selectPropNames
         * @param cond
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> ExceptionalStream<R, SQLException> stream(final Collection<String> selectPropNames, final Condition cond, final JdbcUtil.RowMapper<R> rowMapper)
                throws SQLException;

        // Will it cause confusion if it's called in transaction?
        /**
         * lazy-execution, lazy-fetch.
         *
         * @param selectPropNames
         * @param cond
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> ExceptionalStream<R, SQLException> stream(final Collection<String> selectPropNames, final Condition cond, final JdbcUtil.BiRowMapper<R> rowMapper)
                throws SQLException;

        // Will it cause confusion if it's called in transaction?
        /**
         * lazy-execution, lazy-fetch.
         *
         * @param selectPropNames
         * @param cond
         * @param rowFilter
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> ExceptionalStream<R, SQLException> stream(final Collection<String> selectPropNames, final Condition cond, JdbcUtil.RowFilter rowFilter,
                final JdbcUtil.RowMapper<R> rowMapper) throws SQLException;

        // Will it cause confusion if it's called in transaction?
        /**
         * lazy-execution, lazy-fetch.
         *
         * @param selectPropNames
         * @param cond
         * @param rowFilter
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> ExceptionalStream<R, SQLException> stream(final Collection<String> selectPropNames, final Condition cond, final JdbcUtil.BiRowFilter rowFilter,
                final JdbcUtil.BiRowMapper<R> rowMapper) throws SQLException;

        /**
         *
         * @param entity
         * @param joinEntityClass
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntities(final T entity, final Class<?> joinEntityClass) throws SQLException {
            loadJoinEntities(entity, joinEntityClass, null);
        }

        /**
         *
         * @param entity
         * @param joinEntityClass
         * @param selectPropNames
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntities(final T entity, final Class<?> joinEntityClass, final Collection<String> selectPropNames) throws SQLException {
            final List<String> joinEntityPropNames = JoinInfo.getJoinEntityPropNamesByType(entity.getClass(), joinEntityClass);
            N.checkArgument(N.notNullOrEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, entity.getClass());

            for (String joinEntityPropName : joinEntityPropNames) {
                loadJoinEntities(entity, joinEntityPropName, selectPropNames);
            }
        }

        /**
         *
         * @param entities
         * @param joinEntityClass
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntities(final Collection<T> entities, final Class<?> joinEntityClass) throws SQLException {
            loadJoinEntities(entities, joinEntityClass, null);
        }

        /**
         *
         * @param entities
         * @param joinEntityClass
         * @param selectPropNames
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntities(final Collection<T> entities, final Class<?> joinEntityClass, final Collection<String> selectPropNames)
                throws SQLException {
            if (N.isNullOrEmpty(entities)) {
                return;
            }

            final Class<?> entityClass = N.firstOrNullIfEmpty(entities).getClass();
            final List<String> joinEntityPropNames = JoinInfo.getJoinEntityPropNamesByType(entityClass, joinEntityClass);
            N.checkArgument(N.notNullOrEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, entityClass);

            for (String joinEntityPropName : joinEntityPropNames) {
                loadJoinEntities(entities, joinEntityPropName, selectPropNames);
            }
        }

        /**
         *
         * @param entity
         * @param joinEntityPropName
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntities(final T entity, final String joinEntityPropName) throws SQLException {
            loadJoinEntities(entity, joinEntityPropName, null);
        }

        /**
         *
         * @param entity
         * @param joinEntityPropName
         * @param selectPropNames
         * @throws SQLException the SQL exception
         */
        void loadJoinEntities(final T entity, final String joinEntityPropName, final Collection<String> selectPropNames) throws SQLException;

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntities(final Collection<T> entities, final String joinEntityPropName) throws SQLException {
            loadJoinEntities(entities, joinEntityPropName, null);
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param selectPropNames
         * @throws SQLException the SQL exception
         */
        void loadJoinEntities(final Collection<T> entities, final String joinEntityPropName, final Collection<String> selectPropNames) throws SQLException;

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntities(final T entity, final Collection<String> joinEntityPropNames) throws SQLException {
            if (N.isNullOrEmpty(joinEntityPropNames)) {
                return;
            }

            for (String joinEntityPropName : joinEntityPropNames) {
                loadJoinEntities(entity, joinEntityPropName);
            }
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @param inParallel
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final boolean inParallel) throws SQLException {
            if (inParallel) {
                loadJoinEntities(entity, joinEntityPropNames, executor());
            } else {
                loadJoinEntities(entity, joinEntityPropNames);
            }
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @param executor
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final Executor executor) throws SQLException {
            if (N.isNullOrEmpty(joinEntityPropNames)) {
                return;
            }

            final List<ContinuableFuture<Void>> futures = StreamE.of(joinEntityPropNames, SQLException.class)
                    .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntities(entity, joinEntityPropName), executor))
                    .toList();

            JdbcUtil.complete(futures);
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames) throws SQLException {
            if (N.isNullOrEmpty(entities) || N.isNullOrEmpty(joinEntityPropNames)) {
                return;
            }

            for (String joinEntityPropName : joinEntityPropNames) {
                loadJoinEntities(entities, joinEntityPropName);
            }
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param inParallel
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final boolean inParallel)
                throws SQLException {
            if (inParallel) {
                loadJoinEntities(entities, joinEntityPropNames, executor());
            } else {
                loadJoinEntities(entities, joinEntityPropNames);
            }
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param executor
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final Executor executor) throws SQLException {
            if (N.isNullOrEmpty(entities) || N.isNullOrEmpty(joinEntityPropNames)) {
                return;
            }

            final List<ContinuableFuture<Void>> futures = StreamE.of(joinEntityPropNames, SQLException.class)
                    .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntities(entities, joinEntityPropName), executor))
                    .toList();

            JdbcUtil.complete(futures);
        }

        /**
         *
         * @param entity
         * @throws SQLException the SQL exception
         */
        default void loadAllJoinEntities(T entity) throws SQLException {
            loadJoinEntities(entity, JoinInfo.getEntityJoinInfo(entity.getClass()).keySet());
        }

        /**
         *
         * @param entity
         * @param inParallel
         * @throws SQLException the SQL exception
         */
        default void loadAllJoinEntities(final T entity, final boolean inParallel) throws SQLException {
            if (inParallel) {
                loadAllJoinEntities(entity, executor());
            } else {
                loadAllJoinEntities(entity);
            }
        }

        /**
         *
         * @param entity
         * @param executor
         * @throws SQLException the SQL exception
         */
        default void loadAllJoinEntities(final T entity, final Executor executor) throws SQLException {
            loadJoinEntities(entity, JoinInfo.getEntityJoinInfo(entity.getClass()).keySet(), executor);
        }

        /**
         *
         * @param entities
         * @throws SQLException the SQL exception
         */
        default void loadAllJoinEntities(final Collection<T> entities) throws SQLException {
            if (N.isNullOrEmpty(entities)) {
                return;
            }

            loadJoinEntities(entities, JoinInfo.getEntityJoinInfo(N.firstOrNullIfEmpty(entities).getClass()).keySet());
        }

        /**
         *
         * @param entities
         * @param inParallel
         * @throws SQLException the SQL exception
         */
        default void loadAllJoinEntities(final Collection<T> entities, final boolean inParallel) throws SQLException {
            if (inParallel) {
                loadAllJoinEntities(entities, executor());
            } else {
                loadAllJoinEntities(entities);
            }
        }

        /**
         *
         * @param entities
         * @param executor
         * @throws SQLException the SQL exception
         */
        default void loadAllJoinEntities(final Collection<T> entities, final Executor executor) throws SQLException {
            if (N.isNullOrEmpty(entities)) {
                return;
            }

            loadJoinEntities(entities, JoinInfo.getEntityJoinInfo(N.firstOrNullIfEmpty(entities).getClass()).keySet(), executor);
        }

        /**
         *
         * @param entity
         * @param joinEntityClass
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final T entity, final Class<?> joinEntityClass) throws SQLException {
            loadJoinEntitiesIfNull(entity, joinEntityClass, null);
        }

        /**
         *
         * @param entity
         * @param joinEntityClass
         * @param selectPropNames
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final T entity, final Class<?> joinEntityClass, final Collection<String> selectPropNames) throws SQLException {
            final List<String> joinEntityPropNames = JoinInfo.getJoinEntityPropNamesByType(entity.getClass(), joinEntityClass);
            N.checkArgument(N.notNullOrEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, entity.getClass());

            for (String joinEntityPropName : joinEntityPropNames) {
                loadJoinEntitiesIfNull(entity, joinEntityPropName, selectPropNames);
            }
        }

        /**
         *
         * @param entities
         * @param joinEntityClass
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final Collection<T> entities, final Class<?> joinEntityClass) throws SQLException {
            loadJoinEntitiesIfNull(entities, joinEntityClass, null);
        }

        /**
         *
         * @param entities
         * @param joinEntityClass
         * @param selectPropNames
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final Collection<T> entities, final Class<?> joinEntityClass, final Collection<String> selectPropNames)
                throws SQLException {
            if (N.isNullOrEmpty(entities)) {
                return;
            }

            final Class<?> entityClass = N.firstOrNullIfEmpty(entities).getClass();
            final List<String> joinEntityPropNames = JoinInfo.getJoinEntityPropNamesByType(entityClass, joinEntityClass);
            N.checkArgument(N.notNullOrEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, entityClass);

            if (joinEntityPropNames.size() == 1) {
                loadJoinEntitiesIfNull(entities, joinEntityPropNames.get(0), selectPropNames);
            } else {
                for (String joinEntityPropName : joinEntityPropNames) {
                    loadJoinEntitiesIfNull(entities, joinEntityPropName, selectPropNames);
                }
            }
        }

        /**
         *
         * @param entity
         * @param joinEntityPropName
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final T entity, final String joinEntityPropName) throws SQLException {
            loadJoinEntitiesIfNull(entity, joinEntityPropName, null);
        }

        /**
         *
         * @param entity
         * ?
         * @param joinEntityPropName
         * @param selectPropNames
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final T entity, final String joinEntityPropName, final Collection<String> selectPropNames) throws SQLException {
            final Class<?> cls = entity.getClass();
            final PropInfo propInfo = ParserUtil.getEntityInfo(cls).getPropInfo(joinEntityPropName);

            if (propInfo.getPropValue(entity) == null) {
                loadJoinEntities(entity, joinEntityPropName, selectPropNames);
            }
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final Collection<T> entities, final String joinEntityPropName) throws SQLException {
            loadJoinEntitiesIfNull(entities, joinEntityPropName, null);
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param selectPropNames
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final Collection<T> entities, final String joinEntityPropName, final Collection<String> selectPropNames)
                throws SQLException {
            if (N.isNullOrEmpty(entities)) {
                return;
            }

            final Class<?> cls = N.firstOrNullIfEmpty(entities).getClass();
            final PropInfo propInfo = ParserUtil.getEntityInfo(cls).getPropInfo(joinEntityPropName);
            final List<T> newEntities = N.filter(entities, entity -> propInfo.getPropValue(entity) == null);

            loadJoinEntities(newEntities, joinEntityPropName, selectPropNames);
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final T entity, final Collection<String> joinEntityPropNames) throws SQLException {
            if (N.isNullOrEmpty(joinEntityPropNames)) {
                return;
            }

            for (String joinEntityPropName : joinEntityPropNames) {
                loadJoinEntitiesIfNull(entity, joinEntityPropName);
            }
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @param inParallel
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final T entity, final Collection<String> joinEntityPropNames, final boolean inParallel) throws SQLException {
            if (inParallel) {
                loadJoinEntitiesIfNull(entity, joinEntityPropNames, executor());
            } else {
                loadJoinEntitiesIfNull(entity, joinEntityPropNames);
            }
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @param executor
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final T entity, final Collection<String> joinEntityPropNames, final Executor executor) throws SQLException {
            if (N.isNullOrEmpty(joinEntityPropNames)) {
                return;
            }

            final List<ContinuableFuture<Void>> futures = StreamE.of(joinEntityPropNames, SQLException.class)
                    .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntitiesIfNull(entity, joinEntityPropName), executor))
                    .toList();

            JdbcUtil.complete(futures);
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final Collection<T> entities, final Collection<String> joinEntityPropNames) throws SQLException {
            if (N.isNullOrEmpty(entities) || N.isNullOrEmpty(joinEntityPropNames)) {
                return;
            }

            for (String joinEntityPropName : joinEntityPropNames) {
                loadJoinEntitiesIfNull(entities, joinEntityPropName);
            }
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param inParallel
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final Collection<T> entities, final Collection<String> joinEntityPropNames, final boolean inParallel)
                throws SQLException {
            if (inParallel) {
                loadJoinEntitiesIfNull(entities, joinEntityPropNames, executor());
            } else {
                loadJoinEntitiesIfNull(entities, joinEntityPropNames);
            }
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param executor
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final Collection<T> entities, final Collection<String> joinEntityPropNames, final Executor executor)
                throws SQLException {
            if (N.isNullOrEmpty(entities) || N.isNullOrEmpty(joinEntityPropNames)) {
                return;
            }

            final List<ContinuableFuture<Void>> futures = StreamE.of(joinEntityPropNames, SQLException.class)
                    .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntitiesIfNull(entities, joinEntityPropName), executor))
                    .toList();

            JdbcUtil.complete(futures);
        }

        /**
         *
         * @param entity
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(T entity) throws SQLException {
            loadJoinEntitiesIfNull(entity, JoinInfo.getEntityJoinInfo(entity.getClass()).keySet());
        }

        /**
         *
         * @param entity
         * @param inParallel
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final T entity, final boolean inParallel) throws SQLException {
            if (inParallel) {
                loadJoinEntitiesIfNull(entity, executor());
            } else {
                loadJoinEntitiesIfNull(entity);
            }
        }

        /**
         *
         * @param entity
         * @param executor
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final T entity, final Executor executor) throws SQLException {
            loadJoinEntitiesIfNull(entity, JoinInfo.getEntityJoinInfo(entity.getClass()).keySet(), executor);
        }

        /**
         *
         * @param entities
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final Collection<T> entities) throws SQLException {
            if (N.isNullOrEmpty(entities)) {
                return;
            }

            loadJoinEntitiesIfNull(entities, JoinInfo.getEntityJoinInfo(N.firstOrNullIfEmpty(entities).getClass()).keySet());
        }

        /**
         *
         * @param entities
         * @param inParallel
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final Collection<T> entities, final boolean inParallel) throws SQLException {
            if (inParallel) {
                loadJoinEntitiesIfNull(entities, executor());
            } else {
                loadJoinEntitiesIfNull(entities);
            }
        }

        /**
         *
         * @param entities
         * @param executor
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final Collection<T> entities, final Executor executor) throws SQLException {
            if (N.isNullOrEmpty(entities)) {
                return;
            }

            loadJoinEntitiesIfNull(entities, JoinInfo.getEntityJoinInfo(N.firstOrNullIfEmpty(entities).getClass()).keySet(), executor);
        }

        /**
         *
         * @param updateProps
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        int update(final Map<String, Object> updateProps, final Condition cond) throws SQLException;

        /**
         *
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        int delete(final Condition cond) throws SQLException;

        /**
         *
         * @param <R>
         * @param func
         * @return
         */
        @Beta
        default <R> ContinuableFuture<R> asyncApply(final Throwables.Function<TD, R, SQLException> func) {
            return asyncApply(func, executor());
        }

        /**
         *
         * @param <R>
         * @param func
         * @param executor
         * @return
         */
        @Beta
        default <R> ContinuableFuture<R> asyncApply(final Throwables.Function<TD, R, SQLException> func, final Executor executor) {
            N.checkArgNotNull(func, "func");
            N.checkArgNotNull(executor, "executor");

            final TD tdao = (TD) this;

            return ContinuableFuture.call(() -> func.apply(tdao), executor);
        }

        /**
         *
         * @param action
         * @return
         */
        @Beta
        default ContinuableFuture<Void> asyncAccept(final Throwables.Consumer<TD, SQLException> action) {
            return asyncAccept(action, executor());
        }

        /**
         *
         * @param action
         * @param executor
         * @return
         */
        @Beta
        default ContinuableFuture<Void> asyncAccept(final Throwables.Consumer<TD, SQLException> action, final Executor executor) {
            N.checkArgNotNull(action, "action");
            N.checkArgNotNull(executor, "executor");

            final TD tdao = (TD) this;

            return ContinuableFuture.run(() -> action.accept(tdao), executor);
        }
    }

    /**
     * The Interface CrudDao.
     *
     * @param <T>
     * @param <ID> use {@code Void} if there is no id defined/annotated with {@code @Id} in target entity class {@code T}.
     * @param <SB> {@code SQLBuilder} used to generate sql scripts. Only can be {@code SQLBuilder.PSC/PAC/PLC}
     * @see JdbcUtil#prepareQuery(javax.sql.DataSource, String)
     * @see JdbcUtil#prepareNamedQuery(javax.sql.DataSource, String)
     * @see JdbcUtil#beginTransaction(javax.sql.DataSource, IsolationLevel, boolean)
     * @see Dao
     * @see SQLExecutor.Mapper
     * @see com.landawn.abacus.condition.ConditionFactory
     * @see com.landawn.abacus.condition.ConditionFactory.CF
     */
    public interface CrudDao<T, ID, SB extends SQLBuilder, TD extends CrudDao<T, ID, SB, TD>> extends Dao<T, SB, TD> {

        /**
         *
         * @param entityToSave
         * @return
         * @throws SQLException the SQL exception
         */
        ID insert(final T entityToSave) throws SQLException;

        /**
         *
         * @param entityToSave
         * @param propNamesToSave
         * @return
         * @throws SQLException the SQL exception
         */
        ID insert(final T entityToSave, final Collection<String> propNamesToSave) throws SQLException;

        /**
         *
         * @param namedInsertSQL
         * @param entityToSave
         * @return
         * @throws SQLException the SQL exception
         */
        ID insert(final String namedInsertSQL, final T entityToSave) throws SQLException;

        /**
         *
         * @param entities
         * @return
         * @throws SQLException the SQL exception
         */
        default List<ID> batchInsert(final Collection<? extends T> entities) throws SQLException {
            return batchInsert(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param entities
         * @param batchSize
         * @return
         * @throws SQLException the SQL exception
         */
        List<ID> batchInsert(final Collection<? extends T> entities, final int batchSize) throws SQLException;

        /**
         *
         * @param namedInsertSQL
         * @param entities
         * @return
         * @throws SQLException the SQL exception
         */
        @Beta
        default List<ID> batchInsert(final String namedInsertSQL, final Collection<? extends T> entities) throws SQLException {
            return batchInsert(namedInsertSQL, entities, JdbcUtil.DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param namedInsertSQL
         * @param entities
         * @param batchSize
         * @return
         * @throws SQLException the SQL exception
         */
        @Beta
        List<ID> batchInsert(final String namedInsertSQL, final Collection<? extends T> entities, final int batchSize) throws SQLException;

        /**
         *
         * @param id
         * @return
         * @throws SQLException the SQL exception
         */
        default Optional<T> get(final ID id) throws SQLException {
            return get(null, id);
        }

        /**
         *
         * @param selectPropNames
         * @param id
         * @return
         * @throws SQLException the SQL exception
         */
        Optional<T> get(final Collection<String> selectPropNames, final ID id) throws SQLException;

        /**
         *
         * @param id
         * @param includeAllJoinEntities
         * @return
         * @throws SQLException the SQL exception
         */
        default Optional<T> get(final ID id, final boolean includeAllJoinEntities) throws SQLException {
            final Optional<T> result = get(id);

            if (includeAllJoinEntities && result.isPresent()) {
                loadAllJoinEntities(result.get());
            }

            return result;
        }

        /**
         *
         * @param id
         * @param joinEntitiesToLoad
         * @return
         * @throws SQLException the SQL exception
         */
        default Optional<T> get(final ID id, final Class<?> joinEntitiesToLoad) throws SQLException {
            final Optional<T> result = get(id);

            if (result.isPresent()) {
                loadJoinEntities(result.get(), joinEntitiesToLoad);
            }

            return result;
        }

        /**
         *
         * @param id
         * @param joinEntitiesToLoad
         * @return
         * @throws SQLException the SQL exception
         */
        default Optional<T> get(final ID id, final Collection<Class<?>> joinEntitiesToLoad) throws SQLException {
            final Optional<T> result = get(id);

            if (result.isPresent()) {
                for (Class<?> joinEntityClass : joinEntitiesToLoad) {
                    loadJoinEntities(result.get(), joinEntityClass);
                }
            }

            return result;
        }

        /**
         * Gets the t.
         *
         * @param id
         * @return
         * @throws SQLException the SQL exception
         */
        default T gett(final ID id) throws SQLException {
            return get(id).orNull();
        }

        /**
         * Gets the t.
         *
         * @param selectPropNames
         * @param id
         * @return
         * @throws SQLException the SQL exception
         */
        default T gett(final Collection<String> selectPropNames, final ID id) throws SQLException {
            return get(selectPropNames, id).orNull();
        }

        /**
         *
         * @param id
         * @param includeAllJoinEntities
         * @return
         * @throws SQLException the SQL exception
         */
        default T gett(final ID id, final boolean includeAllJoinEntities) throws SQLException {
            return get(id, includeAllJoinEntities).orNull();
        }

        /**
         *
         * @param id
         * @param joinEntitiesToLoad
         * @return
         * @throws SQLException the SQL exception
         */
        default T gett(final ID id, final Class<?> joinEntitiesToLoad) throws SQLException {
            return get(id, joinEntitiesToLoad).orNull();
        }

        /**
         *
         * @param id
         * @param joinEntitiesToLoad
         * @return
         * @throws SQLException the SQL exception
         */
        default T gett(final ID id, final Collection<Class<?>> joinEntitiesToLoad) throws SQLException {
            return get(id, joinEntitiesToLoad).orNull();
        }

        /**
         *
         *
         * @param ids
         * @return
         * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}.
         * @throws SQLException the SQL exception
         */
        default List<T> batchGet(final Collection<? extends ID> ids) throws DuplicatedResultException, SQLException {
            return batchGet(ids, (Collection<String>) null);
        }

        /**
         *
         *
         * @param ids
         * @param selectPropNames
         * @return
         * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}.
         * @throws SQLException the SQL exception
         */
        default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames) throws DuplicatedResultException, SQLException {
            return batchGet(ids, selectPropNames, JdbcUtil.DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param ids
         * @param selectPropNames
         * @param batchSize
         * @return
         * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}.
         * @throws SQLException the SQL exception
         */
        List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final int batchSize)
                throws DuplicatedResultException, SQLException;

        /**
         *
         * @param id
         * @return true, if successful
         * @throws SQLException the SQL exception
         */
        boolean exists(final ID id) throws SQLException;

        /**
         *
         * @param entityToUpdate
         * @return
         * @throws SQLException the SQL exception
         */
        int update(final T entityToUpdate) throws SQLException;

        /**
         *
         * @param entityToUpdate
         * @param propNamesToUpdate
         * @return
         * @throws SQLException the SQL exception
         */
        int update(final T entityToUpdate, final Collection<String> propNamesToUpdate) throws SQLException;

        /**
         *
         * @param updateProps
         * @param id
         * @return
         * @throws SQLException the SQL exception
         */
        int update(final Map<String, Object> updateProps, final ID id) throws SQLException;

        /**
         *
         * @param entities
         * @return
         * @throws SQLException the SQL exception
         */
        default int batchUpdate(final Collection<? extends T> entities) throws SQLException {
            return batchUpdate(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param entities
         * @param batchSize
         * @return
         * @throws SQLException the SQL exception
         */
        int batchUpdate(final Collection<? extends T> entities, final int batchSize) throws SQLException;

        /**
         *
         * @param entities
         * @param propNamesToUpdate
         * @return
         * @throws SQLException the SQL exception
         */
        default int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate) throws SQLException {
            return batchUpdate(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param entities
         * @param propNamesToUpdate
         * @param batchSize
         * @return
         * @throws SQLException the SQL exception
         */
        int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate, final int batchSize) throws SQLException;

        /**
         * Execute {@code add} and return the added entity if the record doesn't, otherwise, {@code update} is executed and updated db record is returned.
         *
         * @param entity
         * @param whereCause to verify if the record exists or not.
         * @return
         */
        default T upsert(final T entity, final Condition whereCause) throws SQLException {
            N.checkArgNotNull(whereCause, "whereCause");

            final T dbEntity = findFirst(whereCause).orNull();

            if (dbEntity == null) {
                insert(entity);
                return entity;
            } else {
                @SuppressWarnings("deprecation")
                final List<String> idPropNameList = ClassUtil.getIdFieldNames(targetEntityClass());
                N.merge(entity, dbEntity, false, N.newHashSet(idPropNameList));
                update(dbEntity);
                return dbEntity;
            }
        }

        /**
         * Execute {@code add} and return the added entity if the record doesn't, otherwise, {@code update} is executed and updated db record is returned.
         *
         * @param entity
         * @return
         */
        default T upsert(final T entity) throws SQLException {
            @SuppressWarnings("deprecation")
            final List<String> idPropNameList = ClassUtil.getIdFieldNames(targetEntityClass());
            final T dbEntity = idPropNameList.size() == 1 ? gett((ID) ClassUtil.getPropValue(entity, idPropNameList.get(0))) : gett((ID) entity);

            if (dbEntity == null) {
                insert(entity);
                return entity;
            } else {
                N.merge(entity, dbEntity, false, N.newHashSet(idPropNameList));
                update(dbEntity);
                return dbEntity;
            }
        }

        /**
         *
         * @param entity
         * @return true, if successful
         * @throws SQLException
         */
        default boolean refresh(final T entity) throws SQLException {
            final Class<?> cls = entity.getClass();
            final Collection<String> propNamesToRefresh = DirtyMarkerUtil.isDirtyMarker(cls) ? DirtyMarkerUtil.signedPropNames((DirtyMarker) entity)
                    : SQLBuilder.getSelectPropNamesByClass(cls, false, null);

            return refresh(entity, propNamesToRefresh);
        }

        /**
         *
         * @param entity
         * @param propNamesToRefresh
         * @return {@code false} if no record found by the ids in the specified {@code entity}.
         * @throws SQLException
         */
        @SuppressWarnings("deprecation")
        default boolean refresh(final T entity, Collection<String> propNamesToRefresh) throws SQLException {
            final Class<?> cls = entity.getClass();
            final List<String> idPropNameList = ClassUtil.getIdFieldNames(cls); // must not empty.
            final EntityInfo entityInfo = ParserUtil.getEntityInfo(cls);

            ID id = null;

            if (idPropNameList.size() == 1) {
                id = entityInfo.getPropInfo(idPropNameList.get(0)).getPropValue(entity);

            } else {
                Seid entityId = Seid.of(ClassUtil.getSimpleClassName(cls));

                for (String idPropName : idPropNameList) {
                    entityId.set(idPropName, entityInfo.getPropInfo(idPropName).getPropValue(entity));
                }

                id = (ID) entityId;
            }

            if (N.isNullOrEmpty(propNamesToRefresh)) {
                return exists(id);
            }

            final T dbEntity = gett(propNamesToRefresh, id);

            if (dbEntity == null) {
                return false;
            } else {
                N.merge(dbEntity, entity, propNamesToRefresh);

                if (DirtyMarkerUtil.isDirtyMarker(cls)) {
                    DirtyMarkerUtil.markDirty((DirtyMarker) entity, propNamesToRefresh, false);
                }

                return true;
            }
        }

        /**
         * Delete by id.
         *
         * @param id
         * @return
         * @throws SQLException the SQL exception
         */
        int deleteById(final ID id) throws SQLException;

        /**
         *
         * @param entity
         * @return
         * @throws SQLException the SQL exception
         */
        int delete(final T entity) throws SQLException;

        /**
         *
         * @param entity
         * @param deleteAllJoinEntities
         * @return
         * @throws SQLException the SQL exception
         */
        int delete(final T entity, final boolean deleteAllJoinEntities) throws SQLException;

        /**
         *
         * @param entities
         * @return
         * @throws SQLException the SQL exception
         */
        default int batchDelete(final Collection<? extends T> entities) throws SQLException {
            return batchDelete(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param entities
         * @param batchSize
         * @return
         * @throws SQLException the SQL exception
         */
        int batchDelete(final Collection<? extends T> entities, final int batchSize) throws SQLException;

        /**
         *
         * @param entities
         * @param deleteAllJoinEntities
         * @return
         * @throws SQLException the SQL exception
         */
        default int batchDelete(final Collection<? extends T> entities, final boolean deleteAllJoinEntities) throws SQLException {
            return batchDelete(entities, deleteAllJoinEntities, JdbcUtil.DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param entities
         * @param deleteAllJoinEntities
         * @param batchSize
         * @return
         * @throws SQLException the SQL exception
         */
        int batchDelete(final Collection<? extends T> entities, final boolean deleteAllJoinEntities, final int batchSize) throws SQLException;

        /**
         *
         * @param ids
         * @return
         * @throws SQLException the SQL exception
         */
        default int batchDeleteByIds(final Collection<? extends ID> ids) throws SQLException {
            return batchDeleteByIds(ids, JdbcUtil.DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param ids
         * @param batchSize
         * @return
         * @throws SQLException the SQL exception
         */
        int batchDeleteByIds(final Collection<? extends ID> ids, final int batchSize) throws SQLException;
    }

    /**
     * TODO
     *
     * @param <T>
     * @param <SB>
     * @param <TD>
     */
    @Beta
    public static interface NoUpdateDao<T, SB extends SQLBuilder, TD extends NoUpdateDao<T, SB, TD>> extends Dao<T, SB, TD> {

        /**
         *
         * @param updateProps
         * @param cond
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int update(final Map<String, Object> updateProps, final Condition cond) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param cond
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int delete(final Condition cond) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * TODO
     *
     * @param <T>
     * @param <SB>
     * @param <TD>
     */
    @Beta
    public static interface ReadOnlyDao<T, SB extends SQLBuilder, TD extends ReadOnlyDao<T, SB, TD>> extends NoUpdateDao<T, SB, TD> {

        /**
         *
         * @param entityToSave
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default void save(final T entityToSave) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entityToSave
         * @param propNamesToSave
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default void save(final T entityToSave, final Collection<String> propNamesToSave) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param namedInsertSQL
         * @param entityToSave
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default void save(final String namedInsertSQL, final T entityToSave) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * Always throws {@code UnsupportedOperationException}.
         *
         * @param entitiesToSave
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default void saveAll(final Collection<? extends T> entitiesToSave) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * Always throws {@code UnsupportedOperationException}.
         *
         * @param entitiesToSave
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default void saveAll(final Collection<? extends T> entitiesToSave, final int batchSize) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * Always throws {@code UnsupportedOperationException}.
         *
         * @param namedInsertSQL
         * @param entitiesToSave
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default void saveAll(final String namedInsertSQL, final Collection<? extends T> entitiesToSave) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * Always throws {@code UnsupportedOperationException}.
         *
         * @param namedInsertSQL
         * @param entitiesToSave
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default void saveAll(final String namedInsertSQL, final Collection<? extends T> entitiesToSave, final int batchSize)
                throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * TODO
     *
     * @param <T>
     * @param <ID>
     * @param <SB>
     * @param <TD>
     */
    @Beta
    public static interface NoUpdateCrudDao<T, ID, SB extends SQLBuilder, TD extends NoUpdateCrudDao<T, ID, SB, TD>>
            extends NoUpdateDao<T, SB, TD>, CrudDao<T, ID, SB, TD> {

        /**
         *
         * @param entityToUpdate
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int update(final T entityToUpdate) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entityToUpdate
         * @param propNamesToUpdate
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int update(final T entityToUpdate, final Collection<String> propNamesToUpdate) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param updateProps
         * @param id
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int update(final Map<String, Object> updateProps, final ID id) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int batchUpdate(final Collection<? extends T> entities) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int batchUpdate(final Collection<? extends T> entities, final int batchSize) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param propNamesToUpdate
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate)
                throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param propNamesToUpdate
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate, final int batchSize)
                throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * Execute {@code add} and return the added entity if the record doesn't, otherwise, {@code update} is executed and updated db record is returned.
         *
         * @param entity
         * @param whereCause to verify if the record exists or not.
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default T upsert(final T entity, final Condition whereCause) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * Execute {@code add} and return the added entity if the record doesn't, otherwise, {@code update} is executed and updated db record is returned.
         *
         * @param entity
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default T upsert(final T entity) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * Delete by id.
         *
         * @param id
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteById(final ID id) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entity
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int delete(final T entity) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entity
         * @param deleteAllJoinEntities
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int delete(final T entity, final boolean deleteAllJoinEntities) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int batchDelete(final Collection<? extends T> entities) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int batchDelete(final Collection<? extends T> entities, final int batchSize) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param deleteAllJoinEntities
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int batchDelete(final Collection<? extends T> entities, final boolean deleteAllJoinEntities)
                throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param deleteAllJoinEntities
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int batchDelete(final Collection<? extends T> entities, final boolean deleteAllJoinEntities, final int batchSize)
                throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param ids
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int batchDeleteByIds(final Collection<? extends ID> ids) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param ids
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int batchDeleteByIds(final Collection<? extends ID> ids, final int batchSize) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * TODO
     *
     * @param <T>
     * @param <ID>
     * @param <SB>
     * @param <TD>
     */
    @Beta
    public static interface ReadOnlyCrudDao<T, ID, SB extends SQLBuilder, TD extends ReadOnlyCrudDao<T, ID, SB, TD>>
            extends ReadOnlyDao<T, SB, TD>, NoUpdateCrudDao<T, ID, SB, TD> {

        /**
         *
         * @param entityToSave
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default ID insert(final T entityToSave) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entityToSave
         * @param propNamesToSave
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default ID insert(final T entityToSave, final Collection<String> propNamesToSave) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param namedInsertSQL
         * @param entityToSave
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default ID insert(final String namedInsertSQL, final T entityToSave) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default List<ID> batchInsert(final Collection<? extends T> entities) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default List<ID> batchInsert(final Collection<? extends T> entities, final int batchSize) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param namedInsertSQL
         * @param entities
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default List<ID> batchInsert(final String namedInsertSQL, final Collection<? extends T> entities) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param namedInsertSQL
         * @param entities
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default List<ID> batchInsert(final String namedInsertSQL, final Collection<? extends T> entities, final int batchSize)
                throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }
    }

    static Object[] getParameterArray(final SP sp) {
        return N.isNullOrEmpty(sp.parameters) ? N.EMPTY_OBJECT_ARRAY : sp.parameters.toArray();
    }

    static <R> BiRowMapper<R> toBiRowMapper(final RowMapper<R> rowMapper) {
        return (rs, columnLabels) -> rowMapper.apply(rs);
    }

    static final Throwables.Consumer<? super Exception, SQLException> throwSQLExceptionAction = e -> {
        if (e instanceof SQLException) {
            throw (SQLException) e;
        } else if (e.getCause() != null && e.getCause() instanceof SQLException) {
            throw (SQLException) e.getCause();
        } else {
            throw N.toRuntimeException(e);
        }
    };

    static void complete(final List<ContinuableFuture<Void>> futures) throws SQLException {
        for (ContinuableFuture<Void> f : futures) {
            f.gett().ifFailure(throwSQLExceptionAction);
        }
    }

    @SuppressWarnings("rawtypes")
    private static final Map<Class<?>, Map<NamingPolicy, Tuple3<BiRowMapper, Function, BiConsumer>>> idGeneratorGetterSetterPool = new ConcurrentHashMap<>();

    @SuppressWarnings("rawtypes")
    private static final Tuple3<BiRowMapper, Function, BiConsumer> noIdGeneratorGetterSetter = Tuple.of(NO_BI_GENERATED_KEY_EXTRACTOR, entity -> null,
            BiConsumers.doNothing());

    @SuppressWarnings({ "rawtypes", "deprecation" })
    static <ID> Tuple3<BiRowMapper<ID>, Function<Object, ID>, BiConsumer<ID, Object>> getIdGeneratorGetterSetter(final Class<?> entityClass,
            final NamingPolicy namingPolicy) {
        if (entityClass == null || !ClassUtil.isEntity(entityClass)) {
            return (Tuple3) noIdGeneratorGetterSetter;
        }

        Map<NamingPolicy, Tuple3<BiRowMapper, Function, BiConsumer>> map = idGeneratorGetterSetterPool.get(entityClass);

        if (map == null) {
            final List<String> idPropNameList = ClassUtil.getIdFieldNames(entityClass);
            final boolean isNoId = N.isNullOrEmpty(idPropNameList) || ClassUtil.isFakeId(idPropNameList);
            final String oneIdPropName = isNoId ? null : idPropNameList.get(0);
            final EntityInfo entityInfo = isNoId ? null : ParserUtil.getEntityInfo(entityClass);
            final List<PropInfo> idPropInfoList = isNoId ? null : Stream.of(idPropNameList).map(entityInfo::getPropInfo).toList();
            final PropInfo idPropInfo = isNoId ? null : entityInfo.getPropInfo(oneIdPropName);
            final boolean isOneId = isNoId ? false : idPropNameList.size() == 1;

            final Function<Object, ID> idGetter = isNoId ? noIdGeneratorGetterSetter._2 //
                    : (isOneId ? entity -> idPropInfo.getPropValue(entity) //
                            : entity -> {
                                final Seid seid = Seid.of(ClassUtil.getSimpleClassName(entityClass));

                                for (PropInfo propInfo : idPropInfoList) {
                                    seid.set(propInfo.name, propInfo.getPropValue(entity));
                                }

                                return (ID) seid;
                            });

            final BiConsumer<ID, Object> idSetter = isNoId ? noIdGeneratorGetterSetter._3 //
                    : (isOneId ? (id, entity) -> idPropInfo.setPropValue(entity, id) //
                            : (id, entity) -> {
                                if (id instanceof EntityId) {
                                    final EntityId entityId = (EntityId) id;
                                    PropInfo propInfo = null;

                                    for (String propName : entityId.keySet()) {
                                        propInfo = entityInfo.getPropInfo(propName);

                                        if ((propInfo = entityInfo.getPropInfo(propName)) != null) {
                                            propInfo.setPropValue(entity, entityId.get(propName));
                                        }
                                    }
                                } else {
                                    logger.warn("Can't set generated keys by id type: " + ClassUtil.getCanonicalClassName(id.getClass()));
                                }
                            });

            map = new EnumMap<>(NamingPolicy.class);

            for (NamingPolicy np : NamingPolicy.values()) {
                final ImmutableMap<String, String> propColumnNameMap = SQLBuilder.getPropColumnNameMap(entityClass, namingPolicy);

                final ImmutableMap<String, String> columnPropNameMap = EntryStream.of(propColumnNameMap)
                        .inversed()
                        .flattMapKey(e -> N.asList(e, e.toLowerCase(), e.toUpperCase()))
                        .distinctByKey()
                        .toImmutableMap();

                final BiRowMapper<Object> keyExtractor = isNoId ? noIdGeneratorGetterSetter._1 //
                        : (isOneId ? (rs, columnLabels) -> idPropInfo.dbType.get(rs, 1) //
                                : (rs, columnLabels) -> {
                                    if (columnLabels.size() == 1) {
                                        return idPropInfo.dbType.get(rs, 1);
                                    } else {
                                        final int columnCount = columnLabels.size();
                                        final Seid id = Seid.of(ClassUtil.getSimpleClassName(entityClass));
                                        String columnName = null;
                                        String propName = null;
                                        PropInfo propInfo = null;

                                        for (int i = 1; i <= columnCount; i++) {
                                            columnName = columnLabels.get(i - 1);

                                            if ((propName = columnPropNameMap.get(columnName)) == null
                                                    || (propInfo = entityInfo.getPropInfo(propName)) == null) {
                                                id.set(columnName, getColumnValue(rs, i));
                                            } else {
                                                id.set(propInfo.name, propInfo.dbType.get(rs, i));
                                            }
                                        }

                                        return id;
                                    }
                                });

                map.put(np, Tuple.of(keyExtractor, idGetter, idSetter));
            }

            idGeneratorGetterSetterPool.put(entityClass, map);
        }

        return (Tuple3) map.get(namingPolicy);
    }

    /**
     *
     * @param <T>
     * @param <SB>
     * @param <TD>
     * @param daoInterface
     * @param ds
     * @return
     */
    public static <T, SB extends SQLBuilder, TD extends Dao<T, SB, TD>> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds) {
        return createDao(daoInterface, ds, null, null, asyncExecutor.getExecutor());
    }

    /**
     *
     * @param <T>
     * @param <SB>
     * @param <TD>
     * @param daoInterface
     * @param ds
     * @param daoSettings
     * @return
     */
    public static <T, SB extends SQLBuilder, TD extends Dao<T, SB, TD>> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds,
            final DaoSettings daoSettings) {
        return createDao(daoInterface, ds, daoSettings, null, asyncExecutor.getExecutor());
    }

    /**
     *
     * @param <T>
     * @param <SB>
     * @param <TD>
     * @param daoInterface
     * @param ds
     * @param sqlMapper
     * @return
     */
    public static <T, SB extends SQLBuilder, TD extends Dao<T, SB, TD>> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds,
            final SQLMapper sqlMapper) {
        return createDao(daoInterface, ds, null, sqlMapper, asyncExecutor.getExecutor());
    }

    /**
     *
     * @param <T>
     * @param <SB>
     * @param <TD>
     * @param daoInterface
     * @param ds
     * @param executor
     * @return
     */
    public static <T, SB extends SQLBuilder, TD extends Dao<T, SB, TD>> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds,
            final Executor executor) {
        return createDao(daoInterface, ds, null, null, executor);
    }

    /**
     *
     * @param <T>
     * @param <SB>
     * @param <TD>
     * @param daoInterface
     * @param ds
     * @param daoSettings
     * @param sqlMapper
     * @param executor
     * @return
     */
    public static <T, SB extends SQLBuilder, TD extends Dao<T, SB, TD>> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds,
            final DaoSettings daoSettings, final SQLMapper sqlMapper, final Executor executor) {
        return DaoUtil.createDao(daoInterface, ds, null, daoSettings, sqlMapper, executor);
    }
}
