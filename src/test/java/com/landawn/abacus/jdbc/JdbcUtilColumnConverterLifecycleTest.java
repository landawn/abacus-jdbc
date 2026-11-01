package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("2025")
@Tag("integration")
class JdbcUtilColumnConverterLifecycleTest {

    @TempDir
    Path directory;

    @Test
    void getColumnValue_disposableValueClass_doesNotRetainItsLoader() throws Exception {
        assertProbePasses(RetentionProbe.class, "COLUMN CONVERTER RETENTION PROBE PASSED");
    }

    // Regression: a ClassValue cache stored the converters (defined by JdbcUtil's loader) on bootstrap classes such as
    // UUID, so a JdbcUtil loaded by a disposable loader (e.g. a redeployed web application's) stayed reachable for good.
    @Test
    void getColumnValue_childLoadedJdbcUtilReadsBootstrapValue_doesNotRetainJdbcUtilLoader() throws Exception {
        // The test log4j2.xml logs com.landawn.abacus at DEBUG, which by itself keeps the child-loaded classes reachable
        // through Log4j; error-level logging isolates the column converters.
        final Path quietLogging = directory.resolve("log4j2-quiet.xml");
        Files.writeString(quietLogging, "<Configuration status=\"OFF\"><Loggers><Root level=\"error\"/></Loggers></Configuration>");

        assertProbePasses(ChildLoadedJdbcUtilProbe.class, "CHILD-LOADED JDBCUTIL PROBE PASSED", "-Dlog4j2.configurationFile=" + quietLogging.toUri());
    }

    @Test
    void isSameOrAncestorClassLoader_followsParentChainOnly() {
        final ClassLoader application = JdbcUtilColumnConverterLifecycleTest.class.getClassLoader();
        final ClassLoader child = new ClassLoader(application) {
        };
        final ClassLoader unrelated = new ClassLoader(null) {
        };

        assertTrue(JdbcUtil.isSameOrAncestorClassLoader(null, application), "The bootstrap loader is every loader's ancestor");
        assertTrue(JdbcUtil.isSameOrAncestorClassLoader(null, null));
        assertTrue(JdbcUtil.isSameOrAncestorClassLoader(application, application));
        assertTrue(JdbcUtil.isSameOrAncestorClassLoader(application, child));
        assertTrue(JdbcUtil.isSameOrAncestorClassLoader(ClassLoader.getPlatformClassLoader(), child));
        assertFalse(JdbcUtil.isSameOrAncestorClassLoader(child, application));
        assertFalse(JdbcUtil.isSameOrAncestorClassLoader(application, null));
        assertFalse(JdbcUtil.isSameOrAncestorClassLoader(application, unrelated));
        assertFalse(JdbcUtil.isSameOrAncestorClassLoader(unrelated, application));
    }

    @Test
    void columnConvertersOf_everyLoaderRelation_selectsSameConverters() {
        final Class<?> unrelatedClass = Proxy.newProxyInstance(new ClassLoader(null) {
        }, new Class<?>[] { Runnable.class }, (proxy, method, args) -> null).getClass();
        final Class<?> descendantClass = Proxy.newProxyInstance(new ClassLoader(JdbcUtil.class.getClassLoader()) {
        }, new Class<?>[] { Runnable.class }, (proxy, method, args) -> null).getClass();

        assertSame(JdbcUtil.columnConvertersOf(UUID.class), JdbcUtil.columnConvertersOf(unrelatedClass));
        assertSame(JdbcUtil.columnConvertersOf(UUID.class), JdbcUtil.columnConvertersOf(descendantClass));
        assertSame(JdbcUtil.columnConvertersOf(java.sql.Date.class), JdbcUtil.columnConvertersOf(java.sql.Date.class));
        assertFalse(JdbcUtil.columnConvertersOf(java.sql.Date.class) == JdbcUtil.columnConvertersOf(UUID.class),
                "java.sql.Date values keep their timestamp-aware conversion");
    }

