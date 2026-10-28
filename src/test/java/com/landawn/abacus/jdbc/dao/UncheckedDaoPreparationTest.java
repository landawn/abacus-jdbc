package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.annotation.JoinedBy;
import com.landawn.abacus.annotation.Table;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.AbstractQuery;
import com.landawn.abacus.jdbc.CallableQuery;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.NamedQuery;
import com.landawn.abacus.jdbc.PreparedQuery;
import com.landawn.abacus.query.Filters;
import com.landawn.abacus.query.ParsedSql;

class UncheckedDaoPreparationTest extends TestBase {

    private static final String SQL = "select ?";
    private static final String NAMED_SQL = "select :value";
    private static final String CALL_SQL = "call sample(?)";

    @ParameterizedTest(name = "{0}")
    @MethodSource("preparations")
    void preparesBindsAndReleasesOwnedResources(final Preparation preparation) throws Exception {
        final CallableStatement statement = mock(CallableStatement.class);
        final Connection connection = connectionReturning(statement, null);
        when(statement.getConnection()).thenReturn(connection);
        final DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        final PreparationDao dao = mock(PreparationDao.class, CALLS_REAL_METHODS);
        when(dao.dataSource()).thenReturn(dataSource);

        try (AbstractQuery<?, ?> query = preparation.create.apply(dao)) {
            assertInstanceOf(preparation.queryType, query);
            query.configureStatement(actual -> assertSame(statement, actual));
            if (query instanceof NamedQuery namedQuery) {
                namedQuery.setInt("value", 42);
            } else {
                query.setInt(1, 42);
            }
            verify(statement).setInt(1, 42);
            verify(connection, never()).close();
            preparation.verifyPreparation(connection);
            if (preparation.large) {
                verify(statement).setFetchDirection(ResultSet.FETCH_FORWARD);
                verify(statement).setFetchSize(JdbcUtil.DEFAULT_FETCH_SIZE_FOR_LARGE_RESULT_SET);
            }
        }

        verify(statement).close();
        verify(connection).close();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("preparations")
    void wrapsPreparationFailureAndReleasesAcquiredConnection(final Preparation preparation) throws Exception {
        final SQLException failure = new SQLException("preparation failed", "42000", 17);
        final Connection connection = connectionReturning(null, failure);
        final DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        final PreparationDao dao = mock(PreparationDao.class, CALLS_REAL_METHODS);
        when(dao.dataSource()).thenReturn(dataSource);

        final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class, () -> preparation.create.apply(dao));

        assertSame(failure, thrown.getCause());
        preparation.verifyPreparation(connection);
        verify(connection).close();
    }

    // Pins the documented connection-acquisition contract of the unchecked prepare* defaults: Spring wraps every
    // acquisition failure in CannotGetJdbcConnectionException, but DaoUtil.uncheckedSql translates one caused by a
    // SQLException to UncheckedSQLException, so only a data source returning null (or throwing an
    // IllegalStateException) surfaces as CannotGetJdbcConnectionException.
    @ParameterizedTest(name = "{0}")
    @MethodSource("preparations")
    void mapsConnectionAcquisitionFailures(final Preparation preparation) throws Exception {
        final SQLException failure = new SQLException("no connection", "08001");
        final DataSource failingDataSource = mock(DataSource.class);
        when(failingDataSource.getConnection()).thenThrow(failure);
        final PreparationDao failingDao = mock(PreparationDao.class, CALLS_REAL_METHODS);
        when(failingDao.dataSource()).thenReturn(failingDataSource);

        final UncheckedSQLException translated = assertThrows(UncheckedSQLException.class, () -> preparation.create.apply(failingDao));
        assertSame(failure, translated.getCause());

        final DataSource nullDataSource = mock(DataSource.class);
        final PreparationDao nullDao = mock(PreparationDao.class, CALLS_REAL_METHODS);
        when(nullDao.dataSource()).thenReturn(nullDataSource);

        assertInstanceOf(IllegalStateException.class, assertThrows(CannotGetJdbcConnectionException.class, () -> preparation.create.apply(nullDao)).getCause());
    }

