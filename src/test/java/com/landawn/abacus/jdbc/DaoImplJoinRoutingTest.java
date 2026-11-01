package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.annotation.JoinedBy;
import com.landawn.abacus.jdbc.dao.Dao;
import com.landawn.abacus.jdbc.dao.JoinEntityHelper;

import lombok.Data;

class DaoImplJoinRoutingTest extends TestBase {
    private static final ThreadLocal<DataSource> ROUTED_SOURCE = new ThreadLocal<>();
    private static final ThreadLocal<DataSource> CHILD_SOURCE = new ThreadLocal<>();
    private static final ThreadLocal<Integer> CHILD_PREPARATIONS = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Map<DataSource, PreparedQuery>> QUERIES = ThreadLocal.withInitial(IdentityHashMap::new);

    @ParameterizedTest(name = "delete={0}, overload={1} (0=entity, 1=singleton, 2=batch)")
    @CsvSource({ "false,0", "false,1", "false,2", "true,0", "true,1", "true,2" })
    void joinOperation_overriddenDataSource_usesRegisteredDaoForActualSource(final boolean delete, final int sourceCount) throws SQLException {
        final DataSource constructionSource = dataSource();
        final DataSource actualSource = dataSource();
        final PreparedQuery constructionQuery = mock(PreparedQuery.class, RETURNS_SELF);
        final PreparedQuery actualQuery = mock(PreparedQuery.class, RETURNS_SELF);
        when(constructionQuery.list(Child.class)).thenReturn(List.of());
        when(actualQuery.list(Child.class)).thenReturn(List.of());
        when(constructionQuery.update()).thenReturn(71);
        when(actualQuery.update()).thenReturn(2);
        QUERIES.get().put(constructionSource, constructionQuery);
        QUERIES.get().put(actualSource, actualQuery);
        ROUTED_SOURCE.set(actualSource);

        try {
            JdbcUtil.createDao(ChildDao.class, constructionSource);
            JdbcUtil.createDao(ChildDao.class, actualSource);
            final ParentDao parentDao = JdbcUtil.createDao(ParentDao.class, constructionSource);
            final Parent first = new Parent();
            first.setId(1);
            final Parent second = new Parent();
            second.setId(2);
            final List<Parent> parents = sourceCount == 1 ? List.of(first) : List.of(first, second);

            if (delete) {
                assertEquals(2, sourceCount == 0 ? parentDao.deleteJoinEntities(first, "children") : parentDao.deleteJoinEntities(parents, "children"));
                verify(actualQuery).update();
                verify(constructionQuery, never()).update();
            } else {
                if (sourceCount == 0) {
                    parentDao.loadJoinEntities(first, "children", null);
                } else {
                    parentDao.loadJoinEntities(parents, "children", null);
                }
                assertEquals(List.of(), first.getChildren());
                verify(actualQuery).list(Child.class);
                verify(constructionQuery, never()).list(Child.class);
            }
        } finally {
            ROUTED_SOURCE.remove();
            QUERIES.remove();
        }
    }

    @ParameterizedTest(name = "target initially matches={0}")
    @ValueSource(booleans = { false, true })
    void loadJoinEntities_targetRouteChanges_rechecksWhetherRegisteredTargetMatches(final boolean initiallyMatches) throws SQLException {
        final DataSource source = dataSource();
        final DataSource otherSource = dataSource();
        final PreparedQuery sourceQuery = mock(PreparedQuery.class, RETURNS_SELF);
        final PreparedQuery otherQuery = mock(PreparedQuery.class, RETURNS_SELF);
        when(sourceQuery.list(Child.class)).thenReturn(List.of());
        when(otherQuery.list(Child.class)).thenReturn(List.of());
        QUERIES.get().put(source, sourceQuery);
        QUERIES.get().put(otherSource, otherQuery);
        ROUTED_SOURCE.set(source);
        CHILD_SOURCE.set(initiallyMatches ? source : otherSource);

        try {
            JdbcUtil.createDao(RoutedChildDao.class, otherSource);
            final ParentDao parentDao = JdbcUtil.createDao(ParentDao.class, source);
            final Parent parent = new Parent();
            parent.setId(1);
            parentDao.loadJoinEntities(parent, "children", null);
            assertEquals(initiallyMatches ? 1 : 0, CHILD_PREPARATIONS.get());
            CHILD_SOURCE.set(initiallyMatches ? otherSource : source);
            parentDao.loadJoinEntities(parent, "children", null);
            assertEquals(1, CHILD_PREPARATIONS.get(), "The routed target must be selected only while it matches the parent's source");
            verify(sourceQuery, times(2)).list(Child.class);
            verify(otherQuery, never()).list(Child.class);
        } finally {
            ROUTED_SOURCE.remove();
            CHILD_SOURCE.remove();
            CHILD_PREPARATIONS.remove();
            QUERIES.remove();
        }
    }

