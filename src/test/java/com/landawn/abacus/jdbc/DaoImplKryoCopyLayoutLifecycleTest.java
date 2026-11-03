package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Class-loader lifecycle of the KRYO cache copy of immutable containers and JDK views (DaoImpl's
 * {@code ImmutableContainerCopySerializer}).
 */
@Tag("2025")
@Tag("integration")
class DaoImplKryoCopyLayoutLifecycleTest {

    @TempDir
    Path directory;

    // Regression: the copy layouts were cached in a plain ClassValue, which stores its value (a DaoImpl-defined
    // ContainerCopyLayout) on the copied class. For a JDK view such as Collections.unmodifiableList's class, a bootstrap
    // class, that kept the loader of a child-loaded abacus-jdbc (e.g. a redeployed web application's) reachable for good.
    @Test
    void kryoCopyOfJdkView_childLoadedDaoImpl_doesNotRetainItsLoader() throws Exception {
        // The test log4j2.xml logs com.landawn.abacus at DEBUG, which by itself keeps the child-loaded classes reachable
        // through Log4j; error-level logging isolates the copy layouts.
        final Path quietLogging = directory.resolve("log4j2-quiet.xml");
        Files.writeString(quietLogging, "<Configuration status=\"OFF\"><Loggers><Root level=\"error\"/></Loggers></Configuration>");

        final Path output = directory.resolve("probe.log");
        final String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        final List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(), "-Xms16m", "-Xmx96m",
                "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=2", "-XX:-DisableExplicitGC", "-Dlog4j2.configurationFile=" + quietLogging.toUri()));
        // Keep the test runtime's module access, which the Kryo parser needs to be available at all.
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
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), ChildLoadedDaoImplProbe.class.getName()));
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();

        try {
            assertTrue(process.waitFor(90, TimeUnit.SECONDS), "The probe did not finish");
            final String diagnostic = Files.readString(output);
            assertEquals(0, process.exitValue(), diagnostic);
            assertTrue(diagnostic.contains("CHILD-LOADED DAOIMPL KRYO COPY PROBE PASSED"), diagnostic);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    // The three cache paths of ImmutableContainerCopySerializer.layoutOf: a class of the serializer's loader or one of its
    // ancestors (a JDK view) is cached in the strong map, which cannot outlive the serializer; a class of a descendant loader
    // (e.g. an application subclass of a wrapper) is cached in the ClassValue, which lives on that class and so cannot pin
    // it; a class of an unrelated loader is not cached at all, as either cache would keep one loader reachable from the other.
    @Test
    @SuppressWarnings("unchecked")
    void layoutOf_cachesByClassLoaderRelation() throws Exception {
        final Class<?> serializerClass = Class.forName(DaoImpl.class.getName() + "$ImmutableContainerCopySerializer");
        final Constructor<?> constructor = serializerClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        final Object serializer = constructor.newInstance();
        final Method layoutOf = serializerClass.getDeclaredMethod("layoutOf", Class.class);
        layoutOf.setAccessible(true);
        final Field layoutsField = serializerClass.getDeclaredField("layouts");
        layoutsField.setAccessible(true);
        final Map<Class<?>, ?> layouts = (Map<Class<?>, ?>) layoutsField.get(serializer);

        final Class<?> jdkView = Collections.unmodifiableList(new ArrayList<>()).getClass();
        final Object jdkLayout = layoutOf.invoke(serializer, jdkView);
        assertSame(jdkLayout, layoutOf.invoke(serializer, jdkView));
        assertSame(jdkLayout, layouts.get(jdkView));

        try (ProbeClassLoader descendant = new ProbeClassLoader(DaoImpl.class.getClassLoader());
             ProbeClassLoader unrelated = new ProbeClassLoader(null)) {
            final Class<?> descendantType = descendant.loadClass(ProbeContainer.class.getName());
            final Class<?> unrelatedType = unrelated.loadClass(ProbeContainer.class.getName());
            assertSame(descendant, descendantType.getClassLoader());
            assertSame(unrelated, unrelatedType.getClassLoader());

            assertSame(layoutOf.invoke(serializer, descendantType), layoutOf.invoke(serializer, descendantType));
            assertFalse(layouts.containsKey(descendantType), "a descendant loader's class must not be held by the strong map");

            assertNotSame(layoutOf.invoke(serializer, unrelatedType), layoutOf.invoke(serializer, unrelatedType));
            assertFalse(layouts.containsKey(unrelatedType), "an unrelated loader's class must not be cached");
        }
    }

    /** A container-like class defined again by each {@link ProbeClassLoader}. */
    static final class ProbeContainer {
        Object value;
    }

    /** Defines its own copy of {@link ProbeContainer} and delegates every other class to {@code parent}. */
    private static final class ProbeClassLoader extends URLClassLoader {
        ProbeClassLoader(final ClassLoader parent) {
            super(new URL[0], parent);
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            if (!ProbeContainer.class.getName().equals(name)) {
                return super.loadClass(name, resolve);
            }

            synchronized (getClassLoadingLock(name)) {
                final Class<?> loaded = findLoadedClass(name);

                if (loaded != null) {
                    return loaded;
                }

                final String resource = name.replace('.', '/') + ".class";

                try (java.io.InputStream in = ProbeContainer.class.getClassLoader().getResourceAsStream(resource)) {
                    final byte[] bytes = in.readAllBytes();
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (final java.io.IOException e) {
                    throw new ClassNotFoundException(name, e);
                }
            }
        }
    }

    public static final class ChildLoadedDaoImplProbe {
        public static void main(final String[] args) throws Exception {
            // Run the same copy through the application class loader's DaoImpl first, as a hosting application would have
            // (see JdbcUtilColumnConverterLifecycleTest.ChildLoadedJdbcUtilProbe): the copy brings up process-wide
            // infrastructure of abacus-common, such as its stream pool, whose shutdown hook lives as long as the JVM. Before
            // JDK 24 a new thread inherits the access control context of its creator's stack, so a hook created while a
            // child-loaded class is on the stack keeps that loader reachable whatever DaoImpl caches.
            copyUnmodifiableList(DaoImpl.class);

            final WeakReference<ClassLoader> loader = copyThroughChildLoadedDaoImpl();

            for (int attempt = 0; attempt < 50 && loader.get() != null; attempt++) {
                System.gc();
                Thread.sleep(20);
            }

            if (loader.get() != null) {
                throw new AssertionError("A KRYO cache copy of a JDK view retains the loader of a child-loaded DaoImpl");
            }

            System.out.println("CHILD-LOADED DAOIMPL KRYO COPY PROBE PASSED");
        }

        // Separate the setup frame from the reachability check so JVM local-variable liveness cannot retain the fixtures.
        private static WeakReference<ClassLoader> copyThroughChildLoadedDaoImpl() throws Exception {
            final URL classes = DaoImpl.class.getProtectionDomain().getCodeSource().getLocation();
            // Child-first for this library only, as a web application's loader would define it; everything else resolves
            // through the application class path.
            final URLClassLoader loader = new URLClassLoader(new URL[] { classes }, ChildLoadedDaoImplProbe.class.getClassLoader()) {
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

            final Throwable[] failure = new Throwable[1];
            // A short-lived thread: thread-locals set while the library initializes must not outlive the probe. The context
            // loader is left unchanged, as logging frameworks keep a context per context loader.
            final Thread copier = new Thread(() -> {
                try {
                    final Class<?> daoImpl = loader.loadClass(DaoImpl.class.getName());

                    if (daoImpl.getClassLoader() != loader) {
                        throw new AssertionError("DaoImpl was not loaded by the child loader");
                    }

                    copyUnmodifiableList(daoImpl);
                } catch (final Throwable e) {
                    failure[0] = e;
                }
            });
            copier.start();
            copier.join();

            if (failure[0] != null) {
                throw new AssertionError(failure[0]);
            }

            loader.close();

            return new WeakReference<>(loader);
        }

        /** Copies an unmodifiable list through the KRYO cache copy of {@code daoImpl} (the given loader's DaoImpl class). */
        private static void copyUnmodifiableList(final Class<?> daoImpl) throws Exception {
            final Field kryoParserField = daoImpl.getDeclaredField("kryoParser");
            kryoParserField.setAccessible(true);
            final Object kryoParser = kryoParserField.get(null);

            if (kryoParser == null) {
                throw new AssertionError("The Kryo parser is unavailable in the DaoImpl of " + daoImpl.getClassLoader());
            }

            final Constructor<?> kryoCacheCopy = daoImpl.getClassLoader()
                    .loadClass(DaoImpl.class.getName() + "$KryoCacheCopy")
                    .getDeclaredConstructor(Object.class);
            kryoCacheCopy.setAccessible(true);

            final List<String> source = Collections.unmodifiableList(new ArrayList<>(List.of("a", "b")));
            final Method deepCopy = kryoParser.getClass().getMethod("deepCopy", Object.class);
            final Object copy = deepCopy.invoke(kryoParser, kryoCacheCopy.newInstance(source));

            if (copy == source || !source.equals(copy) || copy.getClass() != source.getClass()) {
                throw new AssertionError("Unexpected copy of the unmodifiable list: " + copy);
            }
        }
    }
}