    // Pins the per-operation contract documented on the unchecked DAO interfaces: operations that prepare through the
    // unchecked prepare* methods translate an acquisition failure caused by a SQLException, while CRUD inserts and operations
    // that open a transaction first let the CannotGetJdbcConnectionException through. A data source returning null always
    // surfaces as CannotGetJdbcConnectionException.
    @ParameterizedTest(name = "{0}")
    @MethodSource("daoOperations")
    void mapsConnectionAcquisitionFailuresOfDaoOperations(final DaoOperation operation) throws Exception {
        final AcquisitionDao dao = acquisitionDao();

        try {
            ACQUISITION_MODE.set(1);
            final RuntimeException thrown = assertThrows(RuntimeException.class, () -> operation.call.accept(dao));
            final Class<? extends RuntimeException> expected = operation.translated ? UncheckedSQLException.class : CannotGetJdbcConnectionException.class;
            assertInstanceOf(expected, thrown);
            assertInstanceOf(SQLException.class, thrown.getCause());

            ACQUISITION_MODE.set(2);
            assertInstanceOf(IllegalStateException.class, assertThrows(CannotGetJdbcConnectionException.class, () -> operation.call.accept(dao)).getCause());
        } finally {
            ACQUISITION_MODE.set(0);
        }
    }

    // Pins the contract documented on UncheckedJoinEntityReadOps/UncheckedJoinEntityDeleteOps: join entities are loaded and
    // deleted through the DAO registered for the join entity class on the same data source, or through this DAO if there is
    // none. This (unchecked) DAO translates a CannotGetJdbcConnectionException caused by a SQLException to
    // UncheckedSQLException; a checked registered join DAO (a CrudDao) lets it through, as does a sequential delete that
    // opens a transaction first (more than one entity, or more than one join property); the Executor overloads translate
    // it whichever DAO runs their tasks. A data source returning null always surfaces as CannotGetJdbcConnectionException
    // caused by IllegalStateException.
    @ParameterizedTest(name = "{0}")
    @MethodSource("joinDaoOperations")
    void mapsConnectionAcquisitionFailuresOfJoinOperations(final JoinDaoOperation operation) throws Exception {
        final JoinParentDao dao = joinParentDao();

        // The operation is valid in itself: with the data source reachable it runs against the (empty) join tables.
        operation.call.accept(dao);

        try {
            ACQUISITION_MODE.set(1);
            final RuntimeException thrown = assertThrows(RuntimeException.class, () -> operation.call.accept(dao));
            final Class<? extends RuntimeException> expected = operation.translated ? UncheckedSQLException.class : CannotGetJdbcConnectionException.class;
            assertInstanceOf(expected, thrown);
            assertInstanceOf(SQLException.class, thrown.getCause());
            assertEquals("08001", ((SQLException) thrown.getCause()).getSQLState());

            ACQUISITION_MODE.set(2);
            assertInstanceOf(IllegalStateException.class, assertThrows(CannotGetJdbcConnectionException.class, () -> operation.call.accept(dao)).getCause());
        } finally {
            ACQUISITION_MODE.set(0);
        }
    }