    private void assertProbePasses(final Class<?> probe, final String successMessage, final String... jvmOptions) throws Exception {
        final Path output = directory.resolve(probe.getSimpleName() + ".log");
        final String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        final List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(), "-Xms16m", "-Xmx64m",
                "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=2", "-XX:-DisableExplicitGC"));
        command.addAll(List.of(jvmOptions));
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), probe.getName()));
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();

        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), probe.getSimpleName() + " did not finish");
            final String diagnostic = Files.readString(output);
            assertEquals(0, process.exitValue(), diagnostic);
            assertTrue(diagnostic.contains(successMessage), diagnostic);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    private static void awaitCollection(final WeakReference<?> reference, final String failure) throws InterruptedException {
        for (int attempt = 0; attempt < 50 && reference.get() != null; attempt++) {
            System.gc();
            Thread.sleep(20);
        }

        if (reference.get() != null) {
            throw new AssertionError(failure);
        }
    }

    private static ResultSet resultSetReturning(final Object value) {
        return (ResultSet) Proxy.newProxyInstance(JdbcUtilColumnConverterLifecycleTest.class.getClassLoader(), new Class<?>[] { ResultSet.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("getObject")) {
                        return value;
                    }
                    if (method.getName().equals("close")) {
                        return null;
                    }
                    throw new AssertionError("Unexpected ResultSet operation: " + method.getName());
                });
    }

    public static final class RetentionProbe {
        public static void main(final String[] args) throws Exception {
            awaitCollection(readDisposableValue(new ClassLoader(RetentionProbe.class.getClassLoader()) {
            }), "The column converter cache retains a discarded value class loader");
            // A loader unrelated to JdbcUtil's (here: with the bootstrap loader as parent) must not be retained either.
            awaitCollection(readDisposableValue(new ClassLoader(null) {
            }), "The column converter cache retains a discarded unrelated value class loader");

            System.out.println("COLUMN CONVERTER RETENTION PROBE PASSED");
        }

        // Separate the setup frame from the reachability check so JVM local-variable liveness cannot retain the fixtures.
        private static WeakReference<ClassLoader> readDisposableValue(final ClassLoader loader) throws Exception {
            final Object value = Proxy.newProxyInstance(loader, new Class<?>[] { Runnable.class }, (proxy, method, args) -> null);

            try (ResultSet resultSet = resultSetReturning(value)) {
                if (JdbcUtil.getColumnValue(resultSet, 1) != value || JdbcUtil.getColumnValue(resultSet, "value") != value) {
                    throw new AssertionError("The converter must preserve unrecognized driver values by identity");
                }
            }

            return new WeakReference<>(loader);
        }
    }

    public static final class ChildLoadedJdbcUtilProbe {
        public static void main(final String[] args) throws Exception {
            // Initialize the application class loader's copy first, as a hosting application does: bringing this
            // library up initializes the process-wide logging backend and the shared stream/executor pools, and those
            // create threads the JVM keeps for its whole life (shutdown hooks, the JUL Logging-Cleaner). A thread
            // created while a class of the child loader is on the stack captures that loader's protection domain in its
            // inherited access control context, which keeps the loader reachable whatever this library caches; a
            // redeployed web application starts with that infrastructure already initialized.
            Class.forName(JdbcUtil.class.getName(), true, JdbcUtil.class.getClassLoader());

            awaitCollection(readThroughChildLoadedJdbcUtil(), "Reading a bootstrap-class value retains the loader of a child-loaded JdbcUtil");

            System.out.println("CHILD-LOADED JDBCUTIL PROBE PASSED");
        }

        private static WeakReference<ClassLoader> readThroughChildLoadedJdbcUtil() throws Exception {
            final URL classes = JdbcUtil.class.getProtectionDomain().getCodeSource().getLocation();
            // Child-first for this library only, as a web application's loader would define it; everything else (and the
            // package scan of JdbcUtil's initialization) resolves through the application class path.
            final URLClassLoader loader = new URLClassLoader(new URL[] { classes }, ChildLoadedJdbcUtilProbe.class.getClassLoader()) {
                @Override
                protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
                    if (!name.startsWith("com.landawn.abacus.jdbc.")) {
                        return super.loadClass(name, resolve);
                    }

                    synchronized (getClassLoadingLock(name)) {
                        final Class<?> loaded = findLoadedClass(name);
                        return loaded != null ? loaded : findClass(name);
                    }
                }
            };

            final UUID value = UUID.randomUUID();
            final Throwable[] failure = new Throwable[1];
            // A short-lived thread: thread-locals set while JdbcUtil initializes must not outlive the probe. The context
            // loader is left unchanged, as logging frameworks keep a context per context loader.
            final Thread reader = new Thread(() -> {
                try (ResultSet resultSet = resultSetReturning(value)) {
                    final Class<?> jdbcUtil = loader.loadClass(JdbcUtil.class.getName());

                    if (jdbcUtil.getClassLoader() != loader) {
                        throw new AssertionError("JdbcUtil was not loaded by the child loader");
                    }

                    final Method byIndex = jdbcUtil.getMethod("getColumnValue", ResultSet.class, int.class);
                    final Method byLabel = jdbcUtil.getMethod("getColumnValue", ResultSet.class, String.class);

                    if (byIndex.invoke(null, resultSet, 1) != value || byLabel.invoke(null, resultSet, "value") != value) {
                        throw new AssertionError("The converter must preserve bootstrap-class values by identity");
                    }
                } catch (final Throwable e) {
                    failure[0] = e;
                }
            });
            reader.start();
            reader.join();

            if (failure[0] != null) {
                throw new AssertionError(failure[0]);
            }

            loader.close();

            return new WeakReference<>(loader);
        }
    }
}
