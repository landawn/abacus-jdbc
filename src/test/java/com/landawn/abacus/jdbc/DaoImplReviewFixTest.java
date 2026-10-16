package com.landawn.abacus.jdbc;

import static com.landawn.abacus.query.Dsl.PSC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.jdbc.annotation.Cache;
import com.landawn.abacus.jdbc.annotation.CacheResult;
import com.landawn.abacus.jdbc.annotation.RefreshCache;
import com.landawn.abacus.jdbc.dao.UncheckedNonUpdateDao;
import com.landawn.abacus.util.ImmutableList;
import com.landawn.abacus.util.Tuple.Tuple3;

/**
 * Regression tests for the {@link DaoImpl#createDao} default-cache lifecycle. A {@link Jdbc.DefaultDaoCache}'s backing
 * pool registers a JVM shutdown hook and a periodic eviction task that are never released while the DAO is in use, so
 * {@code createDao} must create one only for a DAO that can actually store results, and never for a DAO it rejects or
 * discards:
 * <ul>
 *   <li>Previously the mere presence of an {@code enabled = false} {@code @CacheResult}/{@code @RefreshCache}
 *       annotation, or of an enabled {@code @RefreshCache} without any enabled {@code @CacheResult}, created the
 *       never-used pool.</li>
 *   <li>Previously the cache was created before the DAO methods were validated, so a DAO rejected by a later check
 *       (e.g., a {@code @RefreshCache}-only DAO or an invalid {@code @CacheResult} setting) leaked its pool.</li>
 *   <li>Previously a proxy discarded because an equivalent one was published concurrently leaked its pool.</li>
 * </ul>
 *
 * <p>Each pool registers exactly one shutdown hook and its {@code close()} removes it, so the probe counts registered
 * shutdown hooks (which needs the suite's {@code --add-opens java.base/java.lang}, forwarded to the probe). The checks
 * run in a freshly forked JVM ({@link Probe}) so they are independent of pools created by other tests, and the caches
 * the probe deliberately creates are not left registered in the suite JVM.</p>
 */
class DaoImplReviewFixTest extends TestBase {

    @TempDir
    Path directory;