    private static Stream<JoinDaoOperation> joinDaoOperations() {
        final Executor executor = Runnable::run;

        return Stream.of(
                // (a) no DAO is registered for OwnChild: its joins run through this DAO's unchecked prepareQuery
                new JoinDaoOperation("loadJoinEntities of one entity through this DAO", dao -> dao.loadJoinEntities(joinParent(1L), "ownChildren"), true),
                new JoinDaoOperation("loadJoinEntities of two entities through this DAO",
                        dao -> dao.loadJoinEntities(List.of(joinParent(1L), joinParent(2L)), "ownChildren"), true),
                new JoinDaoOperation("loadJoinEntities by class through this DAO", dao -> dao.loadJoinEntities(joinParent(1L), OwnChild.class), true),
                new JoinDaoOperation("sequential loadJoinEntities of one property through this DAO",
                        dao -> dao.loadJoinEntities(joinParent(1L), List.of("ownChildren")), true),
                new JoinDaoOperation("deleteJoinEntities of one entity through this DAO", dao -> dao.deleteJoinEntities(joinParent(1L), "ownChildren"), true),
                new JoinDaoOperation("deleteJoinEntities of a one-entity collection through this DAO",
                        dao -> dao.deleteJoinEntities(List.of(joinParent(1L)), "ownChildren"), true),
                // (b) a checked CrudDao is registered for CheckedChild on the same data source
                new JoinDaoOperation("loadJoinEntities of one entity through the checked join DAO",
                        dao -> dao.loadJoinEntities(joinParent(1L), "checkedChildren"), false),
                new JoinDaoOperation("loadJoinEntities of two entities through the checked join DAO",
                        dao -> dao.loadJoinEntities(List.of(joinParent(1L), joinParent(2L)), "checkedChildren"), false),
                new JoinDaoOperation("sequential loadJoinEntities of one property through the checked join DAO",
                        dao -> dao.loadJoinEntities(joinParent(1L), List.of("checkedChildren")), false),
                new JoinDaoOperation("deleteJoinEntities of one entity through the checked join DAO",
                        dao -> dao.deleteJoinEntities(joinParent(1L), "checkedChildren"), false),
                // (c) a sequential delete of more than one entity or join property opens a transaction first
                new JoinDaoOperation("deleteJoinEntities of two entities through this DAO (transaction)",
                        dao -> dao.deleteJoinEntities(List.of(joinParent(1L), joinParent(2L)), "ownChildren"), false),
                new JoinDaoOperation("deleteJoinEntities of two properties (transaction)",
                        dao -> dao.deleteJoinEntities(joinParent(1L), List.of("ownChildren", "checkedChildren")), false),
                new JoinDaoOperation("deleteJoinEntities of two entities and two properties (transaction)",
                        dao -> dao.deleteJoinEntities(List.of(joinParent(1L), joinParent(2L)), List.of("ownChildren", "checkedChildren")), false),
                new JoinDaoOperation("deleteAllJoinEntities (transaction)", dao -> dao.deleteAllJoinEntities(joinParent(1L)), false),
                // (d) the Executor overloads translate whichever DAO runs their tasks
                new JoinDaoOperation("loadJoinEntities through the checked join DAO with an Executor",
                        dao -> dao.loadJoinEntities(joinParent(1L), List.of("checkedChildren"), executor), true),
                new JoinDaoOperation("loadJoinEntities of two entities through the checked join DAO with an Executor",
                        dao -> dao.loadJoinEntities(List.of(joinParent(1L), joinParent(2L)), List.of("checkedChildren"), executor), true),
                new JoinDaoOperation("loadAllJoinEntities with an Executor", dao -> dao.loadAllJoinEntities(joinParent(1L), executor), true),
                new JoinDaoOperation("deleteJoinEntities through the checked join DAO with an Executor",
                        dao -> dao.deleteJoinEntities(joinParent(1L), List.of("checkedChildren"), executor), true),
                new JoinDaoOperation("deleteJoinEntities of two entities through this DAO with an Executor",
                        dao -> dao.deleteJoinEntities(List.of(joinParent(1L), joinParent(2L)), List.of("ownChildren"), executor), true),
                new JoinDaoOperation("deleteAllJoinEntities with an Executor", dao -> dao.deleteAllJoinEntities(joinParent(1L), executor), true));
    }

    private record JoinDaoOperation(String name, Consumer<JoinParentDao> call, boolean translated) {
        @Override
        public String toString() {
            return name;
        }
    }

    // Pins the contract documented on UncheckedDaoBase's Condition-based prepare* overloads: the proxy implements them through
    // the unchecked prepareQuery(String)/prepareNamedQuery(String) defaults, so an acquisition failure caused by a SQLException
    // is translated to UncheckedSQLException, while a data source returning null still surfaces as
    // CannotGetJdbcConnectionException caused by IllegalStateException.
    @ParameterizedTest(name = "{0}")
    @MethodSource("conditionPreparations")
    void mapsConnectionAcquisitionFailuresOfConditionPreparations(final ConditionPreparation preparation) throws Exception {
        final AcquisitionDao dao = acquisitionDao();

        try (AbstractQuery<?, ?> query = preparation.create.apply(dao)) {
            assertInstanceOf(preparation.queryType, query);
        }

        try {
            ACQUISITION_MODE.set(1);
            final UncheckedSQLException translated = assertThrows(UncheckedSQLException.class, () -> preparation.create.apply(dao));
            assertInstanceOf(SQLException.class, translated.getCause());
            assertEquals("08001", ((SQLException) translated.getCause()).getSQLState());

            ACQUISITION_MODE.set(2);
            assertInstanceOf(IllegalStateException.class, assertThrows(CannotGetJdbcConnectionException.class, () -> preparation.create.apply(dao)).getCause());
        } finally {
            ACQUISITION_MODE.set(0);
        }
    }

