package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.jdbc.annotation.CacheResult;
import com.landawn.abacus.jdbc.annotation.CacheSerialization;
import com.landawn.abacus.jdbc.dao.NonUpdateCrudDao;
import com.landawn.abacus.util.ImmutableList;

@Tag("2025")
class DaoCacheRuntimeCompatibilityTest {
    @TempDir
    Path directory;

    @Tag("integration")
    @Test
    void disabledUnsafePreservesOtherCacheModesAndHasAClearAdapterFailure() throws Exception {
        runProbe("noUnsafe", System.getProperty("java.class.path"));
    }

    @Tag("integration")
    @Test
    void deniedUnsafeMemoryAccessHasACachedActionableFailure() throws Exception {
        runProbe("deniedUnsafeAdapter", System.getProperty("java.class.path"));
    }

    @Tag("integration")
    @Test
    void deniedUnsafeMemoryAccessExplainsUnavailableParserAndPreservesOtherModes() throws Exception {
        runProbe("deniedUnsafe", System.getProperty("java.class.path"));
    }

    @Tag("integration")
    @Test
    void deniedMemoryAccessWithKryoUnsafeDisabledPreservesOrdinaryCopies() throws Exception {
        runProbe("deniedUnsafeDisabled", System.getProperty("java.class.path"));
    }

