package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.io.TempDir;

import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.jdbc.annotation.CacheResult;
import com.landawn.abacus.jdbc.annotation.CacheSerialization;
import com.landawn.abacus.jdbc.dao.NonUpdateCrudDao;
import com.landawn.abacus.util.u;

/**
 * The JSON cache copy of an enum constant with a body, whose class is an anonymous subclass of its enum, in a position
 * whose type is unresolved: held by an {@code Optional<Object>}/{@code Nullable<Object>} (as the built-in methods'
 * {@code Optional<T>}), or as the untyped root. The copy named the constant's own class
 * ({@code N.typeOf(Op$1.class)}), which abacus-common resolves through the enum's name, by its own class loader: for an
 * enum it cannot see (an application's class loader below the library's, as here), every call of the method failed with
 * "No class found by name" after the method had returned its result.
 *
 * <p>Such a constant inside a container still fails: abacus-common's JSON writer resolves the element's class the same
 * way.</p>
 *
 * <p>The enum is compiled at test time and loaded by a child class loader that the test class loader (which also loaded
 * abacus-common) cannot see.</p>
 */
@Tag("integration")
@TestInstance(Lifecycle.PER_CLASS)
class DaoCacheJsonChildLoaderEnumTest {
    private static final String PACKAGE = "com.landawn.abacus.jdbc.childenum";

    private static final String SOURCE = """
            package com.landawn.abacus.jdbc.childenum;

            public enum Op {
                PLUS {
                    @Override
                    public long apply(final long a, final long b) {
                        return a + b;
                    }
                },
                MINUS;

                public long apply(final long a, final long b) {
                    return a - b;
                }
            }
            """;

    private static final Map<String, Object> results = new ConcurrentHashMap<>();

    @TempDir
    static Path directory;

    private URLClassLoader isolatedLoader;
    private Object plus;
    private com.zaxxer.hikari.HikariDataSource dataSource;
    private CacheDao dao;

    public static class Row {
        @Id
        private long id;

        public long getId() {
            return id;
        }

        public void setId(final long id) {
            this.id = id;
        }
    }

    public interface CacheDao extends NonUpdateCrudDao<Row, Long, CacheDao> {
        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default u.Optional<Object> getHeldOptional(final String key) {
            return u.Optional.ofNullable(results.get(key));
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default u.Nullable<Object> getHeldNullable(final String key) {
            return u.Nullable.of(results.get(key));
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        @SuppressWarnings("unchecked")
        default <T> java.util.Optional<T> getHeldJdkOptional(final String key) {
            return java.util.Optional.ofNullable((T) results.get(key));
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default Object getAny(final String key) {
            return results.get(key);
        }
    }

    @BeforeAll
    void compileIsolatedEnum() throws IOException, ClassNotFoundException {
        final Path sourceDir = Files.createDirectories(directory.resolve("src").resolve(PACKAGE.replace('.', '/')));
        final Path classesDir = Files.createDirectories(directory.resolve("classes"));
        final Path file = sourceDir.resolve("Op.java");
        Files.writeString(file, SOURCE);

        final ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        final int exitCode = ToolProvider.getSystemJavaCompiler()
                .run(null, diagnostics, diagnostics, "-encoding", "UTF-8", "-proc:none", "-d", classesDir.toString(), file.toString());
        assertEquals(0, exitCode, () -> diagnostics.toString(StandardCharsets.UTF_8));

        isolatedLoader = new URLClassLoader(new URL[] { classesDir.toUri().toURL() }, DaoCacheJsonChildLoaderEnumTest.class.getClassLoader());
        final Object[] constants = Class.forName(PACKAGE + ".Op", true, isolatedLoader).getEnumConstants();
        plus = constants[0];

        dataSource = (com.zaxxer.hikari.HikariDataSource) JdbcUtil.createHikariDataSource("jdbc:h2:mem:json_child_loader_enum", "sa", "");
        dao = JdbcUtil.createDao(CacheDao.class, dataSource);
    }

    @AfterAll
    void close() throws IOException {
        if (dataSource != null) {
            dataSource.close();
        }

        if (isolatedLoader != null) {
            isolatedLoader.close();
        }

        results.clear();
    }

    @Test
    void heldConstantWithABodyIsServedFromTheCache() {
        // The loader that defined abacus-common (and this test) cannot see the enum.
        assertThrows(ClassNotFoundException.class, () -> Class.forName(PACKAGE + ".Op"));
        assertNotSame(((Enum<?>) plus).getDeclaringClass(), plus.getClass(), "a constant with a body");

        final Map<String, Function<String, Object>> getters = new LinkedHashMap<>();
        getters.put("u.Optional", k -> dao.getHeldOptional(k).get());
        getters.put("u.Nullable", k -> dao.getHeldNullable(k).get());
        getters.put("java.util.Optional", k -> dao.getHeldJdkOptional(k).get());

        for (final Map.Entry<String, Function<String, Object>> getter : getters.entrySet()) {
            final String key = "held." + getter.getKey();
            results.put(key, plus);
            assertSame(plus, getter.getValue().apply(key), key);
            results.remove(key);
            assertSame(plus, getter.getValue().apply(key), key + ": served from the cache");
        }

        results.put("any", plus);
        assertSame(plus, dao.getAny("any"));
        results.remove("any");
        assertSame(plus, dao.getAny("any"), "served from the cache");
    }
}