    private static Stream<ConditionPreparation> conditionPreparations() {
        return Stream.of(new ConditionPreparation("prepared by condition", dao -> dao.prepareQuery(Filters.eq("name", "a")), PreparedQuery.class),
                new ConditionPreparation("prepared props by condition", dao -> dao.prepareQuery(List.of("id"), Filters.eq("name", "a")), PreparedQuery.class),
                new ConditionPreparation("prepared large by condition", dao -> dao.prepareQueryForLargeResult(Filters.eq("name", "a")), PreparedQuery.class),
                new ConditionPreparation("prepared props large by condition", dao -> dao.prepareQueryForLargeResult(List.of("id"), Filters.eq("name", "a")),
                        PreparedQuery.class),
                new ConditionPreparation("named by condition", dao -> dao.prepareNamedQuery(Filters.eq("name", "a")), NamedQuery.class),
                new ConditionPreparation("named props by condition", dao -> dao.prepareNamedQuery(List.of("id"), Filters.eq("name", "a")), NamedQuery.class),
                new ConditionPreparation("named large by condition", dao -> dao.prepareNamedQueryForLargeResult(Filters.eq("name", "a")), NamedQuery.class),
                new ConditionPreparation("named props large by condition", dao -> dao.prepareNamedQueryForLargeResult(List.of("id"), Filters.eq("name", "a")),
                        NamedQuery.class));
    }

    private record ConditionPreparation(String name, Function<AcquisitionDao, AbstractQuery<?, ?>> create, Class<?> queryType) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static Stream<DaoOperation> daoOperations() {
        return Stream.of(new DaoOperation("list", dao -> dao.list(Filters.eq("name", "a")), true),
                new DaoOperation("update property", dao -> dao.update("name", "b", Filters.eq("id", 1L)), true),
                new DaoOperation("update by ID", dao -> dao.update("name", "b", 1L), true),
                new DaoOperation("save", dao -> dao.save(acquisitionEntity(null)), true),
                // The multi-element batchSave/batchDelete/batchDeleteByIds @throws documentation names these untranslated cases.
                new DaoOperation("batchSave of one entity", dao -> dao.batchSave(List.of(acquisitionEntity(null)), 10), true),
                new DaoOperation(
                        "batchSave of two entities in one batch", dao -> dao.batchSave(List.of(acquisitionEntity(null), acquisitionEntity(null)), 10), false),
                new DaoOperation("batchDelete of one entity", dao -> dao.batchDelete(List.of(acquisitionEntity(1L)), 10), true),
                new DaoOperation("batchDelete of two entities in one batch", dao -> dao.batchDelete(List.of(acquisitionEntity(1L), acquisitionEntity(2L)), 10),
                        false),
                new DaoOperation("batchDeleteByIds of two IDs in one batch", dao -> dao.batchDeleteByIds(List.of(1L, 2L), 10), false),
                new DaoOperation("batchUpdate of one entity", dao -> dao.batchUpdate(List.of(acquisitionEntity(1L)), 10), true),
                new DaoOperation("insert", dao -> dao.insert(acquisitionEntity(null)), false),
                new DaoOperation("batchInsert in one batch", dao -> dao.batchInsert(List.of(acquisitionEntity(null)), 10), false),
                new DaoOperation("batchUpdate of two entities in one batch", dao -> dao.batchUpdate(List.of(acquisitionEntity(1L), acquisitionEntity(2L)), 10),
                        false),
                new DaoOperation("batchUpdate across batches", dao -> dao.batchUpdate(List.of(acquisitionEntity(1L), acquisitionEntity(2L)), 1), false),
                // The lookup acquires first, through the unchecked prepare* methods; several entities open a transaction first.
                new DaoOperation("upsert", dao -> dao.upsert(acquisitionEntity(1L)), true),
                new DaoOperation("batchUpsert of one entity", dao -> dao.batchUpsert(List.of(acquisitionEntity(1L))), true),
                new DaoOperation("batchUpsert of two entities", dao -> dao.batchUpsert(List.of(acquisitionEntity(1L), acquisitionEntity(2L))), false));
    }

