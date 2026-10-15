package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class JdbcCodeGenerationEntityIntegrationTest extends JdbcCodeGenerationUtilTestSupport {

    @Test
    public void testGenerateEntityClassPrimaryKeysFollowIdentifierCaseRules() throws SQLException {
        try (Connection conn = java.sql.DriverManager.getConnection("jdbc:h2:mem:codegen_primary_key_case", "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE case_fold_table (pk_code INT PRIMARY KEY, value_text VARCHAR(20))");
            stmt.execute("CREATE SCHEMA fold_schema");
            stmt.execute("CREATE TABLE fold_schema.case_fold_table (pk_code INT PRIMARY KEY, value_text VARCHAR(20))");
            stmt.execute("CREATE SCHEMA \"MixedSchema\"");
            stmt.execute("CREATE TABLE \"MixedSchema\".\"MixedTable\" (\"customKey\" INT PRIMARY KEY, value_text VARCHAR(20))");

            for (String tableName : List.of("case_fold_table", "fold_schema.case_fold_table")) {
                final String source = JdbcCodeGenerationUtil.generateEntityClass(conn, tableName);
                assertTrue(source.contains("    @Id\n"), source);
                assertTrue(source.contains("public class CaseFoldTable"), source);
                assertTrue(source.contains("    private int pkCode;"), source);
            }

            assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateEntityClass(conn, "\"MixedSchema\".\"MixedTable\""));
            final String quotedSource = JdbcCodeGenerationUtil.generateEntityClassByQuery(conn, "\"MixedSchema\".\"MixedTable\"",
                    "SELECT * FROM \"MixedSchema\".\"MixedTable\" WHERE 1 > 2");
            assertTrue(quotedSource.contains("    @Id\n"), quotedSource);
            assertTrue(quotedSource.contains("@Column(name = \"customKey\")"), quotedSource);
        }
    }

    // Caller-supplied SQL can still address exact table names; primary-key lookup must retain their case.
    @Test
    public void testGenerateEntityClassPrimaryKeysOfSpecialTableNameAreNotCaseFolded() throws SQLException {
        try (Connection conn = java.sql.DriverManager.getConnection("jdbc:h2:mem:codegen_primary_key_special_" + System.nanoTime(), "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE \"order-history\" (order_key INT PRIMARY KEY, note VARCHAR(20))");

            assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateEntityClass(conn, "order-history"));
            final String source = JdbcCodeGenerationUtil.generateEntityClassByQuery(conn, "order-history", "SELECT * FROM \"order-history\" WHERE 1 > 2");

            assertTrue(source.contains("public class OrderHistory"), source);
            assertTrue(source.contains("    @Id\n    @Column(name = \"ORDER_KEY\")"), source);

            // Folding is decided per part: the simple schema name is still folded (to SPECIAL_SCHEMA), the table name is not.
            stmt.execute("CREATE SCHEMA special_schema");
            stmt.execute("CREATE TABLE special_schema.\"order-history\" (other_key INT PRIMARY KEY, note VARCHAR(20))");

            assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateEntityClass(conn, "special_schema.order-history"));
            final String qualifiedSource = JdbcCodeGenerationUtil.generateEntityClassByQuery(conn, "special_schema.order-history",
                    "SELECT * FROM special_schema.\"order-history\" WHERE 1 > 2");

            assertTrue(qualifiedSource.contains("    @Id\n    @Column(name = \"OTHER_KEY\")"), qualifiedSource);
        }
    }

    @Test
    public void testGenerateEntityClassPrimaryKeysStayInCurrentSchema() throws SQLException {
        try (Connection conn = java.sql.DriverManager.getConnection("jdbc:h2:mem:codegen_primary_key_schema_" + System.nanoTime(), "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA other_schema");
            stmt.execute("CREATE TABLE public.shared_table (local_key INT PRIMARY KEY, other_key INT)");
            stmt.execute("CREATE TABLE other_schema.shared_table (local_key INT, other_key INT PRIMARY KEY)");

            final String localSource = JdbcCodeGenerationUtil.generateEntityClass(conn, "shared_table");
            assertTrue(localSource.contains("    @Id\n    @Column(name = \"LOCAL_KEY\")"), localSource);
            assertFalse(localSource.contains("    @Id\n    @Column(name = \"OTHER_KEY\")"), localSource);

            final String qualifiedSource = JdbcCodeGenerationUtil.generateEntityClass(conn, "other_schema.shared_table");
            assertFalse(qualifiedSource.contains("    @Id\n    @Column(name = \"LOCAL_KEY\")"), qualifiedSource);
            assertTrue(qualifiedSource.contains("    @Id\n    @Column(name = \"OTHER_KEY\")"), qualifiedSource);

            conn.setSchema("OTHER_SCHEMA");
            final String switchedSource = JdbcCodeGenerationUtil.generateEntityClass(conn, "shared_table");
            assertFalse(switchedSource.contains("    @Id\n    @Column(name = \"LOCAL_KEY\")"), switchedSource);
            assertTrue(switchedSource.contains("    @Id\n    @Column(name = \"OTHER_KEY\")"), switchedSource);
        }
    }

    @Test
    public void testGenerateEntityClassPrimaryKeysFollowSchemaSearchPath() throws SQLException {
        try (Connection conn = java.sql.DriverManager.getConnection("jdbc:h2:mem:codegen_primary_key_search_path_" + System.nanoTime(), "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA other_schema");
            stmt.execute("CREATE TABLE other_schema.path_table (other_key INT PRIMARY KEY, payload VARCHAR(20))");
            stmt.execute("SET SCHEMA_SEARCH_PATH PUBLIC, OTHER_SCHEMA");
            assertEquals("PUBLIC", conn.getSchema());

            final String source = JdbcCodeGenerationUtil.generateEntityClass(conn, "path_table");

            assertTrue(source.contains("    @Id\n    @Column(name = \"OTHER_KEY\")"), source);
            assertEquals("PUBLIC", conn.getSchema());
        }
    }

    // A requested import that equals a default import was skipped as a duplicate, and the default import was then pruned
    // as unused (the scans only recognize annotation lines), so a body using the type did not compile.
    @Test
    public void testGenerateEntityClass_RequestedDefaultImportIsNotPrunedAsUnused(@TempDir final Path directory) throws IOException {
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .className("OrderHistory")
                .classNamesToImport(List.of("com.landawn.abacus.util.NamingPolicy", "com.landawn.abacus.annotation.ReadOnly"))
                .additionalClassBodySource("    private NamingPolicy namingPolicy = NamingPolicy.CAMEL_CASE;\n    @Deprecated @ReadOnly private String note;")
                .build();
        final String code = JdbcCodeGenerationUtil.generateEntityClass("order_history", resultSet, config);

        assertTrue(code.contains("import com.landawn.abacus.util.NamingPolicy;\n"), code);
        assertTrue(code.contains("import com.landawn.abacus.annotation.ReadOnly;\n"), code);

        final Path source = directory.resolve("OrderHistory.java");
        final ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        Files.writeString(source, code, StandardCharsets.UTF_8);
        final int exitCode = ToolProvider.getSystemJavaCompiler()
                .run(null, diagnostics, diagnostics, "-encoding", "UTF-8", "-classpath", System.getProperty("java.class.path"), "-processor",
                        "lombok.launch.AnnotationProcessorHider$AnnotationProcessor", "-d", directory.toString(), source.toString());
        assertEquals(0, exitCode, () -> diagnostics.toString(StandardCharsets.UTF_8) + "\n" + code);
    }

    // The same holds for the imports pruned up front: Id when no generated field gets @Id (here the key column is excluded and
    // re-declared in the body), and Builder/Accessors when the class-level annotations are disabled (here used by a nested class).
    @Test
    public void testGenerateEntityClass_RequestedIdAndLombokImportsAreNotPrunedAsUnused(@TempDir final Path directory) throws IOException {
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .className("OrderHistory")
                .excludedFields(List.of("id"))
                .classNamesToImport(List.of("com.landawn.abacus.annotation.Id", "lombok.Builder", "lombok.experimental.Accessors"))
                .additionalClassBodySource(String.join("\n", //
                        "    @Id", //
                        "    @Column(name = \"id\")", //
                        "    private long id;", //
                        "    @Builder", //
                        "    @Accessors(fluent = true)", //
                        "    public static class Item {", //
                        "        private String name;", //
                        "    }"))
                .build();
        final String code = JdbcCodeGenerationUtil.generateEntityClass("order_history", resultSet, config);

        assertTrue(code.contains("import com.landawn.abacus.annotation.Id;\n"), code);
        assertTrue(code.contains("import lombok.Builder;\n"), code);
        assertTrue(code.contains("import lombok.experimental.Accessors;\n"), code);
        // The disabled class-level annotations are still left out.
        assertFalse(code.contains("\n@Builder\n"), code);
        assertFalse(code.contains("\n@Accessors(chain = true)\n"), code);

        final Path source = directory.resolve("OrderHistory.java");
        final ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        Files.writeString(source, code, StandardCharsets.UTF_8);
        final int exitCode = ToolProvider.getSystemJavaCompiler()
                .run(null, diagnostics, diagnostics, "-encoding", "UTF-8", "-classpath", System.getProperty("java.class.path"), "-processor",
                        "lombok.launch.AnnotationProcessorHider$AnnotationProcessor", "-d", directory.toString(), source.toString());
        assertEquals(0, exitCode, () -> diagnostics.toString(StandardCharsets.UTF_8) + "\n" + code);
    }

    // BUG FIX: the java.util auto-import only looked at the raw type before the first '<'. A nested parameterized
    // type (List in Map<String, List<String>>) was never imported; Map.Entry<K, V> imported java.util.Map.Entry, which
    // leaves the qualifier Map unresolved; and Optional<String> was auto-imported from java.util even when
    // classNamesToImport already imports another Optional. Each generated class must compile.
    @Test
    public void testGenerateEntityClass_AdditionalGenericFieldImportsCompile(@TempDir final Path directory) throws IOException {
        final Path source = directory.resolve("OrderHistory.java");
        final ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();

        final List<JdbcCodeGenerationUtil.EntityCodeConfig> configs = List.of(
                JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                        .className("OrderHistory")
                        .additionalClassBodySource("private Map<String, List<String>> index;")
                        .build(),
                JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                        .className("OrderHistory")
                        .additionalClassBodySource("private Map.Entry<String, Integer> entry;")
                        .build(),
                JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                        .className("OrderHistory")
                        .classNamesToImport(List.of("com.landawn.abacus.util.u.Optional"))
                        .additionalClassBodySource("private Optional<String> maybe = Optional.of(\"x\");")
                        .build());

        for (final JdbcCodeGenerationUtil.EntityCodeConfig config : configs) {
            final String code = JdbcCodeGenerationUtil.generateEntityClass("order_history", resultSet, config);
            Files.writeString(source, code, StandardCharsets.UTF_8);
            diagnostics.reset();
            final int exitCode = ToolProvider.getSystemJavaCompiler()
                    .run(null, diagnostics, diagnostics, "-encoding", "UTF-8", "-classpath", System.getProperty("java.class.path"), "-processor",
                            "lombok.launch.AnnotationProcessorHider$AnnotationProcessor", "-d", directory.toString(), source.toString());
            assertEquals(0, exitCode, () -> diagnostics.toString(StandardCharsets.UTF_8) + "\n" + code);
        }
    }

    // Arrays, wildcards/bounds, whitespace before '<', fully qualified generic types and an explicitly imported
    // java.util type: every parameterized java.util simple name is imported exactly once, and nothing else.
    @Test
    public void testGenerateEntityClass_AdditionalGenericFieldImportsEdgeCasesCompile(@TempDir final Path directory) throws IOException {
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .className("OrderHistory")
                .classNamesToImport(List.of("java.util.Queue"))
                .additionalClassBodySource(String.join("\n", //
                        "    private Set<String>[] sets;", //
                        "    private Map<String, ? extends Deque<Integer>> bounded;", //
                        "    private TreeMap <String, Comparable<String>> spaced;", //
                        "    private java.util.List<NavigableSet<Long>> qualified;", //
                        "    private java.util.Map.Entry<String, Integer> qualifiedEntry;", //
                        "    private Queue<String> explicitlyImported;"))
                .build();
        final String code = JdbcCodeGenerationUtil.generateEntityClass("order_history", resultSet, config);

        for (final String simpleName : List.of("Set", "Map", "Deque", "TreeMap", "NavigableSet", "Queue")) {
            assertEquals(1, code.split("import java\\.util\\." + simpleName + ";", -1).length - 1, () -> simpleName + " import count:\n" + code);
        }

        assertFalse(code.contains("import java.util.List;"), code);
        assertFalse(code.contains("import java.util.Comparable;"), code);
        assertFalse(code.contains("import java.util.java;"), code);

        final Path source = directory.resolve("OrderHistory.java");
        final ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        Files.writeString(source, code, StandardCharsets.UTF_8);
        final int exitCode = ToolProvider.getSystemJavaCompiler()
                .run(null, diagnostics, diagnostics, "-encoding", "UTF-8", "-classpath", System.getProperty("java.class.path"), "-processor",
                        "lombok.launch.AnnotationProcessorHider$AnnotationProcessor", "-d", directory.toString(), source.toString());
        assertEquals(0, exitCode, () -> diagnostics.toString(StandardCharsets.UTF_8) + "\n" + code);
    }

    // BUG FIX: the NonUpdatable import was dropped up front whenever no configured non-updatable field matched a
    // column, so an additionalClassBodySource using @NonUpdatable did not compile. It is now pruned only by the final
    // scan of the emitted lines, like the other annotation imports.
    @Test
    public void testGenerateEntityClass_AdditionalBodyNonUpdatableKeepsImport(@TempDir final Path directory) throws IOException {
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .className("OrderHistory")
                .additionalClassBodySource("    @NonUpdatable\n    private String note;")
                .build();
        final String code = JdbcCodeGenerationUtil.generateEntityClass("order_history", resultSet, config);
        final Path source = directory.resolve("OrderHistory.java");
        final ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        Files.writeString(source, code, StandardCharsets.UTF_8);

        final int exitCode = ToolProvider.getSystemJavaCompiler()
                .run(null, diagnostics, diagnostics, "-encoding", "UTF-8", "-classpath", System.getProperty("java.class.path"), "-processor",
                        "lombok.launch.AnnotationProcessorHider$AnnotationProcessor", "-d", directory.toString(), source.toString());
        assertEquals(0, exitCode, () -> diagnostics.toString(StandardCharsets.UTF_8) + "\n" + code);
        assertFalse(JdbcCodeGenerationUtil.generateEntityClass("order_history", resultSet, null).contains("NonUpdatable"));
    }

    // Members of a nested type body (and block-commented declarations) were recognized as fields of the generated class,
    // so copy() assigned copy.street = this.street and the source did not compile. A nested body's generic fields still get
    // their java.util imports.
    @Test
    public void testGenerateEntityClass_CopyMethodSkipsMembersOfNestedBodies(@TempDir final Path directory) throws IOException {
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .className("OrderHistory")
                .generateCopyMethod(true)
                .additionalClassBodySource(String.join("\n", //
                        "    private Address address;", //
                        "    /*", //
                        "    private String legacyNote;", //
                        "    */", //
                        "    @lombok.Data", //
                        "    public static class Address {", //
                        "        private String street;", //
                        "        private List<String> lines;", //
                        "    }"))
                .build();
        final String code = JdbcCodeGenerationUtil.generateEntityClass("order_history", resultSet, config);

        assertTrue(code.contains("copy.address = this.address;"), code);
        assertFalse(code.contains("copy.street"), code);
        assertFalse(code.contains("copy.lines"), code);
        assertFalse(code.contains("copy.legacyNote"), code);
        assertTrue(code.contains("import java.util.List;"), code);

        final Path source = directory.resolve("OrderHistory.java");
        final ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        Files.writeString(source, code, StandardCharsets.UTF_8);
        final int exitCode = ToolProvider.getSystemJavaCompiler()
                .run(null, diagnostics, diagnostics, "-encoding", "UTF-8", "-classpath", System.getProperty("java.class.path"), "-processor",
                        "lombok.launch.AnnotationProcessorHider$AnnotationProcessor", "-d", directory.toString(), source.toString());
        assertEquals(0, exitCode, () -> diagnostics.toString(StandardCharsets.UTF_8) + "\n" + code);
    }

    // Edge cases of the top-level detection: braces in char/string literals, an escaped quote and "//" inside a string, braces
    // of an annotation and an array initializer closed on their line, a text block holding braces, an escaped """ and a
    // field-like line, an anonymous class body spanning lines, and CRLF line ends.
    @Test
    public void testGenerateEntityClass_CopyMethodTopLevelDetectionHandlesLiteralsAndCrlf(@TempDir final Path directory) throws IOException {
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .className("OrderHistory")
                .generateCopyMethod(true)
                .additionalClassBodySource(String.join("\r\n", //
                        "    private char open = '{';", //
                        "    private String close = \"}\\\" // {\";", //
                        "    @SuppressWarnings({ \"unused\", \"rawtypes\" })", //
                        "    private int[] codes = { 1, 2 };", //
                        "    private Runnable task = new Runnable() {", //
                        "        private int runs;", //
                        "        public void run() { runs++; }", //
                        "    };", //
                        "    private String sql = \"\"\"", //
                        "        } \\\"\"\" {", //
                        "        private String fake;", //
                        "        \"\"\";", //
                        "    private String note;"))
                .build();
        final String code = JdbcCodeGenerationUtil.generateEntityClass("order_history", resultSet, config);

        for (final String field : List.of("open", "close", "codes", "note")) {
            assertTrue(code.contains("copy." + field + " = this." + field + ";"), code);
        }

        assertFalse(code.contains("copy.runs"), code);
        assertFalse(code.contains("copy.fake"), code);

        final Path source = directory.resolve("OrderHistory.java");
        final ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        Files.writeString(source, code, StandardCharsets.UTF_8);
        final int exitCode = ToolProvider.getSystemJavaCompiler()
                .run(null, diagnostics, diagnostics, "-encoding", "UTF-8", "-classpath", System.getProperty("java.class.path"), "-processor",
                        "lombok.launch.AnnotationProcessorHider$AnnotationProcessor", "-d", directory.toString(), source.toString());
        assertEquals(0, exitCode, () -> diagnostics.toString(StandardCharsets.UTF_8) + "\n" + code);
    }

    @Test
    public void testGeneratedClassCompilesWhenEveryColumnIsExcluded(@TempDir final Path directory) throws IOException {
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .className("EmptyEntity")
                .excludedFields(List.of("id", "created_at", "status"))
                .generateBuilder(true)
                .generateCopyMethod(true)
                .build();
        final Path source = directory.resolve("EmptyEntity.java");
        final ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();

        for (final String additionalFields : List.of("", "private static final long version = 1L;", "private final String note = \"fixed\";",
                "private String note;", "String note;", "@Deprecated private String note;", "@lombok.Builder.Default private final String note = \"fixed\";",
                "@lombok.Builder.Default\nprivate final String note = \"fixed\";", "private String\n    note;",
                "private static int first; private final String note = \"fixed\", other = \"second\"; // recognized fields")) {
            config.setAdditionalClassBodySource(additionalFields);
            Files.writeString(source, JdbcCodeGenerationUtil.generateEntityClass("empty_entity", resultSet, config), StandardCharsets.UTF_8);
            diagnostics.reset();
            final int exitCode = ToolProvider.getSystemJavaCompiler()
                    .run(null, diagnostics, diagnostics, "-encoding", "UTF-8", "-classpath", System.getProperty("java.class.path"), "-processor",
                            "lombok.launch.AnnotationProcessorHider$AnnotationProcessor", "-d", directory.toString(), source.toString());
            assertEquals(0, exitCode, diagnostics.toString(StandardCharsets.UTF_8));
        }
    }
}
