package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Predicate;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.ImmutableList;
import com.landawn.abacus.util.RowDataset;

/**
 * Reflectively verifies that every public method with a {@link FunctionalInterface} parameter
 * rejects {@code null} for that parameter with {@link IllegalArgumentException}.
 *
 * <p>{@link JdbcCodeGenerationUtil.EntityCodeConfig} converter setters intentionally accept {@code null}
 * (optional config → default converters) and are therefore excluded.</p>
 */
public class FunctionalInterfaceNullValidationTest extends TestBase {

    static final class TestQuery extends AbstractQuery<PreparedStatement, TestQuery> {
        TestQuery(final PreparedStatement stmt) {
            super(stmt);
        }
    }

    @Test
    public void testPublicMethodsRejectNullFunctionalArguments() throws Exception {
        int validationCount = 0;

        validationCount += assertNullFunctionalArgumentsRejected(AbstractQuery.class, FunctionalInterfaceNullValidationTest::newTestQuery, method -> true);
        validationCount += assertNullFunctionalArgumentsRejected(CallableQuery.class, FunctionalInterfaceNullValidationTest::newCallableQuery, method -> true);
        validationCount += assertNullFunctionalArgumentsRejected(NamedQuery.class, FunctionalInterfaceNullValidationTest::newNamedQuery, method -> true);
        validationCount += assertNullFunctionalArgumentsRejected(DataTransferUtil.class, () -> null, method -> true);
        validationCount += assertNullFunctionalArgumentsRejected(DataTransferUtil.DatasetImportBuilder.class,
                () -> DataTransferUtil.importFrom(mock(RowDataset.class)), method -> true);
        validationCount += assertNullFunctionalArgumentsRejected(DataTransferUtil.RowImportBuilder.class,
                () -> DataTransferUtil.importFrom(Collections.emptyIterator()), method -> true);
        validationCount += assertNullFunctionalArgumentsRejected(DataTransferUtil.CopyFromDataSource.class,
                () -> DataTransferUtil.copyFrom(mock(DataSource.class), "select 1"), method -> true);
        validationCount += assertNullFunctionalArgumentsRejected(DataTransferUtil.CopyFromConnection.class,
                () -> DataTransferUtil.copyFrom(mock(Connection.class), "select 1"), method -> true);
        validationCount += assertNullFunctionalArgumentsRejected(DataTransferUtil.CopyFromStatement.class,
                () -> DataTransferUtil.copyFrom(mock(PreparedStatement.class)), method -> true);
        validationCount += assertNullFunctionalArgumentsRejected(Jdbc.RowMapper.RowMapperBuilder.class, Jdbc.RowMapper::builder,
                method -> method.getName().equals("to"));
        validationCount += assertNullFunctionalArgumentsRejected(Jdbc.HandlerFactory.class, () -> null, method -> true);
        validationCount += assertNullFunctionalArgumentsRejected(JdbcUtil.class, () -> null, method -> true);
        validationCount += assertNullFunctionalArgumentsRejected(SqlTransaction.class, FunctionalInterfaceNullValidationTest::newSqlTransaction,
                method -> true);

        // Guard against accidental silent filter/skip regressions if methods are skipped.
        assertEquals(259, validationCount);
    }