    private static Stream<Preparation> preparations() {
        final ParsedSql parsed = ParsedSql.parse(NAMED_SQL);
        return Stream.of(new Preparation("prepared", dao -> dao.prepareQuery(SQL), PreparedQuery.class, 0, false),
                new Preparation("prepared keys", dao -> dao.prepareQuery(SQL, true), PreparedQuery.class, 1, false),
                new Preparation("prepared key indexes", dao -> dao.prepareQuery(SQL, new int[] { 1 }), PreparedQuery.class, 2, false),
                new Preparation("prepared key names", dao -> dao.prepareQuery(SQL, new String[] { "id" }), PreparedQuery.class, 3, false),
                new Preparation("prepared creator", dao -> dao.prepareQuery(SQL, Connection::prepareStatement), PreparedQuery.class, 0, false),
                new Preparation("prepared large", dao -> dao.prepareQueryForLargeResult(SQL), PreparedQuery.class, 0, true),
                new Preparation("named", dao -> dao.prepareNamedQuery(NAMED_SQL), NamedQuery.class, 0, false),
                new Preparation("named keys", dao -> dao.prepareNamedQuery(NAMED_SQL, true), NamedQuery.class, 1, false),
                new Preparation("named key indexes", dao -> dao.prepareNamedQuery(NAMED_SQL, new int[] { 1 }), NamedQuery.class, 2, false),
                new Preparation("named key names", dao -> dao.prepareNamedQuery(NAMED_SQL, new String[] { "id" }), NamedQuery.class, 3, false),
                new Preparation("named creator", dao -> dao.prepareNamedQuery(NAMED_SQL, Connection::prepareStatement), NamedQuery.class, 0, false),
                new Preparation("named large", dao -> dao.prepareNamedQueryForLargeResult(NAMED_SQL), NamedQuery.class, 0, true),
                new Preparation("parsed", dao -> dao.prepareNamedQuery(parsed), NamedQuery.class, 0, false),
                new Preparation("parsed keys", dao -> dao.prepareNamedQuery(parsed, true), NamedQuery.class, 1, false),
                new Preparation("parsed key indexes", dao -> dao.prepareNamedQuery(parsed, new int[] { 1 }), NamedQuery.class, 2, false),
                new Preparation("parsed key names", dao -> dao.prepareNamedQuery(parsed, new String[] { "id" }), NamedQuery.class, 3, false),
                new Preparation("parsed creator", dao -> dao.prepareNamedQuery(parsed, Connection::prepareStatement), NamedQuery.class, 0, false),
                new Preparation("parsed large", dao -> dao.prepareNamedQueryForLargeResult(parsed), NamedQuery.class, 0, true),
                new Preparation("callable", dao -> dao.prepareCallableQuery(CALL_SQL), CallableQuery.class, 0, false),
                new Preparation("callable creator", dao -> dao.prepareCallableQuery(CALL_SQL, Connection::prepareCall), CallableQuery.class, 0, false));
    }

    private static Connection connectionReturning(final PreparedStatement statement, final SQLException failure) {
        return mock(Connection.class, call -> {
            if (call.getMethod().getName().equals("prepareStatement") || call.getMethod().getName().equals("prepareCall")) {
                if (failure != null) {
                    throw failure;
                }
                return statement;
            }
            return RETURNS_DEFAULTS.answer(call);
        });
    }

