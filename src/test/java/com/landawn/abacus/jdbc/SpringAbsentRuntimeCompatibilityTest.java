package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.annotation.Table;
import com.landawn.abacus.jdbc.dao.CrudDao;
import com.landawn.abacus.jdbc.dao.UncheckedCrudDao;
import com.landawn.abacus.query.Filters;

/**
 * Spring is an optional ({@code provided}) dependency. {@code JdbcUtil}'s static initializer reads the methods of the
 * built-in DAO interfaces, and creating a DAO proxies its interface, which reads every method it inherits; reading a method
 * loads the exception types in its {@code throws} clause. The DAO interfaces declared Spring's
 * {@code CannotGetJdbcConnectionException} there, so without spring-jdbc on the class path {@code JdbcUtil} failed to load
 * with {@code NoClassDefFoundError}. The exception is now only documented ({@code @throws}), not declared, also by
 * {@code JdbcUtil}, {@code DataTransferUtil} and {@code JdbcCodeGenerationUtil}, whose callers then compile without Spring,
 * and no class but the Spring adapters names a Spring type in a member's signature, so reflection works without Spring too.
 */
@Tag("integration")
@Tag("2025")
class SpringAbsentRuntimeCompatibilityTest {

    @TempDir
    Path directory;

    @Test
    void jdbcUtilAndDaosWorkWithoutSpringOnTheClasspath() throws Exception {
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

        command.addAll(List.of("-cp", springFreeClasspath(), Probe.class.getName()));
        final Path output = directory.resolve("no-spring.log");
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();

        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Spring-absent probe did not finish");
            final String details = Files.readString(output);
            assertEquals(0, process.exitValue(), details);
            assertTrue(details.contains("SPRING-ABSENT RUNTIME PASSED"), details);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    // javac loads each exception type a called method declares, to tell checked from unchecked ones: JdbcUtil, DataTransferUtil
    // and JdbcCodeGenerationUtil still declared CannotGetJdbcConnectionException, so a client calling them didn't compile
    // without spring-jdbc ("cannot access CannotGetJdbcConnectionException"), although it runs without it.
    @Test
    void clientsCompileWithoutSpringOnTheClasspath() throws Exception {
        final Path source = directory.resolve("SpringFreeClient.java");
        Files.writeString(source, """
                import com.landawn.abacus.jdbc.DataTransferUtil;
                import com.landawn.abacus.jdbc.JdbcCodeGenerationUtil;
                import com.landawn.abacus.jdbc.JdbcUtil;
                import com.landawn.abacus.jdbc.SqlTransaction;

                public class SpringFreeClient {
                    static void run(final javax.sql.DataSource ds, final java.io.File file) throws Exception {
                        final SqlTransaction tran = JdbcUtil.beginTransaction(ds);

                        try {
                            JdbcUtil.isInTransaction(ds);
                            JdbcUtil.prepareQuery(ds, "SELECT 1").queryForInt();
                            JdbcUtil.executeUpdate(ds, "DELETE FROM t");
                            JdbcUtil.tableExists(ds, "t");
                            DataTransferUtil.exportCsv(ds, "SELECT 1", file);
                            JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(ds, "INSERT INTO t (a) VALUES (1)");
                            tran.commit();
                        } finally {
                            tran.rollbackIfNotCommitted();
                        }
                    }
                }
                """);
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "no system Java compiler");
        final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            final List<String> options = List.of("-proc:none", "-classpath", springFreeClasspath(), "-d", directory.toString());
            final boolean compiled = compiler.getTask(null, fileManager, diagnostics, options, null, fileManager.getJavaFileObjects(source.toFile())).call();

            assertTrue(compiled, () -> diagnostics.getDiagnostics().stream().map(Object::toString).collect(Collectors.joining("\n")));
        }
    }

    private static String springFreeClasspath() {
        return Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator)))
                .filter(entry -> !Path.of(entry).getFileName().toString().matches("spring-[^/]+\\.jar"))
                .collect(Collectors.joining(File.pathSeparator));
    }

    public static final class Probe {
        /**
         * The classes whose purpose is Spring integration, only loaded when Spring is on the class path. Named, not referenced:
         * a class literal would load the class.
         */
        private static final List<String> SPRING_ADAPTER_CLASSES = List.of("com.landawn.abacus.jdbc.SpringApplicationContext",
                "com.landawn.abacus.jdbc.JdbcUtil$SpringRollbackActions", "com.landawn.abacus.jdbc.JdbcUtil$SpringConnectionHolderAccess");

        public static void main(final String[] args) throws Exception {
            assertThrows(ClassNotFoundException.class, () -> Class.forName("org.springframework.jdbc.CannotGetJdbcConnectionException"));

            final JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:spring_absent_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");

            try (Connection conn = dataSource.getConnection();
                 Statement statement = conn.createStatement()) {
                statement.execute("CREATE TABLE spring_absent_item (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(10))");
            }

            final ItemDao dao = JdbcUtil.createDao(ItemDao.class, dataSource);
            final UncheckedItemDao uncheckedDao = JdbcUtil.createDao(UncheckedItemDao.class, dataSource);
            assertFalse(JdbcUtil.isInTransaction(dataSource));

            // A JdbcUtil transaction: rolled back, it also puts back the generated ID the inserts wrote onto the entity.
            final Item rolledBack = item("a");
            final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);

            try {
                assertNotNull(dao.insert(rolledBack, List.of("name")));
                assertNotNull(dao.insert(rolledBack, List.of("name")));
                assertTrue(JdbcUtil.isInTransaction(dataSource));
            } finally {
                tran.rollbackIfNotCommitted();
            }

            assertNull(rolledBack.getId());

            final Item inserted = item("b");
            final Long id = dao.insert(inserted);
            assertEquals(id, inserted.getId());
            assertEquals("b", dao.getOrNull(id).getName());
            assertEquals(2, dao.batchInsert(List.of(item("c"), item("d")), 10).size());
            assertNotNull(uncheckedDao.insert(item("e")));
            assertEquals(4, uncheckedDao.list(Filters.isNotNull("name")).size());
            assertEquals(4, JdbcUtil.prepareQuery(dataSource, "SELECT COUNT(*) FROM spring_absent_item").queryForInt().orElse(-1));

            // The CRUD read helpers (ID de-duplication, ID conditions, refresh) run without Spring too, in both twins.
            assertEquals(1, dao.batchGet(List.of(id, id)).size());
            assertEquals(1, dao.count(List.of(id)));
            assertTrue(dao.refresh(inserted));
            assertEquals(1, uncheckedDao.batchGet(List.of(id, id)).size());
            assertEquals(1, uncheckedDao.count(List.of(id)));
            assertTrue(uncheckedDao.refresh(inserted));
            assertEquals("b", inserted.getName());

            // DataTransferUtil runs without Spring: its own Spring flag is off, so isSameConnection compares the two connections
            // by identity instead of unwrapping Spring connection proxies (a Spring-typed local or field on that path would fail
            // here rather than at a customer), and the CSV export runs as well.
            try (Connection source = dataSource.getConnection();
                 Connection target = dataSource.getConnection();
                 Statement statement = target.createStatement()) {
                statement.execute("CREATE TABLE spring_absent_copy (id BIGINT PRIMARY KEY, name VARCHAR(10))");
                assertEquals(4, DataTransferUtil.copy(source, "SELECT id, name FROM spring_absent_item", target,
                        "INSERT INTO spring_absent_copy (id, name) VALUES (?, ?)"));
                assertEquals(4, JdbcUtil.prepareQuery(target, "SELECT COUNT(*) FROM spring_absent_copy").queryForInt().orElse(-1));

                final StringWriter csv = new StringWriter();
                assertEquals(4, DataTransferUtil.exportCsv(source, "SELECT id, name FROM spring_absent_copy ORDER BY id", csv));
                assertTrue(csv.toString().lines().count() >= 4, csv.toString());
            }

            assertReflectableWithoutSpring();

            System.out.println("SPRING-ABSENT RUNTIME PASSED");
        }

        /**
         * Reading the members of a class by reflection loads every type its fields, method and constructor signatures and
         * {@code throws} clauses name, which frameworks, mocking libraries and native-image analysis do: JdbcUtil,
         * DataTransferUtil (and its builders), JdbcCodeGenerationUtil and DaoImpl declared CannotGetJdbcConnectionException,
         * a private JdbcUtil method took a Spring ConnectionHolder, and Jdbc.HandlerFactory kept a field of a type implementing
         * Spring interfaces. Only the Spring adapter classes may need Spring now.
         */
        private static void assertReflectableWithoutSpring() throws Exception {
            final Path classes = Path.of(JdbcUtil.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            final List<String> failures = new ArrayList<>();
            int checked = 0;

            try (Stream<Path> files = Files.walk(classes.resolve(JdbcUtil.class.getPackageName().replace('.', File.separatorChar)))) {
                for (final Path file : files.filter(it -> it.toString().endsWith(".class")).sorted().toList()) {
                    final String relativePath = classes.relativize(file).toString();
                    final String className = relativePath.substring(0, relativePath.length() - ".class".length()).replace(File.separatorChar, '.');

                    if (SPRING_ADAPTER_CLASSES.stream().anyMatch(it -> className.equals(it) || className.startsWith(it + "$"))) {
                        continue;
                    }

                    try {
                        final Class<?> cls = Class.forName(className, false, Probe.class.getClassLoader());
                        cls.getDeclaredFields();
                        cls.getDeclaredMethods();
                        cls.getDeclaredConstructors();
                        checked++;
                    } catch (final LinkageError | ClassNotFoundException e) {
                        failures.add(className + ": " + e);
                    }
                }
            }

            assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
            assertTrue(checked > 100, "classes checked: " + checked);
        }

        private static Item item(final String name) {
            final Item item = new Item();
            item.setName(name);
            return item;
        }

        public interface ItemDao extends CrudDao<Item, Long, ItemDao> {
        }

        public interface UncheckedItemDao extends UncheckedCrudDao<Item, Long, UncheckedItemDao> {
        }

        @Table("spring_absent_item")
        public static class Item {
            @Id
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
    }
}
