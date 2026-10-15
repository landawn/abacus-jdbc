package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.function.Function;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.AbstractQuery;
import com.landawn.abacus.jdbc.CallableQuery;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.NamedQuery;
import com.landawn.abacus.jdbc.PreparedQuery;
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
}