    private record Preparation(String name, Function<PreparationDao, AbstractQuery<?, ?>> create, Class<?> queryType, int keys, boolean large) {
        void verifyPreparation(final Connection connection) throws SQLException {
            if (queryType == CallableQuery.class) {
                verify(connection).prepareCall(CALL_SQL);
            } else {
                switch (keys) {
                    case 0 -> verify(connection).prepareStatement(SQL);
                    case 1 -> verify(connection).prepareStatement(SQL, Statement.RETURN_GENERATED_KEYS);
                    case 2 -> verify(connection).prepareStatement(SQL, new int[] { 1 });
                    case 3 -> verify(connection).prepareStatement(SQL, new String[] { "id" });
                    default -> throw new AssertionError("Unknown generated-key mode: " + keys);
                }
            }
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private interface PreparationDao extends UncheckedDao<UncheckedDaoTest.TestEntity, PreparationDao> {
    }

    // 0 = delegate to H2, 1 = getConnection throws SQLException, 2 = getConnection returns null
    private static final AtomicInteger ACQUISITION_MODE = new AtomicInteger();
    private static DataSource switchingDataSource;
    private static AcquisitionDao acquisitionDao;
    private static JoinParentDao joinParentDao;
    private static CheckedChildDao checkedChildDao;

    private static synchronized AcquisitionDao acquisitionDao() throws SQLException {
        if (acquisitionDao == null) {
            acquisitionDao = JdbcUtil.createDao(AcquisitionDao.class, switchingDataSource());
        }

        return acquisitionDao;
    }

    private static synchronized JoinParentDao joinParentDao() throws SQLException {
        if (joinParentDao == null) {
            final DataSource switching = switchingDataSource();

            // Registered before the first join operation: a join DAO is looked up among the DAOs created for the join entity
            // class on the same data source. OwnChild deliberately has none, so its joins run through the parent DAO itself.
            checkedChildDao = JdbcUtil.createDao(CheckedChildDao.class, switching);
            joinParentDao = JdbcUtil.createDao(JoinParentDao.class, switching);
        }

        return joinParentDao;
    }

    private static synchronized DataSource switchingDataSource() throws SQLException {
        if (switchingDataSource == null) {
            final DataSource h2 = JdbcUtil.createHikariDataSource("jdbc:h2:mem:unchecked_acquisition;DB_CLOSE_DELAY=-1", "sa", "");

            try (Connection conn = h2.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS unchecked_acquisition (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(32))");
                stmt.execute("CREATE TABLE IF NOT EXISTS unchecked_acquisition_parent (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(32))");
                stmt.execute("CREATE TABLE IF NOT EXISTS unchecked_acquisition_own_child (id BIGINT AUTO_INCREMENT PRIMARY KEY, parent_id BIGINT)");
                stmt.execute("CREATE TABLE IF NOT EXISTS unchecked_acquisition_checked_child (id BIGINT AUTO_INCREMENT PRIMARY KEY, parent_id BIGINT)");
            }

            switchingDataSource = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] { DataSource.class },
                    (proxy, method, args) -> {
                        if (method.getName().equals("getConnection") && ACQUISITION_MODE.get() != 0) {
                            if (ACQUISITION_MODE.get() == 1) {
                                throw new SQLException("no connection", "08001");
                            }
                            return null;
                        }
                        try {
                            return method.invoke(h2, args);
                        } catch (final InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        return switchingDataSource;
    }

    private static JoinParent joinParent(final Long id) {
        final JoinParent entity = new JoinParent();
        entity.setId(id);
        entity.setName("p");
        return entity;
    }

    private static AcquisitionEntity acquisitionEntity(final Long id) {
        final AcquisitionEntity entity = new AcquisitionEntity();
        entity.setId(id);
        entity.setName("a");
        return entity;
    }

    private record DaoOperation(String name, Consumer<AcquisitionDao> call, boolean translated) {
        @Override
        public String toString() {
            return name;
        }
    }

    @Table("unchecked_acquisition")
    public static class AcquisitionEntity {
        @Id
        private Long id;
        private String name;

        public Long getId() {
            return id;
        }

        public void setId(final Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }
    }

    public interface AcquisitionDao extends UncheckedCrudDao<AcquisitionEntity, Long, AcquisitionDao> {
    }

    public interface CheckedAcquisitionDao extends CrudDao<AcquisitionEntity, Long, CheckedAcquisitionDao> {
    }

    public interface CheckedParentDao extends CrudDao<JoinParent, Long, CheckedParentDao>, CrudJoinEntityHelper<JoinParent, Long, CheckedParentDao> {
    }

    @org.junit.jupiter.api.Test
    void checkedDaoOperationsPropagateSpringAcquisitionFailures() throws Exception {
        final DataSource ds = switchingDataSource();
        final CheckedAcquisitionDao dao = JdbcUtil.createDao(CheckedAcquisitionDao.class, ds);
        final CheckedParentDao parent = JdbcUtil.createDao(CheckedParentDao.class, ds);
        try {
            for (final int mode : new int[] { 1, 2 }) {
                ACQUISITION_MODE.set(mode);
                final Class<? extends Throwable> cause = mode == 1 ? SQLException.class : IllegalStateException.class;
                assertInstanceOf(cause, assertThrows(CannotGetJdbcConnectionException.class, () -> dao.prepareQuery("SELECT 1")).getCause());
                assertInstanceOf(cause, assertThrows(CannotGetJdbcConnectionException.class, () -> dao.insert(acquisitionEntity(null))).getCause());
                assertInstanceOf(cause,
                        assertThrows(CannotGetJdbcConnectionException.class, () -> dao.batchInsert(List.of(acquisitionEntity(null), acquisitionEntity(null))))
                                .getCause());
                assertInstanceOf(cause, assertThrows(CannotGetJdbcConnectionException.class, () -> dao.deleteById(1L)).getCause());
                assertInstanceOf(cause, assertThrows(CannotGetJdbcConnectionException.class, () -> dao.upsert(acquisitionEntity(1L))).getCause());
                assertInstanceOf(cause,
                        assertThrows(CannotGetJdbcConnectionException.class, () -> dao.batchUpsert(List.of(acquisitionEntity(1L), acquisitionEntity(2L))))
                                .getCause());
                assertInstanceOf(cause, assertThrows(CannotGetJdbcConnectionException.class, () -> dao.update("name", "b", Filters.eq("id", 1L))).getCause());
                assertInstanceOf(cause, assertThrows(CannotGetJdbcConnectionException.class, () -> dao.update("name", "b", 1L)).getCause());
                assertInstanceOf(cause,
                        assertThrows(CannotGetJdbcConnectionException.class, () -> parent.loadJoinEntities(joinParent(1L), "ownChildren")).getCause());
                assertInstanceOf(cause, assertThrows(CannotGetJdbcConnectionException.class, () -> parent.get(1L, OwnChild.class)).getCause());
            }
        } finally {
            ACQUISITION_MODE.set(0);
        }
    }

    @Table("unchecked_acquisition_parent")
    public static class JoinParent {
        @Id
        private Long id;
        private String name;
        // No DAO is registered for OwnChild: its joins are loaded/deleted through the parent DAO itself.
        @JoinedBy("id=OwnChild.parentId")
        private List<OwnChild> ownChildren;
        // A checked CrudDao is registered for CheckedChild (see joinParentDao()).
        @JoinedBy("id=CheckedChild.parentId")
        private List<CheckedChild> checkedChildren;

        public Long getId() {
            return id;
        }

        public void setId(final Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }

        public List<OwnChild> getOwnChildren() {
            return ownChildren;
        }

        public void setOwnChildren(final List<OwnChild> ownChildren) {
            this.ownChildren = ownChildren;
        }

        public List<CheckedChild> getCheckedChildren() {
            return checkedChildren;
        }

        public void setCheckedChildren(final List<CheckedChild> checkedChildren) {
            this.checkedChildren = checkedChildren;
        }
    }

    @Table("unchecked_acquisition_own_child")
    public static class OwnChild {
        @Id
        private Long id;
        private Long parentId;

        public Long getId() {
            return id;
        }

        public void setId(final Long id) {
            this.id = id;
        }

        public Long getParentId() {
            return parentId;
        }

        public void setParentId(final Long parentId) {
            this.parentId = parentId;
        }
    }

    @Table("unchecked_acquisition_checked_child")
    public static class CheckedChild {
        @Id
        private Long id;
        private Long parentId;

        public Long getId() {
            return id;
        }

        public void setId(final Long id) {
            this.id = id;
        }

        public Long getParentId() {
            return parentId;
        }

        public void setParentId(final Long parentId) {
            this.parentId = parentId;
        }
    }

    // Non-crud, so the bodies declared in UncheckedJoinEntityReadOps/UncheckedJoinEntityDeleteOps run (the Crud helpers
    // re-declare the join methods).
    public interface JoinParentDao extends UncheckedDao<JoinParent, JoinParentDao>, UncheckedJoinEntityHelper<JoinParent, JoinParentDao> {
    }

    public interface CheckedChildDao extends CrudDao<CheckedChild, Long, CheckedChildDao> {
    }
}