    @Test
    void loadJoinEntities_routedTargetNeverMatches_warnsOnceAndKeepsRechecking() throws SQLException {
        final DataSource source = dataSource();
        final DataSource otherSource = dataSource();
        final PreparedQuery sourceQuery = mock(PreparedQuery.class, RETURNS_SELF);
        when(sourceQuery.list(Child.class)).thenReturn(List.of());
        QUERIES.get().put(source, sourceQuery);
        ROUTED_SOURCE.set(source);
        CHILD_SOURCE.set(otherSource);

        final List<LogEvent> events = new ArrayList<>();
        final AbstractAppender appender = new AbstractAppender("join-dao-miss-capture", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(final LogEvent event) {
                if (event.getMessage().getFormattedMessage().contains("No Dao interface/instance found for join operations")
                        && event.getMessage().getFormattedMessage().contains("dataSourceId=" + System.identityHashCode(source) + ",")) {
                    events.add(event.toImmutable());
                }
            }
        };
        final org.apache.logging.log4j.core.Logger logger = (org.apache.logging.log4j.core.Logger) LogManager.getLogger(JdbcUtil.class);
        final Level previousLevel = logger.getLevel();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);

        try {
            JdbcUtil.createDao(RoutedChildDao.class, otherSource);
            final ParentDao parentDao = JdbcUtil.createDao(ParentDao.class, source);
            final Parent parent = new Parent();
            parent.setId(1);

            for (int i = 0; i < 3; i++) {
                parentDao.loadJoinEntities(parent, "children", null);
            }

            assertEquals(0, CHILD_PREPARATIONS.get());
            assertEquals(List.of(Level.WARN, Level.DEBUG, Level.DEBUG), events.stream().map(LogEvent::getLevel).toList(),
                    "A miss that must be rechecked is still warned about only once");

            // The warning entry must not turn into a cached miss: the routed target is selected once it matches.
            CHILD_SOURCE.set(source);
            parentDao.loadJoinEntities(parent, "children", null);
            assertEquals(1, CHILD_PREPARATIONS.get());
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
            ROUTED_SOURCE.remove();
            CHILD_SOURCE.remove();
            CHILD_PREPARATIONS.remove();
            QUERIES.remove();
        }
    }

    @Test
    void loadJoinEntities_missForRetiredRoute_doesNotRetainDataSource() throws Exception {
        final ParentDao parentDao = JdbcUtil.createDao(ParentDao.class, dataSource());
        final WeakReference<DataSource> retired = loadOrphansThroughTransientRoute(parentDao);

        for (int attempt = 0; attempt < 50 && retired.get() != null; attempt++) {
            System.gc();
            Thread.sleep(20);
        }

        assertNull(retired.get(), "The join-DAO miss cache must not keep a retired routed data source reachable");
    }

    // Orphan has no DAO at all, so the miss is cached (no routed candidate can exist).
    private static WeakReference<DataSource> loadOrphansThroughTransientRoute(final ParentDao parentDao) throws SQLException {
        final DataSource transientSource = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] { DataSource.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "transient route";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        final PreparedQuery query = mock(PreparedQuery.class, RETURNS_SELF);
        when(query.list(Orphan.class)).thenReturn(List.of());
        QUERIES.get().put(transientSource, query);
        ROUTED_SOURCE.set(transientSource);

        try {
            final Parent parent = new Parent();
            parent.setId(1);
            parentDao.loadJoinEntities(parent, "orphans", null);
            verify(query).list(Orphan.class);
        } finally {
            ROUTED_SOURCE.remove();
            QUERIES.remove();
        }

        return new WeakReference<>(transientSource);
    }

    private static DataSource dataSource() throws SQLException {
        final DataSource source = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        when(metadata.getDatabaseProductName()).thenReturn("H2");
        when(metadata.getDatabaseProductVersion()).thenReturn("2");
        return source;
    }

    interface ParentDao extends Dao<Parent, ParentDao>, JoinEntityHelper<Parent, ParentDao> {
        @Override
        default DataSource dataSource() {
            return ROUTED_SOURCE.get();
        }

        @Override
        default PreparedQuery prepareQuery(final String sql) {
            return QUERIES.get().get(dataSource());
        }
    }

    interface RoutedChildDao extends Dao<Child, RoutedChildDao> {
        @Override
        default DataSource dataSource() {
            return CHILD_SOURCE.get();
        }

        @Override
        default PreparedQuery prepareQuery(final String sql) {
            CHILD_PREPARATIONS.set(CHILD_PREPARATIONS.get() + 1);
            return QUERIES.get().get(dataSource());
        }
    }

    interface ChildDao extends Dao<Child, ChildDao> {
        @Override
        default PreparedQuery prepareQuery(final String sql) {
            return QUERIES.get().get(dataSource());
        }
    }

    @Data
    public static class Parent {
        private long id;
        @JoinedBy("id=parentId")
        private List<Child> children;
        @JoinedBy("id=parentId")
        private List<Orphan> orphans;
    }

    @Data
    public static class Child {
        private long parentId;
    }

    @Data
    public static class Orphan {
        private long parentId;
    }
}
