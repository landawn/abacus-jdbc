package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.annotation.ReadOnly;
import com.landawn.abacus.annotation.Table;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.query.Filters;
import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.query.condition.Condition;
import com.landawn.abacus.query.condition.Criteria;
import com.landawn.abacus.util.Dataset;

/**
 * Reflectively verifies that every public method with a {@link FunctionalInterface} parameter,
 * declared on types in {@code com.landawn.abacus.jdbc.dao}, rejects {@code null} for that
 * parameter with {@link IllegalArgumentException}.
 *
 * <p>Default-method overloads are exercised via Mockito {@code CALLS_REAL_METHODS} where possible;
 * abstract methods implemented by {@link com.landawn.abacus.jdbc.DaoImpl} are exercised against a
 * live H2-backed DAO proxy.</p>
 */
@TestInstance(Lifecycle.PER_CLASS)
public class DaoFunctionalInterfaceNullValidationTest extends TestBase {

    @Table("fi_null_user")
    public static class FiNullUser {
        @Id
        @ReadOnly
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

    public interface FiNullUserDao extends CrudDao<FiNullUser, Long, FiNullUserDao> {
    }

    public interface UncheckedFiNullUserDao extends UncheckedCrudDao<FiNullUser, Long, UncheckedFiNullUserDao> {
    }

    public interface JoinNullUserDao extends CrudDao<FiNullUser, Long, JoinNullUserDao>, CrudJoinEntityHelper<FiNullUser, Long, JoinNullUserDao> {
    }

    public interface UncheckedJoinNullUserDao extends UncheckedCrudDao<FiNullUser, Long, UncheckedJoinNullUserDao>,
            UncheckedCrudJoinEntityHelper<FiNullUser, Long, UncheckedJoinNullUserDao> {
    }

    private DataSource ds;
    private FiNullUserDao checkedDao;
    private UncheckedFiNullUserDao uncheckedDao;

    @BeforeAll
    public void initDb() throws SQLException {
        ds = JdbcUtil.createHikariDataSource("jdbc:h2:mem:dao_fi_null;DB_CLOSE_DELAY=-1", "sa", "");

        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS fi_null_user (" + "id BIGINT AUTO_INCREMENT PRIMARY KEY, " + "name VARCHAR(64))");
        }

        checkedDao = JdbcUtil.createDao(FiNullUserDao.class, ds);
        uncheckedDao = JdbcUtil.createDao(UncheckedFiNullUserDao.class, ds);
    }

    @AfterAll
    public void dropDb() throws SQLException {
        if (ds != null) {
            try (Connection conn = ds.getConnection();
                 Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS fi_null_user");
            }
        }
    }

    @Test
    public void testReadAndStatementDefaultsValidateArgumentsInSignatureOrder() throws Exception {
        final Object checked = Mockito.mock(FiNullUserDao.class, Mockito.CALLS_REAL_METHODS);
        final Object unchecked = Mockito.mock(UncheckedFiNullUserDao.class, Mockito.CALLS_REAL_METHODS);

        for (final Class<?> type : List.of(ReadOps.class, UncheckedReadOps.class, Dao.class, UncheckedDao.class)) {
            final Object target = type.getSimpleName().startsWith("Unchecked") ? unchecked : checked;

            for (final Method method : type.getDeclaredMethods()) {
                if (!method.isDefault() || method.isBridge()) {
                    continue;
                }

                final Class<?>[] parameterTypes = method.getParameterTypes();
                final boolean singleColumn = (method.getName().equals("list") || method.getName().equals("stream"))
                        && parameterTypes[0] == String.class;
                final boolean foreach = method.getName().equals("foreach");
                final boolean statementCreator = method.getName().startsWith("prepare") && parameterTypes.length == 2
                        && isFunctionalInterface(parameterTypes[1]);

                if (!singleColumn && !foreach && !statementCreator) {
                    continue;
                }

                final Object[] args = new Object[parameterTypes.length];
                final int firstRequired = foreach && parameterTypes[0] == Collection.class ? 1 : 0;

                for (int index = firstRequired; index < args.length; index++) {
                    final String parameterName = singleColumn && index == 0 ? "singleSelectPropName"
                            : parameterTypes[index] == Condition.class ? "cond"
                                    : foreach ? "rowConsumer"
                                            : statementCreator ? index == 0 ? method.getName().equals("prepareNamedQuery") ? "namedSql" : "sql"
                                                    : "stmtCreator"
                                                    : index == args.length - 1 ? "rowMapper" : "rowFilter";
                    assertInvalidArgument(method, target, args, parameterName);
                    args[index] = validArgument(parameterTypes[index], method.getName());
                }
            }
        }
    }