    private static int assertNullFunctionalArgumentsRejected(final Class<?> declaringClass, final ThrowingSupplier<?> targetSupplier,
            final Predicate<Method> methodFilter) throws Exception {
        int validationCount = 0;

        for (final Method method : declaringClass.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || method.isBridge() || method.isSynthetic() || !methodFilter.test(method)) {
                continue;
            }

            final Class<?>[] parameterTypes = method.getParameterTypes();

            for (int targetParameterIndex = 0; targetParameterIndex < parameterTypes.length; targetParameterIndex++) {
                if (!isFunctionalInterface(parameterTypes[targetParameterIndex])) {
                    continue;
                }

                final Object[] args = new Object[parameterTypes.length];

                for (int parameterIndex = 0; parameterIndex < parameterTypes.length; parameterIndex++) {
                    args[parameterIndex] = parameterIndex == targetParameterIndex ? null : validArgument(parameterTypes[parameterIndex]);
                }

                final Object target = Modifier.isStatic(method.getModifiers()) ? null : targetSupplier.get();
                final String failureMessage = method.toGenericString() + " must reject null parameter at index " + targetParameterIndex;
                final InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () -> method.invoke(target, args), failureMessage);

                assertTrue(thrown.getCause() instanceof IllegalArgumentException, () -> failureMessage + ", but threw "
                        + (thrown.getCause() == null ? "null" : thrown.getCause().getClass().getName() + ": " + thrown.getCause().getMessage()));
                validationCount++;
            }
        }

        return validationCount;
    }

    private static boolean isFunctionalInterface(final Class<?> type) {
        return type.isInterface() && type.isAnnotationPresent(FunctionalInterface.class);
    }

    private static Object validArgument(final Class<?> type) {
        if (isFunctionalInterface(type)) {
            return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> defaultValue(method.getReturnType()));
        } else if (type == boolean.class || type == Boolean.class) {
            return false;
        } else if (type == byte.class || type == Byte.class) {
            return (byte) 1;
        } else if (type == short.class || type == Short.class) {
            return (short) 1;
        } else if (type == int.class || type == Integer.class) {
            return 1;
        } else if (type == long.class || type == Long.class) {
            return 1L;
        } else if (type == float.class || type == Float.class) {
            return 1F;
        } else if (type == double.class || type == Double.class) {
            return 1D;
        } else if (type == char.class || type == Character.class) {
            return 'a';
        } else if (type == String.class || type == CharSequence.class) {
            return "value";
        } else if (type == Class.class) {
            return Object.class;
        } else if (type == File.class) {
            return new File("unused");
        } else if (Reader.class.isAssignableFrom(type)) {
            return new StringReader("");
        } else if (type == Duration.class) {
            return Duration.ZERO;
        } else if (type == Optional.class) {
            return Optional.empty();
        } else if (type.isArray()) {
            return newArray(type.getComponentType());
        } else if (Set.class.isAssignableFrom(type)) {
            return Set.of("value");
        } else if (List.class.isAssignableFrom(type) || Collection.class == type) {
            return List.of("value");
        } else if (Map.class.isAssignableFrom(type)) {
            return Map.of();
        } else if (Iterator.class.isAssignableFrom(type)) {
            return Collections.emptyIterator();
        } else if (Iterable.class.isAssignableFrom(type)) {
            return List.of("value");
        } else if (Executor.class.isAssignableFrom(type)) {
            return (Executor) Runnable::run;
        } else if (Dataset.class.isAssignableFrom(type)) {
            return mock(RowDataset.class);
        } else if (type.isEnum()) {
            return type.getEnumConstants()[0];
        } else {
            return mock(type);
        }
    }

    private static Object newArray(final Class<?> componentType) {
        final Object array = Array.newInstance(componentType, 1);

        if (componentType == String.class) {
            Array.set(array, 0, "value");
        } else if (componentType == int.class) {
            Array.setInt(array, 0, 1);
        }

        return array;
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        } else if (type == boolean.class) {
            return false;
        } else if (type == char.class) {
            return '\0';
        } else if (type == byte.class) {
            return (byte) 0;
        } else if (type == short.class) {
            return (short) 0;
        } else if (type == int.class) {
            return 0;
        } else if (type == long.class) {
            return 0L;
        } else if (type == float.class) {
            return 0F;
        } else {
            return 0D;
        }
    }

    private static TestQuery newTestQuery() throws Exception {
        final PreparedStatement stmt = mock(PreparedStatement.class);

        when(stmt.getConnection()).thenReturn(mock(Connection.class));

        return new TestQuery(stmt);
    }

    private static CallableQuery newCallableQuery() throws Exception {
        final CallableStatement stmt = mock(CallableStatement.class);

        when(stmt.getConnection()).thenReturn(mock(Connection.class));

        return new CallableQuery(stmt);
    }

    private static NamedQuery newNamedQuery() throws Exception {
        final PreparedStatement stmt = mock(PreparedStatement.class);
        final ParsedSql parsedSql = mock(ParsedSql.class);

        when(stmt.getConnection()).thenReturn(mock(Connection.class));
        when(parsedSql.namedParameters()).thenReturn(ImmutableList.of("param1"));
        when(parsedSql.parameterCount()).thenReturn(1);
        when(parsedSql.originalSql()).thenReturn("SELECT 1 WHERE id = :param1");

        return new NamedQuery(stmt, parsedSql);
    }

    private static SqlTransaction newSqlTransaction() throws Exception {
        final DataSource dataSource = mock(DataSource.class);
        final Connection connection = mock(Connection.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);

        return new SqlTransaction(dataSource, connection, IsolationLevel.READ_COMMITTED, SqlTransaction.CreatedBy.JDBC_UTIL, false);
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