    @Tag("integration")
    @Test
    void optionalKryoDoesNotPreventDaoCreationOrOtherCacheModes() throws Exception {
        final String classpath = Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator)))
                .filter(entry -> !Path.of(entry).getFileName().toString().matches("kryo-[^/]+\\.jar"))
                .collect(Collectors.joining(File.pathSeparator));
        runProbe("noKryo", classpath);
    }

    @Tag("integration")
    @Test
    void missingKryoDependencyIsNamedByTheDiagnostic() throws Exception {
        final String classpath = Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator)))
                .filter(entry -> !Path.of(entry).getFileName().toString().matches("minlog-[^/]+\\.jar"))
                .collect(Collectors.joining(File.pathSeparator));
        runProbe("noMinlog", classpath);
    }

    @Test
    void classLoaderDenyingKryoDoesNotPreventDaoClassInitialization() throws Exception {
        // ParserFactory swallows the denial; the DAO's own diagnostic probe must not fail harder than that, or every
        // DAO creation (including JSON/NONE users) would die with ExceptionInInitializerError.
        final List<URL> urls = new ArrayList<>();
        for (final String entry : System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator))) {
            urls.add(Path.of(entry).toUri().toURL());
        }
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("com.esotericsoftware.kryo.")) {
                    throw new SecurityException("package denied: " + name);
                }
                return super.loadClass(name, resolve);
            }
        }) {
            final Class<?> daoImpl = Class.forName("com.landawn.abacus.jdbc.DaoImpl", true, loader);
            assertSame(loader, daoImpl.getClassLoader());
            final Field reason = Class.forName("com.landawn.abacus.jdbc.DaoImpl$KryoUnavailability", true, loader).getDeclaredField("REASON");
            reason.setAccessible(true);
            final String message = (String) reason.get(null);
            assertTrue(message.contains("loading it was denied"), message);
            assertTrue(message.contains("package denied: com.esotericsoftware.kryo."), message);
            assertFalse(message.contains("absent from the classpath"), message);
        }
    }

    private void runProbe(final String mode, final String classpath) throws Exception {
        final Path output = directory.resolve(mode + ".log");
        final String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        final List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
        // Keep the existing test runtime's module access so disabling Unsafe actually reaches DAO copying,
        // instead of letting an unrelated parser-initialization failure bypass the ordinary-copy assertions.
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
        if (mode.startsWith("deniedUnsafe")) {
            command.add("--sun-misc-unsafe-memory-access=deny");
        }
        if (!mode.startsWith("deniedUnsafe") || mode.equals("deniedUnsafeDisabled")) {
            command.add("-Dkryo.unsafe=false");
        }
        command.addAll(List.of("-cp", classpath, Probe.class.getName(), mode));
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Cache compatibility probe did not finish");
            if (mode.startsWith("deniedUnsafe")) {
                assumeFalse(readOutput(output).contains("Unrecognized option: --sun-misc-unsafe-memory-access"),
                        "This JVM does not support the Unsafe memory-access policy option");
            }
            assertEquals(0, process.exitValue(), () -> readOutput(output));
            assertTrue(readOutput(output).contains("CACHE COMPATIBILITY PASSED"), () -> readOutput(output));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static String readOutput(final Path output) {
        try {
            return Files.readString(output);
        } catch (final java.io.IOException e) {
            return e.toString();
        }
    }

    public static final class Probe {
        public static final class Value {
            @Id
            private long id;
            private String name;

            public Value() {
            }

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

        public interface ProbeDao extends NonUpdateCrudDao<Value, Long, ProbeDao> {
            @CacheResult(enabled = true, serialization = CacheSerialization.KRYO)
            default Value getBean() {
                return value();
            }

            @CacheResult(enabled = true, serialization = CacheSerialization.KRYO)
            default List<Value> getList() {
                return new ArrayList<>(List.of(value()));
            }

            @CacheResult(enabled = true, serialization = CacheSerialization.KRYO)
            default ImmutableList<Value> getImmutable() {
                return ImmutableList.of(value());
            }

            @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
            default ImmutableList<Value> getJson() {
                return ImmutableList.of(value());
            }

            @CacheResult(enabled = true, serialization = CacheSerialization.NONE)
            default ImmutableList<Value> getNone() {
                return ImmutableList.of(value());
            }
        }

        private static Value value() {
            final Value value = new Value();
            value.id = 1;
            value.name = "original";
            return value;
        }

        public static void main(final String[] args) throws Exception {
            if (args[0].equals("deniedUnsafeAdapter")) {
                DeniedUnsafeAdapterProbe.verify();
                System.out.println("CACHE COMPATIBILITY PASSED: " + args[0]);
                return;
            }
            final boolean noKryo = args[0].equals("noKryo");
            final boolean noMinlog = args[0].equals("noMinlog");
            final boolean deniedUnsafe = args[0].equals("deniedUnsafe");
            if (noKryo) {
                assertThrows(ClassNotFoundException.class, () -> Class.forName("com.esotericsoftware.kryo.Kryo"));
            } else if (noMinlog) {
                // Kryo's entry class is present and loadable; only its transitive logging dependency is gone.
                Class.forName("com.esotericsoftware.kryo.Kryo", false, Probe.class.getClassLoader());
                assertThrows(ClassNotFoundException.class, () -> Class.forName("com.esotericsoftware.minlog.Log"));
                assertFalse(com.landawn.abacus.parser.ParserFactory.isKryoParserAvailable());
            } else if (!deniedUnsafe) {
                assertFalse(com.esotericsoftware.kryo.util.Util.unsafe);
            }
            Class.forName("com.landawn.abacus.jdbc.DaoImpl");
            try (com.zaxxer.hikari.HikariDataSource dataSource = (com.zaxxer.hikari.HikariDataSource) JdbcUtil
                    .createHikariDataSource("jdbc:h2:mem:cache_compatibility", "sa", "")) {
                final ProbeDao dao = JdbcUtil.createDao(ProbeDao.class, dataSource);
                final ImmutableList<Value> json = dao.getJson();
                json.get(0).name = "changed";
                assertEquals("original", dao.getJson().get(0).name);
                assertSame(dao.getNone(), dao.getNone());
                final boolean kryoAvailable = !noKryo && com.landawn.abacus.parser.ParserFactory.isKryoParserAvailable();
                if (!deniedUnsafe) {
                    assertEquals(!noKryo && !noMinlog, kryoAvailable, "Existing module access must keep the disabled-Unsafe public DAO path available");
                }
                if (kryoAvailable) {
                    final Value first = dao.getBean();
                    first.name = "changed";
                    assertEquals("original", dao.getBean().name);
                    final List<Value> list = dao.getList();
                    list.get(0).name = "changed";
                    assertEquals("original", dao.getList().get(0).name);
                    assertNotSame(list, dao.getList());
                }
                final UnsupportedOperationException failure = assertThrows(UnsupportedOperationException.class, dao::getImmutable);
                assertTrue(failure.getMessage().contains(kryoAvailable ? "requires sun.misc.Unsafe" : "Kryo is not available"));
                if (noKryo) {
                    // The diagnostic must name the missing dependency, not blame runtime settings.
                    assertTrue(failure.getMessage().contains("absent from the classpath"), failure::getMessage);
                    assertFalse(failure.getMessage().contains("failed to initialize"), failure::getMessage);
                }
                if (noMinlog) {
                    // Kryo's entry class loads, so the diagnostic must quote the missing transitive class rather
                    // than guess at JVM access settings or claim Kryo itself is absent.
                    assertTrue(failure.getMessage().contains("one of its dependencies is missing"), failure::getMessage);
                    assertTrue(failure.getMessage().contains("minlog"), failure::getMessage);
                    assertFalse(failure.getMessage().contains("absent from the classpath"), failure::getMessage);
                    assertFalse(failure.getMessage().contains("JVM"), failure::getMessage);
                }
                if (deniedUnsafe) {
                    if (!kryoAvailable) {
                        // Kryo itself can fail during parser initialization under the JVM policy, before the
                        // DAO adapter runs. Its public diagnostic must explain this distinct availability case
                        // rather than suggesting the dependency is missing.
                        assertTrue(failure.getMessage().contains("on the classpath but its parser failed to initialize"), failure::getMessage);
                        assertTrue(failure.getMessage().contains("JVM"), failure::getMessage);
                        assertFalse(failure.getMessage().contains("absent from the classpath"), failure::getMessage);
                        assertThrows(UnsupportedOperationException.class, dao::getBean);
                    }
                } else if (!noKryo && !noMinlog) {
                    assertTrue(failure.getMessage().contains("-Dkryo.unsafe=false"), failure::getMessage);
                    verifyAdapterWithoutUnsafe();
                }
                assertEquals("original", dao.getJson().get(0).name);
                assertSame(dao.getNone(), dao.getNone());
                if (noKryo) {
                    DaoCacheJsonComparatorTest.verifyNoKryoThreadScope();
                }
                System.out.println("CACHE COMPATIBILITY PASSED: " + args[0]);
            }
        }

        private static void verifyAdapterWithoutUnsafe() throws Exception {
            // A plain Kryo instance independently checks the adapter's lazy boundary without relying on
            // ParserFactory's registrations or changing process-global static fields.
            final com.esotericsoftware.kryo.Kryo kryo = new com.esotericsoftware.kryo.Kryo();
            kryo.setRegistrationRequired(false);
            final java.lang.reflect.Constructor<?> wrapper = Class.forName("com.landawn.abacus.jdbc.DaoImpl$KryoCacheCopy")
                    .getDeclaredConstructor(Object.class);
            wrapper.setAccessible(true);
            final Value source = value();
            final Value copy = (Value) kryo.copy(wrapper.newInstance(source));
            assertNotSame(source, copy);
            source.name = "changed";
            assertEquals("original", copy.name);
            final List<?> copiedList = (List<?>) kryo.copy(wrapper.newInstance(new ArrayList<>(List.of(source))));
            assertNotSame(source, copiedList.get(0));
            final Object adapted = wrapper.newInstance(ImmutableList.of(source));
            final UnsupportedOperationException failure = assertThrows(UnsupportedOperationException.class, () -> kryo.copy(adapted));
            assertTrue(failure.getMessage().contains("requires sun.misc.Unsafe"));
        }
    }

    // Keep the custom Serializer's verification linkage outside the entry point used without Kryo.
    private static final class DeniedUnsafeAdapterProbe {
        private static void verify() throws Exception {
            final com.esotericsoftware.kryo.Kryo kryo = new com.esotericsoftware.kryo.Kryo();
            kryo.setRegistrationRequired(false);
            final java.lang.reflect.Constructor<?> envelope = Class.forName("com.landawn.abacus.jdbc.DaoImpl$KryoCacheCopy")
                    .getDeclaredConstructor(Object.class);
            envelope.setAccessible(true);
            final ArrayList<String> ordinary = new ArrayList<>(List.of("ordinary"));
            assertEquals(ordinary, kryo.copy(envelope.newInstance(ordinary)));
            final ImmutableList<String> source = ImmutableList.of("immutable");
            final Object adapted = envelope.newInstance(source);
            final UnsupportedOperationException first = assertThrows(UnsupportedOperationException.class, () -> kryo.copy(adapted));
            assertTrue(first.getMessage().contains("--sun-misc-unsafe-memory-access=deny"), first::getMessage);
            assertTrue(first.getMessage().contains("application serializer"), first::getMessage);
            assertTrue(first.getCause() instanceof UnsupportedOperationException);
            assertTrue(String.valueOf(first.getCause().getMessage()).contains("objectFieldOffset"), () -> first.getCause().toString());
            final UnsupportedOperationException retry = assertThrows(UnsupportedOperationException.class, () -> kryo.copy(adapted));
            assertNotSame(first, retry);
            assertSame(first.getCause(), retry.getCause());
            final Object ordinaryCopy = kryo.copy(envelope.newInstance(ordinary));
            assertEquals(ordinary, ordinaryCopy);
            assertNotSame(ordinary, ordinaryCopy);

            // An application's copy policy remains authoritative even after the built-in adapter has cached
            // an unavailable capability. Registering this instance affects only this isolated Kryo graph.
            kryo.register(source.getClass(), new com.esotericsoftware.kryo.Serializer<>() {
                @Override
                public Object copy(final com.esotericsoftware.kryo.Kryo ignored, final Object original) {
                    return original;
                }

                @Override
                public void write(final com.esotericsoftware.kryo.Kryo ignored, final com.esotericsoftware.kryo.io.Output output, final Object object) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Object read(final com.esotericsoftware.kryo.Kryo ignored, final com.esotericsoftware.kryo.io.Input input, final Class<?> type) {
                    throw new UnsupportedOperationException();
                }
            });
            assertSame(source, kryo.copy(envelope.newInstance(source)));
        }
    }
}
