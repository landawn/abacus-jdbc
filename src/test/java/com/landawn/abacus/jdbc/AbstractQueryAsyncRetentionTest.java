package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.landawn.abacus.util.ContinuableFuture;
import com.landawn.abacus.util.Throwables;

@org.junit.jupiter.api.Tag("2025")
class AbstractQueryAsyncRetentionTest {

    @TempDir
    Path directory;

    @Test
    void retainedFuturesReleaseQueriesStatementsAndCallbacks() throws Exception {
        final Path output = directory.resolve("async-retention.log");
        final String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        // A small isolated heap with explicit GC enabled makes the reachability check independent of the suite's GC flags.
        final Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(), "-Xmx64m", "-XX:+UseSerialGC",
                "-XX:-DisableExplicitGC", "-cp", System.getProperty("java.class.path"), RetentionProbe.class.getName()).redirectErrorStream(true)
                        .redirectOutput(output.toFile())
                        .start();

        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Async retention probe did not finish");
            assertEquals(0, process.exitValue(), () -> readOutput(output));
            assertTrue(readOutput(output).contains("ASYNC RETENTION PROBE PASSED"), () -> readOutput(output));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static String readOutput(final Path output) {
        try {
            return Files.readString(output);
        } catch (final Exception e) {
            return e.toString();
        }
    }

    public static final class RetentionProbe {
        private static final List<Object> retainedTasks = new ArrayList<>();
        private static final List<WeakReference<?>> releasedObjects = new ArrayList<>();

        public static void main(final String[] args) throws Exception {
            for (final boolean closeAutomatically : new boolean[] { true, false }) {
                for (int scenario = 0; scenario < 6; scenario++) {
                    submitQuery(scenario, closeAutomatically);
                }
            }

            for (int attempt = 0; attempt < 50 && hasRetainedObject(); attempt++) {
                System.gc();
                Thread.sleep(20);
            }

            if (hasRetainedObject()) {
                throw new AssertionError("A retained completed/cancelled/rejected task still owns its query, statement, or callback");
            }

            Reference.reachabilityFence(retainedTasks);
            System.out.println("ASYNC RETENTION PROBE PASSED");
        }

        private static boolean hasRetainedObject() {
            for (final WeakReference<?> reference : releasedObjects) {
                if (reference.get() != null) {
                    return true;
                }
            }

            return false;
        }

        // Keep this setup in a separate stack frame so only the futures/tasks remain strongly reachable during GC.
        private static void submitQuery(final int scenario, final boolean closeAutomatically) throws Exception {
            final AtomicInteger closes = new AtomicInteger();
            final PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(RetentionProbe.class.getClassLoader(),
                    new Class<?>[] { PreparedStatement.class }, (proxy, method, args) -> {
                        if (method.getName().equals("close")) {
                            closes.incrementAndGet();
                        }

                        return switch (method.getReturnType().getName()) {
                            case "boolean" -> false;
                            case "int" -> 0;
                            case "long" -> 0L;
                            default -> null;
                        };
                    });
            final PreparedQuery query = new PreparedQuery(statement).closeAfterExecution(closeAutomatically);
            final CapturedAction action = new CapturedAction(scenario == 2);
            releasedObjects.add(new WeakReference<>(query));
            releasedObjects.add(new WeakReference<>(statement));
            releasedObjects.add(new WeakReference<>(action));

            final AtomicReference<Runnable> scheduled = new AtomicReference<>();
            if (scenario == 5) {
                final RejectedExecutionException rejection = new RejectedExecutionException("rejected");
                try {
                    query.callAsync(action, task -> {
                        scheduled.set(task);
                        throw rejection;
                    });
                    throw new AssertionError("Submission should fail");
                } catch (final RejectedExecutionException expected) {
                    if (expected != rejection) {
                        throw new AssertionError("Submission failure was replaced", expected);
                    }
                }

                // Executors may retain rejected Runnable instances. They must not keep the callback/query alive.
                retainedTasks.add(scheduled.get());
                scheduled.get().run();
                if (action.calls != 0) {
                    throw new AssertionError("Rejected callback ran");
                }
            } else {
                final boolean cancelled = scenario >= 3;
                final ContinuableFuture<?> future = scenario == 1 || scenario == 4
                        ? query.runAsync(q -> action.apply(q), cancelled ? scheduled::set : Runnable::run)
                        : query.callAsync(action, cancelled ? scheduled::set : Runnable::run);
                retainedTasks.add(future);

                if (cancelled) {
                    if (!future.cancel(false)) {
                        throw new AssertionError("Queued task was not cancelled");
                    }
                    retainedTasks.add(scheduled.get());
                    scheduled.get().run();
                    if (action.calls != 0) {
                        throw new AssertionError("Cancelled callback ran");
                    }
                } else {
                    try {
                        future.get();
                        if (scenario == 2) {
                            throw new AssertionError("Callback failure was lost");
                        }
                    } catch (final ExecutionException failure) {
                        if (scenario != 2 || !(failure.getCause() instanceof SQLException)) {
                            throw failure;
                        }
                    }
                }
            }

            if (closes.get() != (closeAutomatically ? 1 : 0)) {
                throw new AssertionError("Automatic close policy was not respected for scenario " + scenario);
            }
            query.close();
        }

        private static final class CapturedAction implements Throwables.Function<PreparedQuery, Integer, SQLException> {
            private final boolean fail;
            private int calls;

            CapturedAction(final boolean fail) {
                this.fail = fail;
            }

            @Override
            public Integer apply(final PreparedQuery query) throws SQLException {
                calls++;
                if (fail) {
                    throw new SQLException("callback failure");
                }
                return 7;
            }
        }
    }
}
