package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.management.ManagementFactory;
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

@Tag("2025")
class HandlerFactoryRuntimeCompatibilityTest {
    @TempDir
    Path directory;

    @Test
    void registryAndCreationRemainAvailableWithoutSpringOnTheClasspath() throws Exception {
        // HandlerFactory initializes its optional Spring bridge once, so exercise absence in a fresh JVM.
        final String classpath = Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator)))
                .filter(entry -> !Path.of(entry).getFileName().toString().matches("spring-[^/]+\\.jar"))
                .collect(Collectors.joining(File.pathSeparator));
        final String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        final List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
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
        command.addAll(List.of("-cp", classpath, Probe.class.getName()));
        final Path output = directory.resolve("no-spring.log");
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Handler compatibility probe did not finish");
            final String details = Files.readString(output);
            assertEquals(0, process.exitValue(), details);
            assertTrue(details.contains("HANDLER COMPATIBILITY PASSED"), details);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    public static final class Probe {
        public static void main(final String[] args) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName("org.springframework.context.ApplicationContext"));
            final String qualifier = "no-spring-handler";
            assertNull(Jdbc.HandlerFactory.get(qualifier));
            assertNull(Jdbc.HandlerFactory.get(RegisteredHandler.class));

            final RegisteredHandler registered = new RegisteredHandler();
            assertTrue(Jdbc.HandlerFactory.register(qualifier, registered));
            assertSame(registered, Jdbc.HandlerFactory.get(qualifier));
            assertFalse(Jdbc.HandlerFactory.register(qualifier, new RegisteredHandler()));
            assertNull(Jdbc.HandlerFactory.get(RegisteredHandler.class));

            assertTrue(Jdbc.HandlerFactory.register(registered));
            assertSame(registered, Jdbc.HandlerFactory.get(RegisteredHandler.class));
            assertSame(registered, Jdbc.HandlerFactory.get(RegisteredHandler.class.getCanonicalName()));

            assertNull(Jdbc.HandlerFactory.get(CreatedHandler.class));
            final Jdbc.Handler<?> created = Jdbc.HandlerFactory.getOrCreate(CreatedHandler.class);
            assertTrue(created instanceof CreatedHandler);
            assertSame(created, Jdbc.HandlerFactory.getOrCreate(CreatedHandler.class));
            assertSame(created, Jdbc.HandlerFactory.get(CreatedHandler.class));
            assertSame(created, Jdbc.HandlerFactory.get(CreatedHandler.class.getCanonicalName()));
            System.out.println("HANDLER COMPATIBILITY PASSED");
        }

        public static final class RegisteredHandler implements Jdbc.Handler<Object> {
        }

        public static final class CreatedHandler implements Jdbc.Handler<Object> {
        }
    }
}