    @Test
    void testDefaultCacheCreatedOnlyForAcceptedDaosWithEnabledCacheResult() throws Exception {
        final Path output = directory.resolve("dao-cache-gate-probe.log");
        final String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        final List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
        // Forward the test runtime's module access (java.lang for the shutdown-hook count, Kryo's --add-opens) so the
        // probe JVM behaves like the suite JVM.
        final List<String> runtimeArguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
        for (int i = 0; i < runtimeArguments.size(); i++) {
            final String argument = runtimeArguments.get(i);
            if (argument.startsWith("--add-opens=") || argument.startsWith("--add-exports=")) {
                command.add(argument);
            } else if (argument.equals("--add-opens") || argument.equals("--add-exports")) {
                command.add(argument);
                command.add(runtimeArguments.get(++i));
            }
        }
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), Probe.class.getName()));

        final Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();

        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Dao cache gate probe did not finish");
            assertEquals(0, process.exitValue(), () -> readOutput(output));
            assertTrue(readOutput(output).contains("DAO CACHE GATE PROBE PASSED"), () -> readOutput(output));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static String readOutput(final Path output) {
        try {
            return Files.readString(output);
        } catch (final IOException e) {
            return e.toString();
        }
    }

    public static final class Probe {

        static final AtomicInteger counter = new AtomicInteger();

        @FunctionalInterface
        interface Step {
            void run() throws Exception;
        }

        public static class TestEntity {
            private long id;
            private String name;

            public long getId() {
                return id;
            }

            public void setId(final long id) {
                this.id = id;
            }

            public String getName() {
                return name;
            }

            public void setName(final String name) {
                this.name = name;
            }
        }

        /**
         * A cache implementation without the {@code (int capacity, long evictDelay)} constructor {@code @Cache} requires.
         */
        public static final class NoCapacityConstructorCache implements Jdbc.DaoCache {
            public NoCapacityConstructorCache(final String unused) {
            }

            @Override
            public Object get(final String defaultCacheKey, final Object daoProxy, final Object[] args,
                    final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
                return null;
            }

            @Override
            public boolean put(final String defaultCacheKey, final Object result, final Object daoProxy, final Object[] args,
                    final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
                return false;
            }

            @Override
            public boolean put(final String defaultCacheKey, final Object result, final long liveTime, final long maxIdleTime, final Object daoProxy,
                    final Object[] args, final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
                return false;
            }

            @Override
            public void update(final String defaultCacheKey, final Object result, final Object daoProxy, final Object[] args,
                    final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
            }
        }

        public interface PlainDao extends UncheckedNonUpdateDao<TestEntity, PlainDao> {
            default String findName() {
                return "plain-result";
            }
        }

        public interface DisabledMethodCacheDao extends UncheckedNonUpdateDao<TestEntity, DisabledMethodCacheDao> {
            @CacheResult(enabled = false)
            default String findName() {
                return "fresh-result";
            }
        }

        @CacheResult(enabled = false)
        public interface DisabledClassCacheDao extends UncheckedNonUpdateDao<TestEntity, DisabledClassCacheDao> {
            default String findName() {
                return "fresh-result";
            }
        }

        public interface DisabledMethodRefreshDao extends UncheckedNonUpdateDao<TestEntity, DisabledMethodRefreshDao> {
            @RefreshCache(enabled = false)
            default int addName() {
                return 1;
            }
        }

        @RefreshCache(enabled = false)
        public interface DisabledClassRefreshDao extends UncheckedNonUpdateDao<TestEntity, DisabledClassRefreshDao> {
            default int addName() {
                return 1;
            }
        }

        // Rejected by createDao: an enabled @RefreshCache without any enabled @CacheResult.
        @RefreshCache
        public interface RefreshOnlyDao extends UncheckedNonUpdateDao<TestEntity, RefreshOnlyDao> {
            default int addName() {
                return 1;
            }
        }

        // Rejected by createDao while validating the methods: maxLiveTimeMillis must be >= 0.
        public interface InvalidEnabledCacheDao extends UncheckedNonUpdateDao<TestEntity, InvalidEnabledCacheDao> {
            @CacheResult(enabled = true, maxLiveTimeMillis = -1)
            default String findName() {
                return "never-created";
            }
        }

        @Cache(impl = NoCapacityConstructorCache.class)
        @CacheResult(enabled = true)
        public interface MissingCacheConstructorDao extends UncheckedNonUpdateDao<TestEntity, MissingCacheConstructorDao> {
            default String findName() {
                return "never-created";
            }
        }

        // The method-level annotation overrides the disabled type-level one, so the cache must still be created.
        @CacheResult(enabled = false)
        public interface MethodEnabledUnderDisabledClassDao extends UncheckedNonUpdateDao<TestEntity, MethodEnabledUnderDisabledClassDao> {
            @CacheResult(enabled = true)
            default String findValue() {
                return "value-" + counter.incrementAndGet();
            }
        }

        @CacheResult(enabled = true)
        public interface EnabledClassCacheDao extends UncheckedNonUpdateDao<TestEntity, EnabledClassCacheDao> {
            default String findValue() {
                return "value-" + counter.incrementAndGet();
            }
        }

        public interface RacingCacheDao extends UncheckedNonUpdateDao<TestEntity, RacingCacheDao> {
            @CacheResult(enabled = true)
            default String findValue() {
                return "value-" + counter.incrementAndGet();
            }
        }

        public static void main(final String[] args) throws Exception {
            // The first DAO creation in a JVM runs one-time library initialization; create a plain DAO first so the
            // "before" counts below are taken after it.
            assertEquals("plain-result", DaoImpl.createDao(PlainDao.class, null, mockDataSourceForDaoCreation(), PSC, null, null, null).findName());

            assertNoCacheCreated("method-level @CacheResult(enabled = false)", () -> assertEquals("fresh-result",
                    DaoImpl.createDao(DisabledMethodCacheDao.class, null, mockDataSourceForDaoCreation(), PSC, null, null, null).findName()));
            assertNoCacheCreated("class-level @CacheResult(enabled = false)", () -> assertEquals("fresh-result",
                    DaoImpl.createDao(DisabledClassCacheDao.class, null, mockDataSourceForDaoCreation(), PSC, null, null, null).findName()));
            assertNoCacheCreated("method-level @RefreshCache(enabled = false)", () -> assertEquals(1,
                    DaoImpl.createDao(DisabledMethodRefreshDao.class, null, mockDataSourceForDaoCreation(), PSC, null, null, null).addName()));
            assertNoCacheCreated("class-level @RefreshCache(enabled = false)", () -> assertEquals(1,
                    DaoImpl.createDao(DisabledClassRefreshDao.class, null, mockDataSourceForDaoCreation(), PSC, null, null, null).addName()));

            // Rejected DAOs must not leak a cache either.
            assertNoCacheCreated("a rejected @RefreshCache-only DAO",
                    () -> assertRejected(UnsupportedOperationException.class, "none of them is annotated with @CacheResult",
                            () -> DaoImpl.createDao(RefreshOnlyDao.class, null, mockDataSourceForDaoCreation(), PSC, null, null, null)));
            assertNoCacheCreated("a DAO rejected for an invalid @CacheResult setting", () -> assertRejected(UnsupportedOperationException.class,
                    "maxLiveTimeMillis", () -> DaoImpl.createDao(InvalidEnabledCacheDao.class, null, mockDataSourceForDaoCreation(), PSC, null, null, null)));
            assertNoCacheCreated("a @Cache implementation without an (int, long) constructor",
                    () -> assertRejected(IllegalArgumentException.class, "must declare a constructor (int capacity, long evictDelay)",
                            () -> DaoImpl.createDao(MissingCacheConstructorDao.class, null, mockDataSourceForDaoCreation(), PSC, null, null, null)));

            // Control cases: an effectively enabled @CacheResult must still create (and use) exactly one cache, so the gate
            // cannot be over-corrected into never creating one.
            int before = shutdownHookCount();
            final MethodEnabledUnderDisabledClassDao methodEnabledDao = DaoImpl.createDao(MethodEnabledUnderDisabledClassDao.class, null,
                    mockDataSourceForDaoCreation(), PSC, null, null, null);
            assertCreatedCaches(1, before, "a method-level @CacheResult(enabled = true) under a disabled type-level one");
            assertCachedResult(methodEnabledDao.findValue(), methodEnabledDao.findValue());

            before = shutdownHookCount();
            final EnabledClassCacheDao classEnabledDao = DaoImpl.createDao(EnabledClassCacheDao.class, null, mockDataSourceForDaoCreation(), PSC, null, null,
                    null);
            assertCreatedCaches(1, before, "a class-level @CacheResult(enabled = true)");
            assertCachedResult(classEnabledDao.findValue(), classEnabledDao.findValue());

            // An equivalent proxy published while this createDao call is still initializing (simulated re-entrantly from
            // the connection lookup) wins; the cache created for the discarded proxy must be released.
            final DataSource racingDataSource = mockDataSourceForDaoCreation();
            final Connection racingConnection = racingDataSource.getConnection();
            final AtomicBoolean competitorStarted = new AtomicBoolean();
            final AtomicReference<RacingCacheDao> competitor = new AtomicReference<>();
            org.mockito.Mockito.when(racingDataSource.getConnection()).thenAnswer(invocation -> {
                if (competitorStarted.compareAndSet(false, true)) {
                    competitor.set(DaoImpl.createDao(RacingCacheDao.class, null, racingDataSource, PSC, null, null, null));
                }

                return racingConnection;
            });

            before = shutdownHookCount();
            final RacingCacheDao racingDao = DaoImpl.createDao(RacingCacheDao.class, null, racingDataSource, PSC, null, null, null);
            assertCreatedCaches(1, before, "a proxy discarded in favor of a concurrently published one");

            if (competitor.get() == null || racingDao != competitor.get()) {
                throw new AssertionError("The concurrently published proxy must be returned, got " + racingDao + " vs " + competitor.get());
            }

            assertCachedResult(racingDao.findValue(), racingDao.findValue());

            System.out.println("DAO CACHE GATE PROBE PASSED");
        }

        private static void assertEquals(final Object expected, final Object actual) {
            if (!expected.equals(actual)) {
                throw new AssertionError("Expected '" + expected + "' but got '" + actual + "'");
            }
        }

        private static void assertCachedResult(final String first, final String second) {
            if (!first.equals(second)) {
                throw new AssertionError("The second call must be served from the DAO cache, got '" + first + "' then '" + second + "'");
            }
        }

        private static void assertRejected(final Class<? extends Throwable> expectedType, final String expectedMessagePart, final Step creation) {
            try {
                creation.run();
            } catch (final Throwable e) {
                if (expectedType.isInstance(e) && String.valueOf(e.getMessage()).contains(expectedMessagePart)) {
                    return;
                }

                throw new AssertionError("Expected " + expectedType.getSimpleName() + " containing '" + expectedMessagePart + "'", e);
            }

            throw new AssertionError("Expected " + expectedType.getSimpleName() + " containing '" + expectedMessagePart + "', but the DAO was created");
        }

        private static void assertNoCacheCreated(final String scenario, final Step creation) throws Exception {
            final int before = shutdownHookCount();
            creation.run();
            assertCreatedCaches(0, before, scenario);
        }

        private static void assertCreatedCaches(final int expected, final int before, final String scenario) throws Exception {
            final int created = shutdownHookCount() - before;

            if (created != expected) {
                throw new AssertionError(
                        "Expected " + expected + " DefaultDaoCache pool(s) (shutdown hooks) to remain registered for " + scenario + ", got " + created);
            }
        }

        private static DataSource mockDataSourceForDaoCreation() throws SQLException {
            final DataSource ds = mock(DataSource.class);
            final Connection conn = mock(Connection.class);
            final DatabaseMetaData meta = mock(DatabaseMetaData.class);

            org.mockito.Mockito.when(ds.getConnection()).thenReturn(conn);
            org.mockito.Mockito.when(conn.getMetaData()).thenReturn(meta);
            org.mockito.Mockito.when(meta.getDatabaseProductName()).thenReturn("MySQL");
            org.mockito.Mockito.when(meta.getDatabaseProductVersion()).thenReturn("8.0");

            return ds;
        }

        /**
         * Number of registered JVM shutdown hooks. Every {@link Jdbc.DefaultDaoCache} pool registers one on creation
         * and removes it when closed, so the change across a {@code createDao} call is the number of pools it left alive.
         */
        private static int shutdownHookCount() throws Exception {
            final Class<?> hooksClass = Class.forName("java.lang.ApplicationShutdownHooks");
            final Field hooksField = hooksClass.getDeclaredField("hooks");
            hooksField.setAccessible(true);

            synchronized (hooksClass) {
                return ((Map<?, ?>) hooksField.get(null)).size();
            }
        }
    }
}