    @Test
    public void testJoinDefaultsValidateBeforeReadingMetadata() throws Exception {
        final Object checked = Mockito.mock(JoinNullUserDao.class, Mockito.CALLS_REAL_METHODS);
        final Object unchecked = Mockito.mock(UncheckedJoinNullUserDao.class, Mockito.CALLS_REAL_METHODS);

        for (final Class<?> type : List.of(JoinEntityReadOps.class, UncheckedJoinEntityReadOps.class, JoinEntityDeleteOps.class,
                UncheckedJoinEntityDeleteOps.class, CrudJoinEntityReadOps.class, UncheckedCrudJoinEntityReadOps.class)) {
            final Object target = type.getSimpleName().startsWith("Unchecked") ? unchecked : checked;

            for (final Method method : type.getDeclaredMethods()) {
                if (!method.isDefault() || method.isBridge()) {
                    continue;
                }

                final Class<?>[] parameterTypes = method.getParameterTypes();
                final Object[] args = new Object[parameterTypes.length];

                for (int index = 0; index < args.length; index++) {
                    if (parameterTypes[index].isPrimitive()) {
                        args[index] = defaultValue(parameterTypes[index]);
                    }
                }

                if (method.getName().equals("getOrNull")) {
                    assertInvalidArgument(method, target, args, "id");
                } else if (parameterTypes[parameterTypes.length - 1] == Condition.class) {
                    for (int index = 0; index < args.length - 1; index++) {
                        args[index] = validArgument(parameterTypes[index], method.getName());
                    }
                    assertInvalidArgument(method, target, args, "cond");
                } else if (parameterTypes[0] == Object.class && parameterTypes.length == 2 && parameterTypes[1] == Executor.class
                        && method.getName().startsWith("loadAll")) {
                    assertInvalidArgument(method, target, args, "entity");
                    args[0] = new FiNullUser();
                    assertInvalidArgument(method, target, args, "executor");
                } else if (parameterTypes.length > 1 && parameterTypes[0] == Object.class && parameterTypes[1] == Class.class
                        && (parameterTypes.length == 3 || method.getName().startsWith("delete"))) {
                    assertInvalidArgument(method, target, args, "entity");
                    args[0] = new FiNullUser();
                    assertInvalidArgument(method, target, args, "joinEntityClass");
                }
            }
        }
    }

    private static void assertInvalidArgument(final Method method, final Object target, final Object[] args, final String parameterName) {
        final InvocationTargetException failure = assertThrows(InvocationTargetException.class, () -> method.invoke(target, args), method.toGenericString());
        assertTrue(failure.getCause() instanceof IllegalArgumentException, () -> method + ": " + failure.getCause());
        assertTrue(failure.getCause().getMessage().contains(parameterName), () -> method + ": " + failure.getCause().getMessage());
    }

    @Test
    public void testPublicMethodsRejectNullFunctionalArguments() throws Exception {
        int validationCount = 0;

        // Default methods on Dao / DaoBase (mock CALLS_REAL_METHODS + real dataSource stubs where needed)
        final Dao<?, ?> mockDao = Mockito.mock(FiNullUserDao.class, Mockito.CALLS_REAL_METHODS);
        when(mockDao.dataSource()).thenReturn(ds);
        when(mockDao.executor()).thenReturn(Runnable::run);

        validationCount += assertNullFunctionalArgumentsRejected(Dao.class, () -> mockDao, method -> true);
        validationCount += assertNullFunctionalArgumentsRejected(DaoBase.class, () -> mockDao, method -> true);

        // Default methods on ReadOps / UncheckedReadOps
        validationCount += assertNullFunctionalArgumentsRejected(ReadOps.class, () -> mockDao, Method::isDefault);
        validationCount += assertNullFunctionalArgumentsRejected(UncheckedReadOps.class, () -> uncheckedDao, Method::isDefault);

        // Abstract methods implemented by DaoImpl (live proxy)
        validationCount += assertNullFunctionalArgumentsRejected(ReadOps.class, () -> checkedDao, method -> !method.isDefault());
        validationCount += assertNullFunctionalArgumentsRejected(CrudReadOps.class, () -> checkedDao, method -> true);
        validationCount += assertNullFunctionalArgumentsRejected(UncheckedReadOps.class, () -> uncheckedDao, method -> !method.isDefault());
        validationCount += assertNullFunctionalArgumentsRejected(UncheckedCrudReadOps.class, () -> uncheckedDao, method -> true);

        // Each functional-interface parameter is one validation (methods with multiple FI params count multiple times).
        // Dao(4) + DaoBase(4) + ReadOps defaults(8) + UncheckedReadOps defaults(5)
        // + ReadOps abstract(60) + CrudReadOps(2) + UncheckedReadOps abstract(38) + UncheckedCrudReadOps(2) = 123
        assertEquals(123, validationCount);
    }

    private static int assertNullFunctionalArgumentsRejected(final Class<?> declaringClass, final ThrowingSupplier<?> targetSupplier,
            final java.util.function.Predicate<Method> methodFilter) throws Exception {
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
                    args[parameterIndex] = parameterIndex == targetParameterIndex ? null : validArgument(parameterTypes[parameterIndex], method.getName());
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

    private static Object validArgument(final Class<?> type, final String methodName) {
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
            return "name";
        } else if (type == Class.class) {
            return Object.class;
        } else if (Condition.class.isAssignableFrom(type)) {
            if ("paginate".equals(methodName)) {
                return Criteria.builder().where(Filters.gt("id", 0)).orderByAsc("id").build();
            }
            return Filters.eq("id", 1);
        } else if (ParsedSql.class.isAssignableFrom(type)) {
            return ParsedSql.parse("SELECT 1 WHERE id = :id");
        } else if (type == Optional.class) {
            return Optional.empty();
        } else if (type.isArray()) {
            return Array.newInstance(type.getComponentType(), 0);
        } else if (Set.class.isAssignableFrom(type)) {
            return Set.of("name");
        } else if (List.class.isAssignableFrom(type) || Collection.class == type) {
            return List.of("name");
        } else if (Map.class.isAssignableFrom(type)) {
            return Map.of();
        } else if (Iterator.class.isAssignableFrom(type)) {
            return Collections.emptyIterator();
        } else if (Iterable.class.isAssignableFrom(type)) {
            return List.of("name");
        } else if (Executor.class.isAssignableFrom(type)) {
            return (Executor) Runnable::run;
        } else if (Dataset.class.isAssignableFrom(type)) {
            return mock(Dataset.class);
        } else if (type.isEnum()) {
            return type.getEnumConstants()[0];
        } else {
            return mock(type);
        }
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

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
