package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import com.landawn.abacus.exception.UncheckedIOException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.JdbcCodeGenerationUtil.EntityCodeConfig.FieldMapping;
import com.landawn.abacus.util.NamingPolicy;
import com.landawn.abacus.util.Tuple;

public class JdbcCodeGenerationEntityTest extends JdbcCodeGenerationUtilTestSupport {

    @ParameterizedTest
    @ValueSource(strings = { "private transient int extra = 1;", "private volatile long extra;", "private int extra[] = new int[0];",
            "private java.util.List<String> extra = java.util.Collections.<String>emptyList();",
            "private java.util.List<java.util.List<String>> extra = new java.util.ArrayList< java.util.List<String> >();",
            "private char extra = '\\''; // a quoted character" })
    void copyMethodRecognizesModifiersArraysAndGenericInitializers(final String declaration) {
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .className("OrderHistory")
                .generateCopyMethod(true)
                .additionalClassBodySource(declaration)
                .build();

        final String generated = JdbcCodeGenerationUtil.generateEntityClass("order_history", resultSet, config);

        assertTrue(generated.contains(declaration), generated);
        assertTrue(generated.contains("copy.extra = this.extra;"), generated);
    }

    @ParameterizedTest
    @ValueSource(classes = { WrongTableMember.class, ExtraRequiredTableMember.class, FieldOnlyTable.class })
    void rejectsAnnotationsThatCannotRepresentTheGeneratedTable(final Class<? extends Annotation> annotation) {
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().tableAnnotationClass(annotation).build();

        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateEntityClass("order_history", resultSet, config));

        assertTrue(failure.getMessage().contains("tableAnnotationClass"), failure.getMessage());
        assertTrue(failure.getMessage().contains(annotation.getCanonicalName()), failure.getMessage());
    }

    // Test generateEntityClass(Connection, tableName) overload
    @Test
    public void testGenerateEntityClass_Connection_TableName() throws SQLException {
        setupFullGenerateEntityClassMock();
        String result = JdbcCodeGenerationUtil.generateEntityClass(connection, "order_history");
        assertNotNull(result);
        assertTrue(result.contains("OrderHistory") || result.contains("class "));
    }

    // Test generateEntityClass(Connection, tableName, EntityCodeConfig) overload
    @Test
    public void testGenerateEntityClass_Connection_TableName_Config() throws SQLException {
        setupFullGenerateEntityClassMock();
        JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .className("OrderHistory")
                .useBoxedTypes(true)
                .build();
        String result = JdbcCodeGenerationUtil.generateEntityClass(connection, "order_history", config);
        assertNotNull(result);
        assertTrue(result.contains("OrderHistory"));
    }

    // Test generateEntityClass(Connection, entityName, query) overload
    @Test
    public void testGenerateEntityClass_Connection_EntityName_Query() throws SQLException {
        setupFullGenerateEntityClassMock();
        String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2");
        assertNotNull(result);
    }

    // Test generateEntityClass with DataSource overload
    @Test
    public void testGenerateEntityClass_DataSource_TableName() throws SQLException {
        setupFullGenerateEntityClassMock();
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        String result = JdbcCodeGenerationUtil.generateEntityClass(dataSource, "order_history");
        assertNotNull(result);
    }

    // Test generateEntityClass with DataSource and config overload
    @Test
    public void testGenerateEntityClass_DataSource_TableName_Config() throws SQLException {
        setupFullGenerateEntityClassMock();
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().className("OrderHist").build();
        String result = JdbcCodeGenerationUtil.generateEntityClass(dataSource, "order_history", config);
        assertNotNull(result);
    }

    // Test generateEntityClass with DataSource, query, and config
    @Test
    public void testGenerateEntityClass_DataSource_EntityName_Query_Config() throws SQLException {
        setupFullGenerateEntityClassMock();
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(dataSource, "order_history", "SELECT * FROM order_history WHERE 1 > 2", null);
        assertNotNull(result);
    }

    // Test generateEntityClass with packageName config
    @Test
    public void testGenerateEntityClass_WithPackageName() throws SQLException {
        setupFullGenerateEntityClassMock();
        JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().packageName("com.example.model").build();
        String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("package com.example.model"));
    }

    // Test generateEntityClass with idField config
    @Test
    public void testGenerateEntityClass_WithIdField() throws SQLException {
        setupFullGenerateEntityClassMock();
        JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().idFields(List.of("id")).build();
        String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("@Id"));
    }

    // Test generateEntityClass with readOnlyFields config
    @Test
    public void testGenerateEntityClass_WithReadOnlyFields() throws SQLException {
        setupFullGenerateEntityClassMock();
        JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().readOnlyFields(Arrays.asList("created_at")).build();
        String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("@ReadOnly"));
    }

    // Test generateEntityClass with excludedFields config
    @Test
    public void testGenerateEntityClass_WithExcludedFields() throws SQLException {
        setupFullGenerateEntityClassMock();
        JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().excludedFields(Arrays.asList("status")).build();
        String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
    }

    // Test generateEntityClass with generateCopyMethod config
    @Test
    public void testGenerateEntityClass_WithCopyMethod() throws SQLException {
        setupFullGenerateEntityClassMock();
        JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .generateCopyMethod(true)
                .className("OrderHistory")
                .build();
        String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
    }

    @Test
    public void testGenerateEntityClass_CopyMethodParsesInitializedAdditionalFieldName() throws SQLException {
        setupFullGenerateEntityClassMock();
        JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .generateCopyMethod(true)
                .className("OrderHistory")
                .additionalClassBodySource("    private int retryCount = 1;")
                .build();

        String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);

        assertTrue(result.contains("private int retryCount = 1;"));
        assertTrue(result.contains("copy.retryCount = this.retryCount;"));
        assertFalse(result.contains("copy.1 = this.1;"));
    }

    @Test
    public void testGenerateEntityClass_AdditionalFieldWithGenericTypeComma() throws SQLException {
        setupFullGenerateEntityClassMock();
        JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .generateCopyMethod(true)
                .className("OrderHistory")
                .additionalClassBodySource("    private Map<String, Object> attrs;")
                .build();

        // A generic type with a comma (Map<String, Object>) must not be mis-parsed as a multi-variable declaration.
        String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);

        assertTrue(result.contains("private Map<String, Object> attrs;"));
        assertTrue(result.contains("copy.attrs = this.attrs;"));
        assertFalse(result.contains("copy.Object> = this.Object>;"));
    }

    // Test generateEntityClass with conflicting readOnly and nonUpdatable for same field

    @Test
    public void testGenerateEntityClass_ConflictingReadOnlyAndNonUpdatable() throws SQLException {
        setupFullGenerateEntityClassMock();
        JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .readOnlyFields(Arrays.asList("id"))
                .nonUpdatableFields(Arrays.asList("id"))
                .build();
        assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config));
    }

    // Same conflict as above, but spelled with the two accepted name forms: read-only by column name
    // ("created_at") and non-updatable by field name ("createdAt"). The up-front commonSet check only
    // catches identical spellings, so the per-field emission pass must reject this instead of silently
    // dropping @NonUpdatable.
    @Test
    public void testGenerateEntityClass_ConflictingReadOnlyAndNonUpdatableMixedNameForms() throws SQLException {
        setupFullGenerateEntityClassMock();
        JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .readOnlyFields(Arrays.asList("created_at"))
                .nonUpdatableFields(Arrays.asList("createdAt"))
                .build();
        assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config));
    }

    // generateEntityClass(DataSource, entityName, query) - line 261
    @Test
    public void testGenerateEntityClass_DataSource_EntityName_Query() throws SQLException {
        setupFullGenerateEntityClassMock();
        final Statement stmt = Mockito.mock(Statement.class);
        final ResultSet pkRs = Mockito.mock(ResultSet.class);
        when(resultSet.getStatement()).thenReturn(stmt);
        when(stmt.getConnection()).thenReturn(connection);
        when(databaseMetaData.getPrimaryKeys(null, null, "MyEntity")).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(false);
        when(connection.prepareStatement("SELECT * FROM v WHERE 1 > 2")).thenReturn(preparedStatement);

        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(dataSource, "MyEntity", "SELECT * FROM v WHERE 1 > 2");
        assertNotNull(result);
        assertTrue(result.contains("MyEntity"));
    }

    @Test
    public void testGenerateEntityClassDetectsPrimaryKeyOfDelimitedSinglePartName() throws SQLException {
        final ResultSet pkRs = Mockito.mock(ResultSet.class);
        final ResultSet noPrimaryKeys = Mockito.mock(ResultSet.class);
        when(resultSet.getStatement()).thenReturn(preparedStatement);
        when(preparedStatement.getConnection()).thenReturn(connection);
        when(databaseMetaData.getPrimaryKeys(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyString())).thenReturn(noPrimaryKeys);
        when(databaseMetaData.getPrimaryKeys(null, null, "order_history")).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(true, false);
        when(pkRs.getString("COLUMN_NAME")).thenReturn("id");

        final String source = JdbcCodeGenerationUtil.generateEntityClass("\"order_history\"", resultSet, null);

        assertTrue(source.contains("    @Id\n"), source);
        Mockito.verify(databaseMetaData).getPrimaryKeys(null, null, "order_history");
    }

    @Test
    public void testGenerateEntityClassPrimaryKeysFallBackWhenColumnOriginIsUnsupported() throws SQLException {
        final ResultSet pkRs = Mockito.mock(ResultSet.class);
        when(resultSet.getStatement()).thenReturn(preparedStatement);
        when(preparedStatement.getConnection()).thenReturn(connection);
        when(connection.getCatalog()).thenReturn("current_catalog");
        when(connection.getSchema()).thenReturn("current_schema");
        when(resultSetMetaData.getTableName(1)).thenThrow(new java.sql.SQLFeatureNotSupportedException("column origin unavailable"));
        when(databaseMetaData.getPrimaryKeys("current_catalog", "current_schema", "order_history")).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(true, false);
        when(pkRs.getString("COLUMN_NAME")).thenReturn("id");

        final String source = JdbcCodeGenerationUtil.generateEntityClass("order_history", resultSet, null);

        assertTrue(source.contains("    @Id\n"), source);
        Mockito.verify(databaseMetaData).getPrimaryKeys("current_catalog", "current_schema", "order_history");
    }

    // generateEntityClass with custom tableAnnotationClass and columnAnnotationClass (L418, L421)
    @Test
    public void testGenerateEntityClass_WithCustomAnnotationClasses() throws SQLException {
        setupFullGenerateEntityClassMock();

        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .tableAnnotationClass(jakarta.persistence.Table.class)
                .columnAnnotationClass(jakarta.persistence.Column.class)
                .build();

        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
    }

    // generateEntityClass with additionalClassBodySource set (L444-455)
    @Test
    public void testGenerateEntityClass_WithAdditionalFieldsOrLines() throws SQLException {
        setupFullGenerateEntityClassMock();

        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .additionalClassBodySource("private String extraField; // extra\nprivate int extraCount;")
                .build();

        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
    }

    // Primary key column populates idFields (L496)
    @Test
    public void testGenerateEntityClass_WithPrimaryKey() throws SQLException {
        final Statement stmt = Mockito.mock(Statement.class);
        final ResultSet pkRs = Mockito.mock(ResultSet.class);
        when(resultSet.getStatement()).thenReturn(stmt);
        when(stmt.getConnection()).thenReturn(connection);
        when(databaseMetaData.getPrimaryKeys(null, null, "order_history")).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(true, false);
        when(pkRs.getString("COLUMN_NAME")).thenReturn("id");
        when(resultSetMetaData.getColumnName(1)).thenReturn("id");
        when(resultSetMetaData.getColumnName(2)).thenReturn("created_at");
        when(resultSetMetaData.getColumnName(3)).thenReturn("status");
        when(resultSetMetaData.getColumnType(1)).thenReturn(Types.BIGINT);
        when(resultSetMetaData.getColumnType(2)).thenReturn(Types.TIMESTAMP);
        when(resultSetMetaData.getColumnType(3)).thenReturn(Types.VARCHAR);
        when(resultSetMetaData.getColumnClassName(1)).thenReturn("java.lang.Long");
        when(resultSetMetaData.getColumnClassName(2)).thenReturn("java.sql.Timestamp");
        when(resultSetMetaData.getColumnClassName(3)).thenReturn("java.lang.String");

        final String result = JdbcCodeGenerationUtil.generateEntityClass(connection, "order_history");
        assertNotNull(result);
        assertTrue(result.contains("@Id") || result.contains("id"));
    }

    // classNamesToImport adds custom imports (L535)
    @Test
    public void testGenerateEntityClass_WithClassNamesToImport() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .classNamesToImport(Arrays.asList("com.example.MyAnnotation"))
                .build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("import com.example.MyAnnotation;"));
    }

    @Test
    public void testGenerateEntityClass_CustomImportUsesExactDuplicateMatching() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                // This is a prefix of the built-in ReadOnly import but is still a distinct requested import.
                .classNamesToImport(Arrays.asList("com.landawn.abacus.annotation.Read"))
                .build();

        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);

        assertTrue(result.contains("import com.landawn.abacus.annotation.Read;"));
    }

    // Jakarta annotation in headPart triggers extra LINE_SEPARATOR (L597)
    // and custom tableAnnotationClass = jakarta.persistence.Table covers L542 path
    @Test
    public void testGenerateEntityClass_WithJakartaAnnotations_AddsLineSeparator() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .tableAnnotationClass(jakarta.persistence.Table.class)
                .columnAnnotationClass(jakarta.persistence.Column.class)
                .idAnnotationClass(jakarta.persistence.Id.class)
                .build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("jakarta.persistence"));
    }

    @Test
    public void testGenerateEntityClass_UsesCompatibleCustomAnnotationClasses() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .tableAnnotationClass(CustomTable.class)
                .columnAnnotationClass(CustomColumn.class)
                .idAnnotationClass(CustomId.class)
                .idFields(List.of("id"))
                .build();

        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);

        assertTrue(result.contains("@" + CustomTable.class.getCanonicalName() + "(name = \"order_history\")"));
        assertTrue(result.contains("@" + CustomColumn.class.getCanonicalName() + "(name = \"id\")"));
        assertTrue(result.contains("@" + CustomId.class.getCanonicalName()));
        assertFalse(result.contains("import com.landawn.abacus.annotation.Table;"));
        assertFalse(result.contains("import com.landawn.abacus.annotation.Column;"));
        assertFalse(result.contains("import com.landawn.abacus.annotation.Id;"));
    }

    // nonUpdatableFields marks a field @NonUpdatable (L661)
    @Test
    public void testGenerateEntityClass_WithNonUpdatableField() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .nonUpdatableFields(Arrays.asList("status"))
                .build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("@NonUpdatable"));
    }

    // fieldTypeAnnotationArguments adds @Type annotation (L669)
    @Test
    public void testGenerateEntityClass_WithCustomizedFieldDbType() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .fieldTypeAnnotationArguments(Arrays.asList(Tuple.of("status", "VARCHAR(255)")))
                .build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
    }

    // generateCopyMethod=true and additionalClassBodySource generates copy() with extra fields (L715)
    @Test
    public void testGenerateEntityClass_WithCopyMethodAndAdditionalFields() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .generateCopyMethod(true)
                .additionalClassBodySource("private String extra; // extra")
                .build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("copy()"));
    }

    // generatePropNameTable=true generates X interface with property name constants (L722-751)
    @Test
    public void testGenerateEntityClass_WithFieldNameTable() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().generatePropNameTable(true).build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("public interface "));
    }

    // jsonXmlConfig with namingPolicy generates @JsonXmlConfig (L603-635)
    @Test
    public void testGenerateEntityClass_WithJsonXmlConfig() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig.JsonXmlConfig jsonXmlConfig = new JdbcCodeGenerationUtil.EntityCodeConfig.JsonXmlConfig();
        jsonXmlConfig.setNamingPolicy(NamingPolicy.CAMEL_CASE);
        jsonXmlConfig.setIgnoredFields("createdAt");
        jsonXmlConfig.setDateFormat("yyyy-MM-dd");
        jsonXmlConfig.setTimeZone("UTC");
        jsonXmlConfig.setNumberFormat("#.##");

        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().jsonXmlConfig(jsonXmlConfig).build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("@JsonXmlConfig"));
    }

    @Test
    public void testGenerateEntityClass_EscapesJsonXmlConfigStringLiterals() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig.JsonXmlConfig jsonXmlConfig = new JdbcCodeGenerationUtil.EntityCodeConfig.JsonXmlConfig();
        jsonXmlConfig.setIgnoredFields("raw\"field, path\\field, line\nfield");
        jsonXmlConfig.setDateFormat("yyyy\"MM");
        jsonXmlConfig.setTimeZone("UTC\\GMT");
        jsonXmlConfig.setNumberFormat("#\n##");

        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().jsonXmlConfig(jsonXmlConfig).build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);

        assertTrue(result.contains("ignoredFields = { \"raw\\\"field\", \"path\\\\field\", \"line\\nfield\" }"), result);
        assertTrue(result.contains("dateFormat = \"yyyy\\\"MM\""), result);
        assertTrue(result.contains("timeZone = \"UTC\\\\GMT\""), result);
        assertTrue(result.contains("numberFormat = \"#\\n##\""), result);
        assertFalse(result.contains("dateFormat = \"yyyy\"MM\""), result);
    }

    // generateEntityClass — excludedFields filters out a column (lines 475-476).
    @Test
    public void testGenerateEntityClass_ExcludedFieldsFiltersColumn() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .excludedFields(Arrays.asList("status"))
                .build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        // 'status' field should be filtered out
        assertTrue(!result.contains("private String status"));
    }

    // generateEntityClass — additionalFields with a parameterized java.util collection
    // exercises the import-resolution branch (lines 511-526).
    @Test
    public void testGenerateEntityClass_AdditionalFieldsImportsJavaUtilCollection() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .additionalClassBodySource("private List<String> tags;")
                .build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("import java.util.List;"));
    }

    // A fully qualified non-java.util generic type and annotation names never trigger a java.util import.
    @Test
    public void testGenerateEntityClass_AdditionalGenericFieldImportsIgnoreQualifiedAndAnnotationNames() {
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .className("OrderHistory")
                .additionalClassBodySource(String.join("\n", //
                        "    private com.acme.List<String> acmeList;", //
                        "    private @Nullable com.acme.Map<String, Integer> annotated;"))
                .build();
        final String code = JdbcCodeGenerationUtil.generateEntityClass("order_history", resultSet, config);

        assertFalse(code.contains("import java.util.List;"), code);
        assertFalse(code.contains("import java.util.Map;"), code);
        assertFalse(code.contains("import java.util.com;"), code);
    }

    // Class.forName also loads package-private java.util classes, so a generic type named like one of them (for example
    // a same-package TaskQueue<T>) got an inaccessible "import java.util.TaskQueue;" that does not compile.
    @Test
    public void testGenerateEntityClass_AdditionalGenericFieldDoesNotImportNonPublicJavaUtilClass() {
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .className("OrderHistory")
                .additionalClassBodySource(
                        "    private TaskQueue<String> tasks;\n    private KeyValueHolder<String, Integer> pair;\n    private List<String> tags;")
                .build();
        final String code = JdbcCodeGenerationUtil.generateEntityClass("order_history", resultSet, config);

        assertFalse(code.contains("import java.util.TaskQueue;"), code);
        assertFalse(code.contains("import java.util.KeyValueHolder;"), code);
        assertTrue(code.contains("import java.util.List;"), code);
    }

    // generateEntityClass — jsonXmlConfig with enumerated set (line 631-632).
    @Test
    public void testGenerateEntityClass_JsonXmlConfigWithEnumerated() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig.JsonXmlConfig jx = new JdbcCodeGenerationUtil.EntityCodeConfig.JsonXmlConfig();
        jx.setEnumerated(com.landawn.abacus.util.EnumType.ORDINAL);

        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().jsonXmlConfig(jx).build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("enumerated = "));
    }

    // generateBuilder=false — L593
    @Test
    public void testGenerateEntityClass_WithGenerateBuilderFalse() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().generateBuilder(false).build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertFalse(result.contains("import lombok.Builder;"));
        assertFalse(result.contains("\n@Builder\n"));
    }

    // generateChainAccessors=false — L597
    @Test
    public void testGenerateEntityClass_WithChainAccessorFalse() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().generateChainAccessors(false).build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertFalse(result.contains("import lombok.experimental.Accessors;"));
        assertFalse(result.contains("@Accessors(chain = true)"));
    }

    // customizedField._2 (field name override) — L478
    @Test
    public void testGenerateEntityClass_WithCustomizedFieldName() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .customFieldMappings(Arrays.asList(new FieldMapping("created_at", "createdAtOverride", null)))
                .build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("createdAtOverride"));
    }

    // customizedField._3 (field type override via customized class) — L484-L487
    @Test
    public void testGenerateEntityClass_WithCustomizedFieldType() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .customFieldMappings(Arrays.asList(new FieldMapping("status", null, String.class)))
                .build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("String status"));
    }

    // Only types declared in java.lang itself are implicitly imported: a java.lang subpackage type such as
    // java.lang.annotation.ElementType was shortened to the uncompilable "annotation.ElementType".
    @Test
    public void testGenerateEntityClass_CustomizedFieldTypeKeepsJavaLangSubpackageQualifier() {
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .customFieldMappings(
                        List.of(new FieldMapping("status", null, ElementType.class), new FieldMapping("created_at", null, Character.UnicodeScript.class)))
                .build();
        final String code = JdbcCodeGenerationUtil.generateEntityClass("order_history", resultSet, config);

        assertTrue(code.contains("    private java.lang.annotation.ElementType status;"), code);
        assertTrue(code.contains("    private Character.UnicodeScript createdAt;"), code);
    }

    // idFields non-null in config — L416
    @Test
    public void testGenerateEntityClass_WithIdFieldsList() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().idFields(Arrays.asList("id")).build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("@Id"));
    }

    // isJavaPersistenceId=true with idFields non-empty — L562-L565
    @Test
    public void testGenerateEntityClass_WithJakartaIdAndIdFields() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .idAnnotationClass(jakarta.persistence.Id.class)
                .idFields(Arrays.asList("id"))
                .build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("jakarta.persistence.Id"));
        assertFalse(result.contains("import com.landawn.abacus.annotation.Id;"));
    }

    // srcDir — writes generated file to disk — L793-L812
    @Test
    public void testGenerateEntityClass_WithSrcDir_WritesFile() throws Exception {
        setupFullGenerateEntityClassMock();
        final Path tempDir = Files.createTempDirectory("jdbcCodeGenTest");
        try {
            final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                    .srcDir(tempDir.toString())
                    .className("OrderHistory")
                    .packageName("com.test.entity")
                    .build();
            final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2",
                    config);
            assertNotNull(result);
            final File expectedFile = new File(tempDir.toFile(), "com/test/entity/OrderHistory.java");
            assertTrue(expectedFile.exists(), "Expected generated file at " + expectedFile.getAbsolutePath());
        } finally {
            deleteRecursively(tempDir.toFile());
        }
    }

    // javax.persistence annotations (non-jakarta) — L434, L436, L438
    @Test
    public void testGenerateEntityClass_WithJavaxPersistenceAnnotations() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .tableAnnotationClass(javax.persistence.Table.class)
                .columnAnnotationClass(javax.persistence.Column.class)
                .idAnnotationClass(javax.persistence.Id.class)
                .build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("javax.persistence"));
    }

    // mapBigIntegerToLong — L873-L874
    @Test
    public void testGenerateEntityClass_WithMapBigIntegerToLong() throws SQLException {
        final Statement stmt = Mockito.mock(Statement.class);
        final ResultSet pkRs = Mockito.mock(ResultSet.class);
        when(resultSet.getStatement()).thenReturn(stmt);
        when(stmt.getConnection()).thenReturn(connection);
        when(databaseMetaData.getPrimaryKeys(null, null, "order_history")).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(false);
        when(resultSetMetaData.getColumnName(1)).thenReturn("value");
        when(resultSetMetaData.getColumnType(1)).thenReturn(Types.BIGINT);
        when(resultSetMetaData.getColumnClassName(1)).thenReturn("java.math.BigInteger");
        when(resultSetMetaData.getColumnName(2)).thenReturn("created_at");
        when(resultSetMetaData.getColumnType(2)).thenReturn(Types.TIMESTAMP);
        when(resultSetMetaData.getColumnClassName(2)).thenReturn("java.sql.Timestamp");
        when(resultSetMetaData.getColumnName(3)).thenReturn("status");
        when(resultSetMetaData.getColumnType(3)).thenReturn(Types.VARCHAR);
        when(resultSetMetaData.getColumnClassName(3)).thenReturn("java.lang.String");

        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().mapBigIntegerToLong(true).build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("long value") || result.contains("long "));
    }

    // mapBigDecimalToDouble — L875-L876
    @Test
    public void testGenerateEntityClass_WithMapBigDecimalToDouble() throws SQLException {
        final Statement stmt = Mockito.mock(Statement.class);
        final ResultSet pkRs = Mockito.mock(ResultSet.class);
        when(resultSet.getStatement()).thenReturn(stmt);
        when(stmt.getConnection()).thenReturn(connection);
        when(databaseMetaData.getPrimaryKeys(null, null, "order_history")).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(false);
        when(resultSetMetaData.getColumnName(1)).thenReturn("amount");
        when(resultSetMetaData.getColumnType(1)).thenReturn(Types.DECIMAL);
        when(resultSetMetaData.getColumnClassName(1)).thenReturn("java.math.BigDecimal");
        when(resultSetMetaData.getColumnName(2)).thenReturn("created_at");
        when(resultSetMetaData.getColumnType(2)).thenReturn(Types.TIMESTAMP);
        when(resultSetMetaData.getColumnClassName(2)).thenReturn("java.sql.Timestamp");
        when(resultSetMetaData.getColumnName(3)).thenReturn("status");
        when(resultSetMetaData.getColumnType(3)).thenReturn(Types.VARCHAR);
        when(resultSetMetaData.getColumnClassName(3)).thenReturn("java.lang.String");

        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().mapBigDecimalToDouble(true).build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("double amount") || result.contains("double "));
    }

    // oracle.sql.TIMESTAMP — maps to java.sql.Timestamp — L848-L850
    @Test
    public void testGenerateEntityClass_WithOracleTimestampColumnName() throws SQLException {
        final Statement stmt = Mockito.mock(Statement.class);
        final ResultSet pkRs = Mockito.mock(ResultSet.class);
        when(resultSet.getStatement()).thenReturn(stmt);
        when(stmt.getConnection()).thenReturn(connection);
        when(databaseMetaData.getPrimaryKeys(null, null, "order_history")).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(false);
        when(resultSetMetaData.getColumnLabel(1)).thenReturn("ts");
        when(resultSetMetaData.getColumnType(1)).thenReturn(Types.TIMESTAMP);
        when(resultSetMetaData.getColumnClassName(1)).thenReturn("oracle.sql.TIMESTAMP");
        when(resultSetMetaData.getColumnName(2)).thenReturn("created_at");
        when(resultSetMetaData.getColumnType(2)).thenReturn(Types.TIMESTAMP);
        when(resultSetMetaData.getColumnClassName(2)).thenReturn("java.sql.Timestamp");
        when(resultSetMetaData.getColumnName(3)).thenReturn("status");
        when(resultSetMetaData.getColumnType(3)).thenReturn(Types.VARCHAR);
        when(resultSetMetaData.getColumnClassName(3)).thenReturn("java.lang.String");

        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", null);
        assertNotNull(result);
        assertTrue(result.contains("Timestamp ts"));
    }

    // oracle.sql.DATE — maps to java.sql.Date — L851-L852
    @Test
    public void testGenerateEntityClass_WithOracleDateColumnName() throws SQLException {
        final Statement stmt = Mockito.mock(Statement.class);
        final ResultSet pkRs = Mockito.mock(ResultSet.class);
        when(resultSet.getStatement()).thenReturn(stmt);
        when(stmt.getConnection()).thenReturn(connection);
        when(databaseMetaData.getPrimaryKeys(null, null, "order_history")).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(false);
        when(resultSetMetaData.getColumnLabel(1)).thenReturn("myDate");
        when(resultSetMetaData.getColumnType(1)).thenReturn(Types.DATE);
        when(resultSetMetaData.getColumnClassName(1)).thenReturn("oracle.sql.DATE");
        when(resultSetMetaData.getColumnName(2)).thenReturn("created_at");
        when(resultSetMetaData.getColumnType(2)).thenReturn(Types.TIMESTAMP);
        when(resultSetMetaData.getColumnClassName(2)).thenReturn("java.sql.Timestamp");
        when(resultSetMetaData.getColumnName(3)).thenReturn("status");
        when(resultSetMetaData.getColumnType(3)).thenReturn(Types.VARCHAR);
        when(resultSetMetaData.getColumnClassName(3)).thenReturn("java.lang.String");

        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", null);
        assertNotNull(result);
        assertTrue(result.contains("Date myDate"));
    }

    // oracle.sql.TIME — maps to java.sql.Time — L853-L854
    @Test
    public void testGenerateEntityClass_WithOracleTimeColumnName() throws SQLException {
        final Statement stmt = Mockito.mock(Statement.class);
        final ResultSet pkRs = Mockito.mock(ResultSet.class);
        when(resultSet.getStatement()).thenReturn(stmt);
        when(stmt.getConnection()).thenReturn(connection);
        when(databaseMetaData.getPrimaryKeys(null, null, "order_history")).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(false);
        when(resultSetMetaData.getColumnLabel(1)).thenReturn("myTime");
        when(resultSetMetaData.getColumnType(1)).thenReturn(Types.TIME);
        when(resultSetMetaData.getColumnClassName(1)).thenReturn("oracle.sql.TIME");
        when(resultSetMetaData.getColumnName(2)).thenReturn("created_at");
        when(resultSetMetaData.getColumnType(2)).thenReturn(Types.TIMESTAMP);
        when(resultSetMetaData.getColumnClassName(2)).thenReturn("java.sql.Timestamp");
        when(resultSetMetaData.getColumnName(3)).thenReturn("status");
        when(resultSetMetaData.getColumnType(3)).thenReturn(Types.VARCHAR);
        when(resultSetMetaData.getColumnClassName(3)).thenReturn("java.lang.String");

        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", null);
        assertNotNull(result);
        assertTrue(result.contains("Time myTime"));
    }

    // Exercise continue on line 481 when excludedFields match by column name (snake_case)
    @Test
    public void testGenerateEntityClass_ExcludedFieldsByColumnName() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().excludedFields(List.of("created_at")).build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertFalse(result.contains("createdAt"));
    }

    // Exercise catch block at line 524 — Class.forName throw on non-java.util type with generics
    @Test
    public void testGenerateEntityClass_AdditionalFieldsWithNonJavaUtilParameterizedType() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .additionalClassBodySource("private javax.sql.DataSource<String> ds;")
                .build();
        assertDoesNotThrow(
                () -> JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config));
    }

    // BUG FIX: entity generation used to prefer the physical column name over the SQL alias (result-set
    // label), so generateEntityClassByQuery produced fields that did not match the labels the query
    // actually returns. The label (alias-or-name per JDBC) must win; every other generation path in
    // this class already keys on column labels.
    @Test
    @Tag("2025")
    public void testGenerateEntityClass_PrefersSqlAliasLabelOverPhysicalColumnName() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final ResultSetMetaData metadata = Mockito.mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnName(1)).thenReturn("user_id");
        when(metadata.getColumnLabel(1)).thenReturn("uid");
        when(metadata.getColumnClassName(1)).thenReturn(Long.class.getName());
        when(metadata.getColumnType(1)).thenReturn(Types.BIGINT);

        final String code = JdbcCodeGenerationUtil.generateEntityClass("UserQueryResult", rs, null);

        assertTrue(code.contains(" uid;"), () -> "generated field should be named after the SQL alias 'uid':\n" + code);
        assertFalse(code.contains("userId"), () -> "physical column name must not override the alias:\n" + code);
    }

    // BUG FIX: two additional fields sharing the same generic java.util collection type used to emit
    // the identical "import java.util.List;" line twice (harmless to javac per JLS 7.5.1, but wrong
    // output). The auto-import must be emitted once per type.
    @Test
    @Tag("2025")
    public void testGenerateEntityClass_DuplicateJavaUtilAutoImportEmittedOnce() throws SQLException {
        setupFullGenerateEntityClassMock();
        JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .className("OrderHistory")
                .additionalClassBodySource("private List<String> tags;\nprivate List<Integer> scores;")
                .build();

        String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);

        final int first = result.indexOf("import java.util.List;");
        assertTrue(first >= 0, () -> "expected a java.util.List import:\n" + result);
        assertEquals(-1, result.indexOf("import java.util.List;", first + 1), () -> "duplicate java.util.List import:\n" + result);
    }

    // Regression: when an @Id field is identified by raw snake_case column name (e.g. from
    // DatabaseMetaData.getPrimaryKeys returning COLUMN_NAME='order_id'), the import for @Id must
    // be preserved because the field-emission pass matches against both fieldName and columnName.
    @Test
    public void testGenerateEntityClass_IdImportPreservedWhenIdFieldUsesColumnName() throws SQLException {
        final Connection conn = Mockito.mock(Connection.class);
        final DatabaseMetaData metaData = Mockito.mock(DatabaseMetaData.class);
        final PreparedStatement stmt = Mockito.mock(PreparedStatement.class);
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final ResultSetMetaData rsMetaData = Mockito.mock(ResultSetMetaData.class);
        final Statement jdbcStmt = Mockito.mock(Statement.class);
        final ResultSet pkRs = Mockito.mock(ResultSet.class);

        when(conn.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("MySQL");
        when(metaData.getIdentifierQuoteString()).thenReturn("`");
        when(metaData.getDatabaseProductVersion()).thenReturn("8.0");
        when(conn.prepareStatement("SELECT * FROM orders WHERE 1 > 2")).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(rsMetaData);
        when(rs.getStatement()).thenReturn(jdbcStmt);
        when(jdbcStmt.getConnection()).thenReturn(conn);
        when(metaData.getPrimaryKeys(null, null, "orders")).thenReturn(pkRs);
        // Primary key auto-detect returns the raw snake_case column name.
        when(pkRs.next()).thenReturn(true, false);
        when(pkRs.getString("COLUMN_NAME")).thenReturn("order_id");
        when(rsMetaData.getColumnCount()).thenReturn(2);
        when(rsMetaData.getColumnName(1)).thenReturn("order_id");
        when(rsMetaData.getColumnName(2)).thenReturn("status");
        when(rsMetaData.getColumnLabel(1)).thenReturn("order_id");
        when(rsMetaData.getColumnLabel(2)).thenReturn("status");
        when(rsMetaData.getColumnType(1)).thenReturn(Types.BIGINT);
        when(rsMetaData.getColumnType(2)).thenReturn(Types.VARCHAR);
        when(rsMetaData.getColumnClassName(1)).thenReturn("java.lang.Long");
        when(rsMetaData.getColumnClassName(2)).thenReturn("java.lang.String");

        final String result = JdbcCodeGenerationUtil.generateEntityClass(conn, "orders");

        assertNotNull(result);
        // @Id annotation must be emitted on the id field.
        assertTrue(result.contains("@Id"), "Expected @Id annotation in: " + result);
        // The matching import statement must also be present so the generated source compiles.
        assertTrue(result.contains("import com.landawn.abacus.annotation.Id;"), "Expected '@Id' import in generated class: " + result);
    }

    @Test
    public void testGenerateEntityClass_EscapesColumnNameWithEmbeddedQuote() throws SQLException {
        final Connection conn = Mockito.mock(Connection.class);
        final PreparedStatement stmt = Mockito.mock(PreparedStatement.class);
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final ResultSetMetaData rsMd = Mockito.mock(ResultSetMetaData.class);
        final DatabaseMetaData md = Mockito.mock(DatabaseMetaData.class);
        final Statement jdbcStmt = Mockito.mock(Statement.class);
        final ResultSet pkRs = Mockito.mock(ResultSet.class);

        when(conn.getMetaData()).thenReturn(md);
        when(md.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(md.getIdentifierQuoteString()).thenReturn("\"");
        when(md.getDatabaseProductVersion()).thenReturn("15");
        when(conn.prepareStatement(ArgumentMatchers.anyString())).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(rsMd);
        when(rs.getStatement()).thenReturn(jdbcStmt);
        when(jdbcStmt.getConnection()).thenReturn(conn);
        when(md.getPrimaryKeys(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyString())).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(false);
        when(rsMd.getColumnCount()).thenReturn(1);
        // Column name with an embedded double-quote — pathological but legal in PostgreSQL/Oracle.
        when(rsMd.getColumnLabel(1)).thenReturn("my\"col");
        when(rsMd.getColumnName(1)).thenReturn("my\"col");
        when(rsMd.getColumnType(1)).thenReturn(Types.VARCHAR);
        when(rsMd.getColumnClassName(1)).thenReturn("java.lang.String");

        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .fieldNameConverter((table, column) -> "myCol")
                .build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(conn, "weird_tbl", "SELECT * FROM weird_tbl WHERE 1 > 2", config);
        assertNotNull(result);
        // The raw column-name quote must be escaped; pre-fix the annotation broke compilation.
        assertTrue(result.contains("@Column(name = \"my\\\"col\")"), "Expected escaped @Column annotation, got:\n" + result);
        // The unescaped form (broken Java) must NOT appear.
        assertFalse(result.contains("@Column(name = \"my\"col\")"), "Unescaped column name leaked into generated source");
    }

    // Regression: generated .java files used to be written with IOUtil's platform-default charset
    // (Cp1252 on Windows). Non-ASCII identifiers / comments became mojibake when the file was
    // later compiled by javac with -encoding UTF-8 (Maven/Gradle default). Fix forces UTF-8.
    @Test
    public void testGenerateEntityClass_WritesGeneratedFileAsUtf8() throws Exception {
        // Non-ASCII characters in column name to force a non-ASCII byte in the generated source.
        final Connection conn = Mockito.mock(Connection.class);
        final PreparedStatement stmt = Mockito.mock(PreparedStatement.class);
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final ResultSetMetaData rsMd = Mockito.mock(ResultSetMetaData.class);
        final DatabaseMetaData md = Mockito.mock(DatabaseMetaData.class);
        final Statement jdbcStmt = Mockito.mock(Statement.class);
        final ResultSet pkRs = Mockito.mock(ResultSet.class);

        when(conn.getMetaData()).thenReturn(md);
        when(md.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(md.getIdentifierQuoteString()).thenReturn("\"");
        when(md.getDatabaseProductVersion()).thenReturn("15");
        when(conn.prepareStatement(ArgumentMatchers.anyString())).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(rsMd);
        when(rs.getStatement()).thenReturn(jdbcStmt);
        when(jdbcStmt.getConnection()).thenReturn(conn);
        when(md.getPrimaryKeys(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyString())).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(false);
        when(rsMd.getColumnCount()).thenReturn(1);
        // German umlaut and Japanese characters: 2 bytes in UTF-8, single byte in Cp1252-or-broken.
        final String exoticColumn = "größe漢字";
        when(rsMd.getColumnLabel(1)).thenReturn(exoticColumn);
        when(rsMd.getColumnName(1)).thenReturn(exoticColumn);
        when(rsMd.getColumnType(1)).thenReturn(Types.VARCHAR);
        when(rsMd.getColumnClassName(1)).thenReturn("java.lang.String");

        final Path tempDir = Files.createTempDirectory("jdbcCodeGenCharset");
        try {
            final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                    .srcDir(tempDir.toString())
                    .className("Exotic")
                    .packageName("p")
                    .build();
            JdbcCodeGenerationUtil.generateEntityClassByQuery(conn, "weird_tbl", "SELECT * FROM weird_tbl WHERE 1 > 2", config);

            final File expectedFile = new File(tempDir.toFile(), "p/Exotic.java");
            assertTrue(expectedFile.exists());
            // Read the file as UTF-8 bytes and confirm the non-ASCII column name round-trips
            // exactly. Pre-fix, Cp1252 encoding would have replaced 漢字 with `??`.
            final String onDisk = new String(Files.readAllBytes(expectedFile.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(onDisk.contains(exoticColumn), "Expected UTF-8 round-trip of exotic column name; on-disk content:\n" + onDisk);
        } finally {
            deleteRecursively(tempDir.toFile());
        }
    }

    // Regression: PK auto-detection used to pass `entityName` verbatim to getPrimaryKeys, so a
    // qualified name like "myschema.users" was queried as a literal table named "myschema.users"
    // (drivers never match); @Id annotations were silently missing. Fix splits the qualified
    // identifier into catalog/schema/table.
    @Test
    public void testGenerateEntityClass_QualifiedTableName_SplitsCatalogSchemaTableForPkLookup() throws SQLException {
        final Connection conn = Mockito.mock(Connection.class);
        final PreparedStatement stmt = Mockito.mock(PreparedStatement.class);
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final ResultSetMetaData rsMd = Mockito.mock(ResultSetMetaData.class);
        final DatabaseMetaData md = Mockito.mock(DatabaseMetaData.class);
        final Statement jdbcStmt = Mockito.mock(Statement.class);
        final ResultSet pkRs = Mockito.mock(ResultSet.class);

        when(conn.getMetaData()).thenReturn(md);
        when(md.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(md.getIdentifierQuoteString()).thenReturn("\"");
        when(md.getDatabaseProductVersion()).thenReturn("15");
        when(conn.prepareStatement(ArgumentMatchers.anyString())).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(rsMd);
        when(rs.getStatement()).thenReturn(jdbcStmt);
        when(jdbcStmt.getConnection()).thenReturn(conn);
        // Fix routes the call to (null, "myschema", "users") rather than (null, null, "myschema.users").
        when(md.getPrimaryKeys(null, "myschema", "users")).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(true, false);
        when(pkRs.getString("COLUMN_NAME")).thenReturn("id");

        when(rsMd.getColumnCount()).thenReturn(2);
        when(rsMd.getColumnLabel(1)).thenReturn("id");
        when(rsMd.getColumnName(1)).thenReturn("id");
        when(rsMd.getColumnType(1)).thenReturn(Types.BIGINT);
        when(rsMd.getColumnClassName(1)).thenReturn("java.lang.Long");
        when(rsMd.getColumnLabel(2)).thenReturn("name");
        when(rsMd.getColumnName(2)).thenReturn("name");
        when(rsMd.getColumnType(2)).thenReturn(Types.VARCHAR);
        when(rsMd.getColumnClassName(2)).thenReturn("java.lang.String");

        final String result = JdbcCodeGenerationUtil.generateEntityClass(conn, "myschema.users");
        assertNotNull(result);
        assertTrue(result.contains("public class Users"), "Expected class name to be derived from the unqualified table name; got:\n" + result);
        // The PK column "id" should have produced an @Id annotation, proving the schema-qualified
        // metadata lookup succeeded. Pre-fix, getPrimaryKeys(null, null, "myschema.users") matched
        // nothing and no @Id was emitted.
        assertTrue(result.contains("@Id"), "Expected @Id annotation from schema-qualified PK lookup; got:\n" + result);
        // Verify the call signature exactly.
        Mockito.verify(md).getPrimaryKeys(null, "myschema", "users");
    }

    // BUG FIX: on a catalog-only database (MySQL/MariaDB with the default CATALOG database term) "mydb.users" is
    // database.table and getPrimaryKeys ignores its schema argument. The qualifier used to go into the schema slot, so
    // the lookup searched the connection's current database instead and the @Id was missing (or taken from another
    // table of the same name).
    @Test
    public void testGenerateEntityClass_TwoPartTableNameUsesCatalogSlotOnCatalogOnlyDatabase() throws SQLException {
        final Connection conn = Mockito.mock(Connection.class);
        final PreparedStatement stmt = Mockito.mock(PreparedStatement.class);
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final ResultSetMetaData rsMd = Mockito.mock(ResultSetMetaData.class);
        final DatabaseMetaData md = Mockito.mock(DatabaseMetaData.class);
        final Statement jdbcStmt = Mockito.mock(Statement.class);
        final ResultSet pkRs = Mockito.mock(ResultSet.class);
        final ResultSet noPrimaryKeys = Mockito.mock(ResultSet.class);

        when(conn.getMetaData()).thenReturn(md);
        when(conn.getCatalog()).thenReturn("currentdb");
        when(md.getDatabaseProductName()).thenReturn("MySQL");
        when(md.getIdentifierQuoteString()).thenReturn("`");
        when(md.getDatabaseProductVersion()).thenReturn("8.0");
        when(md.supportsSchemasInTableDefinitions()).thenReturn(false);
        when(md.supportsCatalogsInTableDefinitions()).thenReturn(true);
        when(conn.prepareStatement(ArgumentMatchers.anyString())).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(rsMd);
        when(rs.getStatement()).thenReturn(jdbcStmt);
        when(jdbcStmt.getConnection()).thenReturn(conn);
        when(md.getPrimaryKeys(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyString())).thenReturn(noPrimaryKeys);
        when(md.getPrimaryKeys("mydb", null, "users")).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(true, false);
        when(pkRs.getString("COLUMN_NAME")).thenReturn("id");

        when(rsMd.getColumnCount()).thenReturn(2);
        when(rsMd.getColumnLabel(1)).thenReturn("id");
        when(rsMd.getColumnClassName(1)).thenReturn("java.lang.Long");
        when(rsMd.getColumnLabel(2)).thenReturn("name");
        when(rsMd.getColumnClassName(2)).thenReturn("java.lang.String");

        final String result = JdbcCodeGenerationUtil.generateEntityClass(conn, "mydb.users");

        assertTrue(result.contains("@Id"), () -> "Expected @Id from the database-qualified PK lookup; got:\n" + result);
        Mockito.verify(md).getPrimaryKeys("mydb", null, "users");
    }

    // Guard: a database that supports schemas in table definitions (MySQL with databaseTerm=SCHEMA, or SQL Server/H2
    // which support both) keeps the two-part qualifier in the schema slot.
    @Test
    public void testGenerateEntityClass_TwoPartTableNameUsesSchemaSlotWhenSchemasSupported() throws SQLException {
        for (final boolean catalogsSupported : new boolean[] { false, true }) {
            final Connection conn = Mockito.mock(Connection.class);
            final PreparedStatement stmt = Mockito.mock(PreparedStatement.class);
            final ResultSet rs = Mockito.mock(ResultSet.class);
            final ResultSetMetaData rsMd = Mockito.mock(ResultSetMetaData.class);
            final DatabaseMetaData md = Mockito.mock(DatabaseMetaData.class);
            final Statement jdbcStmt = Mockito.mock(Statement.class);
            final ResultSet pkRs = Mockito.mock(ResultSet.class);
            final ResultSet noPrimaryKeys = Mockito.mock(ResultSet.class);

            when(conn.getMetaData()).thenReturn(md);
            when(conn.getCatalog()).thenReturn("currentdb");
            when(md.getDatabaseProductName()).thenReturn("MySQL");
            when(md.getIdentifierQuoteString()).thenReturn("`");
            when(md.getDatabaseProductVersion()).thenReturn("8.0");
            when(md.supportsSchemasInTableDefinitions()).thenReturn(true);
            when(md.supportsCatalogsInTableDefinitions()).thenReturn(catalogsSupported);
            when(conn.prepareStatement(ArgumentMatchers.anyString())).thenReturn(stmt);
            when(stmt.executeQuery()).thenReturn(rs);
            when(rs.getMetaData()).thenReturn(rsMd);
            when(rs.getStatement()).thenReturn(jdbcStmt);
            when(jdbcStmt.getConnection()).thenReturn(conn);
            when(md.getPrimaryKeys(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyString())).thenReturn(noPrimaryKeys);
            when(md.getPrimaryKeys("currentdb", "mydb", "users")).thenReturn(pkRs);
            when(pkRs.next()).thenReturn(true, false);
            when(pkRs.getString("COLUMN_NAME")).thenReturn("id");

            when(rsMd.getColumnCount()).thenReturn(2);
            when(rsMd.getColumnLabel(1)).thenReturn("id");
            when(rsMd.getColumnClassName(1)).thenReturn("java.lang.Long");
            when(rsMd.getColumnLabel(2)).thenReturn("name");
            when(rsMd.getColumnClassName(2)).thenReturn("java.lang.String");

            final String result = JdbcCodeGenerationUtil.generateEntityClass(conn, "mydb.users");

            assertTrue(result.contains("@Id"), () -> "catalogsSupported=" + catalogsSupported + ", got:\n" + result);
            Mockito.verify(md).getPrimaryKeys("currentdb", "mydb", "users");
        }
    }

    // A three-part qualified name (catalog.schema.table) routes all three slots to getPrimaryKeys
    // (JdbcCodeGenerationUtil L529-533, the parts.length >= 3 branch). The existing fixture only
    // covered the two-part schema.table case.
    @Test
    public void testGenerateEntityClass_QualifiedTableName_CatalogSchemaTable() throws SQLException {
        final Connection conn = Mockito.mock(Connection.class);
        final PreparedStatement stmt = Mockito.mock(PreparedStatement.class);
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final ResultSetMetaData rsMd = Mockito.mock(ResultSetMetaData.class);
        final DatabaseMetaData md = Mockito.mock(DatabaseMetaData.class);
        final Statement jdbcStmt = Mockito.mock(Statement.class);
        final ResultSet pkRs = Mockito.mock(ResultSet.class);

        when(conn.getMetaData()).thenReturn(md);
        when(md.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(md.getIdentifierQuoteString()).thenReturn("\"");
        when(md.getDatabaseProductVersion()).thenReturn("15");
        when(conn.prepareStatement(ArgumentMatchers.anyString())).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(rsMd);
        when(rs.getStatement()).thenReturn(jdbcStmt);
        when(jdbcStmt.getConnection()).thenReturn(conn);
        // catalog.schema.table -> getPrimaryKeys("mycatalog", "myschema", "users").
        when(md.getPrimaryKeys("mycatalog", "myschema", "users")).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(true, false);
        when(pkRs.getString("COLUMN_NAME")).thenReturn("id");

        when(rsMd.getColumnCount()).thenReturn(2);
        when(rsMd.getColumnLabel(1)).thenReturn("id");
        when(rsMd.getColumnName(1)).thenReturn("id");
        when(rsMd.getColumnType(1)).thenReturn(Types.BIGINT);
        when(rsMd.getColumnClassName(1)).thenReturn("java.lang.Long");
        when(rsMd.getColumnLabel(2)).thenReturn("name");
        when(rsMd.getColumnName(2)).thenReturn("name");
        when(rsMd.getColumnType(2)).thenReturn(Types.VARCHAR);
        when(rsMd.getColumnClassName(2)).thenReturn("java.lang.String");

        final String result = JdbcCodeGenerationUtil.generateEntityClass(conn, "mycatalog.myschema.users");
        assertNotNull(result);
        assertTrue(result.contains("public class Users"), "Expected class name to be derived from the unqualified table name; got:\n" + result);
        assertTrue(result.contains("@Id"), "Expected @Id annotation from catalog/schema-qualified PK lookup; got:\n" + result);
        Mockito.verify(md).getPrimaryKeys("mycatalog", "myschema", "users");
    }

    // ----------------------------------------------------------------------------------------------------
    // Additional coverage for previously-uncovered lines in JdbcCodeGenerationUtil.
    // ----------------------------------------------------------------------------------------------------

    // additionalClassBodySource with a multi-variable declaration ("int a, b") must include every
    // declared instance field in the generated copy method.
    @Test
    public void testGenerateEntityClass_AdditionalFieldMultiVariableDeclaration() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .generateCopyMethod(true)
                .className("OrderHistory")
                .additionalClassBodySource("    private int a, b;")
                .build();

        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);

        assertTrue(result.contains("private int a, b;"), result);
        assertTrue(result.contains("copy.a = this.a;"), result);
        assertTrue(result.contains("copy.b = this.b;"), result);
    }

    @Test
    public void testGenerateEntityClass_InitializedAdditionalFieldMultiVariableDeclaration() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .generateCopyMethod(true)
                .className("OrderHistory")
                .additionalClassBodySource("    private int low = Math.min(1, 2), high = Math.max(3, 4);")
                .build();

        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);

        assertTrue(result.contains("private int low = Math.min(1, 2), high = Math.max(3, 4);"), result);
        assertTrue(result.contains("copy.low = this.low;"), result);
        assertTrue(result.contains("copy.high = this.high;"), result);
    }

    @Test
    public void testGenerateEntityClass_AdditionalFieldInitializerContainingSemicolon() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .generateCopyMethod(true)
                .className("OrderHistory")
                .additionalClassBodySource("    private String first = \"a;b\", second = \"c\";")
                .build();

        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);

        assertTrue(result.contains("copy.first = this.first;"), result);
        assertTrue(result.contains("copy.second = this.second;"), result);
    }

    @Test
    public void testGenerateEntityClass_MultipleAdditionalFieldDeclarationsOnOneLine() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .generateCopyMethod(true)
                .className("OrderHistory")
                .additionalClassBodySource("    private int first; private int second;")
                .build();

        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);

        assertTrue(result.contains("copy.first = this.first;"), result);
        assertTrue(result.contains("copy.second = this.second;"), result);
    }

    @Test
    public void testGenerateEntityClass_RelationalInitializerDoesNotHideFollowingField() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .generateCopyMethod(true)
                .className("OrderHistory")
                .additionalClassBodySource("    private boolean first = Integer.MIN_VALUE < 2, second = true;")
                .build();

        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);

        assertTrue(result.contains("copy.first = this.first;"), result);
        assertTrue(result.contains("copy.second = this.second;"), result);
    }

    @Test
    public void testGenerateEntityClass_GenericMethodReferencesRemainSingleDeclarators() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .generateCopyMethod(true)
                .className("OrderHistory")
                .additionalClassBodySource(
                        "    private java.util.function.Supplier<java.util.Map<String, Integer>> factory = java.util.HashMap<String, Integer>::new, other = java.util.HashMap<String, Integer>::new;")
                .build();

        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);

        assertTrue(result.contains("copy.factory = this.factory;"), result);
        assertTrue(result.contains("copy.other = this.other;"), result);
    }

    // A one-line type or method declaration followed by a stray ';' (an empty member) is valid source, but it was parsed
    // as a field declaration and rejected with "Cannot parse field declaration".
    @Test
    public void testGenerateEntityClass_AdditionalTypeOrMethodDeclarationWithStraySemicolonIsAccepted() {
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .className("OrderHistory")
                .generateCopyMethod(true)
                .additionalClassBodySource("    public enum Kind { ONLINE, STORE }; private Kind kind;\n    public String label() { return \"x\"; };")
                .build();

        final String code = assertDoesNotThrow(() -> JdbcCodeGenerationUtil.generateEntityClass("order_history", resultSet, config));

        assertTrue(code.contains("copy.kind = this.kind;"), code);
        assertFalse(code.contains("copy.label"), code);
    }

    // entityName that is not a parseable SQL identifier (4-part "a.b.c.d") makes
    // JdbcUtil.splitQualifiedSqlIdentifier throw; the PK-lookup falls back to best-effort
    // (L566: catch (RuntimeException ignore)).
    @Test
    public void testGenerateEntityClass_UnparseableEntityName_FallsBackToBestEffort() throws SQLException {
        setupFullGenerateEntityClassMock();
        final ResultSet pkRs = Mockito.mock(ResultSet.class);
        when(databaseMetaData.getPrimaryKeys(null, null, "a.b.c.d")).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(false);

        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().className("MyEntity").build();

        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "a.b.c.d", "SELECT * FROM order_history WHERE 1 > 2", config);

        assertNotNull(result);
        assertTrue(result.contains("MyEntity"), result);
        // PK lookup used the verbatim entityName as the table after the parse failure fell through.
        Mockito.verify(databaseMetaData).getPrimaryKeys(null, null, "a.b.c.d");
    }

    @Test
    public void testMalformedTableNamesAreRejectedBeforeDatabaseAccess() {
        final DataSource unusedSource = Mockito.mock(DataSource.class);
        final Connection unusedConnection = Mockito.mock(Connection.class);
        for (final String tableName : List.of("schema..users", "\"users")) {
            assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateEntityClass(unusedSource, tableName));
            assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateSelectSql(unusedSource, tableName));
            assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateInsertSql(unusedSource, tableName));
            assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateNamedInsertSql(unusedSource, tableName));
            assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateUpdateSql(unusedConnection, tableName, "id"));
            assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(unusedConnection, tableName, "id"));
        }
        Mockito.verifyNoInteractions(unusedSource, unusedConnection);
    }

    // The DataSource overloads only checked the table-name syntax before ds.getConnection(); a well-formed name that the
    // disabled-quoting policy rejects (explicitly delimited, reserved or non-simple) was rejected only after a connection
    // had been opened, so an unreachable database reported a connection failure instead of the invalid argument.
    @Test
    public void testUnrenderableTableNamesAreRejectedBeforeDataSourceConnection() {
        final DataSource unusedSource = Mockito.mock(DataSource.class);
        for (final String tableName : List.of("\"users\"", "order", "order-history", "app.\"Users\"")) {
            assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateEntityClass(unusedSource, tableName), tableName);
            assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateEntityClass(unusedSource, tableName, null), tableName);
            assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateSelectSql(unusedSource, tableName), tableName);
            assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateSelectSql(unusedSource, tableName, null, null), tableName);
            assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateInsertSql(unusedSource, tableName), tableName);
            assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateInsertSql(unusedSource, tableName, null), tableName);
            assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateNamedInsertSql(unusedSource, tableName), tableName);
            assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateNamedInsertSql(unusedSource, tableName, null), tableName);
            assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateUpdateSql(unusedSource, tableName), tableName);
            assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateUpdateSql(unusedSource, tableName, "id"), tableName);
            assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateUpdateSql(unusedSource, tableName, null, List.of("id"), null),
                    tableName);
            assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(unusedSource, tableName), tableName);
            assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(unusedSource, tableName, "id"), tableName);
            assertThrows(IllegalArgumentException.class,
                    () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(unusedSource, tableName, null, List.of("id"), null), tableName);
        }
        Mockito.verifyNoInteractions(unusedSource);
    }

    // The Connection overloads only checked the table-name syntax before validating the other arguments, so with both a
    // table name the disabled-quoting policy rejects and another invalid argument they reported the other argument, while
    // the DataSource overloads reported the table name.
    @Test
    public void testConnectionAndDataSourceOverloadsReportUnrenderableTableNameBeforeOtherInvalidArguments() {
        final DataSource unusedSource = Mockito.mock(DataSource.class);
        final Connection unusedConnection = Mockito.mock(Connection.class);
        final JdbcCodeGenerationUtil.EntityCodeConfig invalidConfig = JdbcCodeGenerationUtil.EntityCodeConfig.builder().className("1Invalid").build();
        final List<String> blankKeys = List.of(" ");

        for (final String tableName : List.of("order", "order-history", "\"users\"")) {
            final String tableNameMessage = assertThrows(IllegalArgumentException.class,
                    () -> JdbcCodeGenerationUtil.generateEntityClass(unusedSource, tableName, invalidConfig)).getMessage();
            assertFalse(tableNameMessage.contains("1Invalid"), tableNameMessage);

            assertEquals(tableNameMessage,
                    assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateEntityClass(unusedConnection, tableName, invalidConfig))
                            .getMessage());
            assertEquals(tableNameMessage,
                    assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateUpdateSql(unusedSource, tableName, " ")).getMessage());
            assertEquals(tableNameMessage,
                    assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateUpdateSql(unusedConnection, tableName, " "))
                            .getMessage());
            assertEquals(tableNameMessage, assertThrows(IllegalArgumentException.class,
                    () -> JdbcCodeGenerationUtil.generateUpdateSql(unusedConnection, tableName, null, blankKeys, null)).getMessage());
            assertEquals(tableNameMessage,
                    assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(unusedConnection, tableName, " "))
                            .getMessage());
            assertEquals(tableNameMessage, assertThrows(IllegalArgumentException.class,
                    () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(unusedConnection, tableName, null, blankKeys, null)).getMessage());
        }

        // A renderable table name still lets the other invalid argument be reported.
        assertTrue(assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateEntityClass(unusedConnection, "users", invalidConfig))
                .getMessage()
                .contains("1Invalid"));
        Mockito.verifyNoInteractions(unusedSource, unusedConnection);
    }

    @Test
    public void testInvalidTableIsRejectedBeforeConnectionMetadata() {
        final Connection unusedConnection = Mockito.mock(Connection.class);
        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateEntityClass(unusedConnection, " "));
        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateSelectSql(unusedConnection, " "));
        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateInsertSql(unusedConnection, " "));
        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateNamedInsertSql(unusedConnection, " "));
        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateUpdateSql(unusedConnection, " "));
        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(unusedConnection, " "));
        Mockito.verifyNoInteractions(unusedConnection);
    }

    @Test
    public void testNullDataSourceOrConnectionIsRejectedBeforeAnyDatabaseAccess() {
        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateEntityClass((DataSource) null, "users"));
        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateEntityClass((Connection) null, "users"));
        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateEntityClassByQuery((DataSource) null, "users", "SELECT 1"));
        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateEntityClassByQuery((Connection) null, "users", "SELECT 1"));
        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateSelectSql((DataSource) null, "users"));
        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateSelectSql((Connection) null, "users"));
        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateInsertSql((DataSource) null, "users"));
        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateInsertSql((Connection) null, "users"));
        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateNamedInsertSql((DataSource) null, "users"));
        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateNamedInsertSql((Connection) null, "users"));
        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateUpdateSql((DataSource) null, "users"));
        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateUpdateSql((Connection) null, "users"));
        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateNamedUpdateSql((DataSource) null, "users"));
        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateNamedUpdateSql((Connection) null, "users"));
    }

    @Test
    public void testGenerateEntityClass_RejectsInvalidGeneratedFieldName() throws SQLException {
        setupFullGenerateEntityClassMock();
        when(resultSetMetaData.getColumnLabel(1)).thenReturn("class");

        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", null));

        assertTrue(ex.getMessage().contains("field name 'class'"), ex.getMessage());
    }

    @Test
    public void testGenerateEntityClass_RejectsInvalidConfiguredClassName() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().className("123Entity").build();

        assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config));
    }

    // var/yield/record/sealed/permits are valid identifiers but not type identifiers: "public class record {"
    // does not compile, so the class name must be rejected like any other invalid name.
    @Test
    public void testGenerateEntityClass_RejectsRestrictedIdentifierAsClassName() throws SQLException {
        setupFullGenerateEntityClassMock();

        for (final String className : List.of("record", "var", "yield", "sealed", "permits")) {
            final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().className(className).build();

            assertThrows(IllegalArgumentException.class,
                    () -> JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config),
                    className);
        }
    }

    // A top-level class can't share its simple name with a single-type import (JLS 7.5.1): a table named "data"
    // derives class "Data", which clashes with the always-emitted "import lombok.Data;".
    @Test
    public void testGenerateEntityClass_RejectsClassNameCollidingWithImport() throws SQLException {
        setupFullGenerateEntityClassMock();
        final ResultSet pkRs = Mockito.mock(ResultSet.class);
        when(databaseMetaData.getPrimaryKeys(null, null, "data")).thenReturn(pkRs);

        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "data", "SELECT * FROM order_history WHERE 1 > 2", null));
        assertTrue(ex.getMessage().contains("lombok.Data"), ex.getMessage());

        // Also the always-used column annotation, and the nested property-name interface (JLS 8.1).
        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history",
                "SELECT * FROM order_history WHERE 1 > 2", JdbcCodeGenerationUtil.EntityCodeConfig.builder().className("Column").build()));
        assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2",
                        JdbcCodeGenerationUtil.EntityCodeConfig.builder().className(JdbcCodeGenerationUtil.X).generatePropNameTable(true).build()));

        // An import pruned as unused doesn't collide: no @Id field is generated here, so class "Id" is fine.
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2",
                JdbcCodeGenerationUtil.EntityCodeConfig.builder().className("Id").build());
        assertTrue(result.contains("public class Id {"), result);
    }

    // A java.lang field type is emitted by its simple name, so a class of the same name (derived from a table named "object"
    // or "string") shadowed it: the fields silently took the entity type, and the property-name constants did not compile.
    @Test
    public void testGenerateEntityClass_RejectsClassNameShadowingReferencedJavaLangType() {
        // The default mock reports no column class names, so every generated field is an Object.
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateEntityClass("object", resultSet, null));
        assertTrue(ex.getMessage().contains("java.lang.Object"), ex.getMessage());

        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateEntityClass("string", resultSet,
                JdbcCodeGenerationUtil.EntityCodeConfig.builder().generatePropNameTable(true).build()));
        assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateEntityClass("order_history", resultSet,
                        JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                                .className("Integer")
                                .customFieldMappings(List.of(new FieldMapping("id", null, Integer.class)))
                                .build()));

        // A java.lang name the generated source doesn't refer to by its simple name is not shadowed.
        assertTrue(
                JdbcCodeGenerationUtil
                        .generateEntityClass("order_history", resultSet,
                                JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                                        .className("Integer")
                                        .customFieldMappings(List.of(new FieldMapping("id", null, java.math.BigInteger.class)))
                                        .build())
                        .contains("public class Integer {"));

        // A type argument refers to the name as well; a qualified name doesn't (this last case passed before the fix too).
        assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateEntityClass("order_history", resultSet,
                        JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                                .className("String")
                                .fieldTypeConverter((entity, field, column, type) -> "java.util.List<String>")
                                .build()));
        final String qualified = JdbcCodeGenerationUtil.generateEntityClass("order_history", resultSet,
                JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                        .className("String")
                        .fieldTypeConverter((entity, field, column, type) -> "java.util.List<java.lang.String>")
                        .build());
        assertTrue(qualified.contains("    private java.util.List<java.lang.String> status;"), qualified);
    }

    @Test
    public void testGenerateEntityClass_RejectsInvalidConfiguredPackageName() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().packageName("com.example.bad-package").build();

        assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config));
    }

    @Test
    public void testGenerateEntityClass_RejectsSpecialTableNameBeforeMetadataQuery() throws SQLException {
        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateEntityClass(connection, "order-history"));

        assertTrue(failure.getMessage().contains("quoting is disabled"), failure.getMessage());
        Mockito.verify(connection, Mockito.never()).prepareStatement(ArgumentMatchers.anyString());
        Mockito.verify(databaseMetaData, Mockito.never()).getIdentifierQuoteString();
    }

    // A configured annotation must be structurally usable at the generated location. Silently
    // falling back to Abacus @Table made the configuration appear to work while ignoring it.
    @Test
    public void testGenerateEntityClass_RejectsIncompatibleCustomAnnotationClass() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .tableAnnotationClass(org.junit.jupiter.api.Test.class)
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config));
    }

    @Test
    public void testGenerateEntityClass_RejectsInaccessibleCustomAnnotationClass() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .tableAnnotationClass(PrivateTable.class)
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config));
    }

    // empty columnClassName — falls back to Object.class — L839
    @Test
    public void testGenerateEntityClass_WithEmptyColumnClassName() throws SQLException {
        final Statement stmt = Mockito.mock(Statement.class);
        final ResultSet pkRs = Mockito.mock(ResultSet.class);
        when(resultSet.getStatement()).thenReturn(stmt);
        when(stmt.getConnection()).thenReturn(connection);
        when(databaseMetaData.getPrimaryKeys(null, null, "order_history")).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(false);
        when(resultSetMetaData.getColumnLabel(1)).thenReturn("payload");
        when(resultSetMetaData.getColumnType(1)).thenReturn(Types.OTHER);
        when(resultSetMetaData.getColumnClassName(1)).thenReturn(""); // empty → Object.class fallback
        when(resultSetMetaData.getColumnName(2)).thenReturn("created_at");
        when(resultSetMetaData.getColumnType(2)).thenReturn(Types.TIMESTAMP);
        when(resultSetMetaData.getColumnClassName(2)).thenReturn("java.sql.Timestamp");
        when(resultSetMetaData.getColumnName(3)).thenReturn("status");
        when(resultSetMetaData.getColumnType(3)).thenReturn(Types.VARCHAR);
        when(resultSetMetaData.getColumnClassName(3)).thenReturn("java.lang.String");

        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", null);
        assertNotNull(result);
        assertTrue(result.contains("Object payload"));
    }

    @Test
    public void testGenerateEntityClassRejectsColumnsThatMapToDuplicateFieldNames() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final ResultSetMetaData metadata = Mockito.mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(2);
        when(metadata.getColumnName(1)).thenReturn("user_id");
        when(metadata.getColumnName(2)).thenReturn("userId");
        when(metadata.getColumnClassName(1)).thenReturn(String.class.getName());
        when(metadata.getColumnClassName(2)).thenReturn(String.class.getName());
        when(metadata.getColumnType(1)).thenReturn(Types.VARCHAR);
        when(metadata.getColumnType(2)).thenReturn(Types.VARCHAR);

        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateEntityClass("TestEntity", rs, null));

        assertTrue(thrown.getMessage().contains("user_id"));
        assertTrue(thrown.getMessage().contains("userId"));
    }

    // Only a declaration with a parameter list or body (before any initializer) is skipped as a non-field: a malformed field
    // declaration is still rejected with the parse error, as before. Bodiless methods and annotation elements (here of nested
    // types) are skipped, and source in a block comment or text block is not parsed at all (before, it was parsed: "private
    // int;" failed and Deque got imported).
    @Test
    public void testGenerateEntityClass_MalformedAdditionalFieldDeclarationIsStillRejected() {
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().className("OrderHistory").build();

        for (final String malformed : List.of("    private int;", "    private int a, int b;", "    private int = 1;", "    private int = foo();")) {
            config.setAdditionalClassBodySource(malformed);

            final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> JdbcCodeGenerationUtil.generateEntityClass("order_history", resultSet, config), malformed);
            assertTrue(ex.getMessage().contains("Cannot parse field declaration"), ex.getMessage());
        }

        config.setAdditionalClassBodySource(String.join("\n", //
                "    public interface Visitor {", //
                "        public void visit(String value);", //
                "    }", //
                "    public @interface Marker {", //
                "        public String value() default \"\";", //
                "        public int order() default 1;", //
                "    }", //
                "    /*", //
                "    private int;", //
                "    private Deque<String> legacy;", //
                "    */", //
                "    private static final String SQL = \"\"\"", //
                "        public select;", //
                "        \"\"\";"));

        final String code = assertDoesNotThrow(() -> JdbcCodeGenerationUtil.generateEntityClass("order_history", resultSet, config));

        assertFalse(code.contains("import java.util.Deque;"), code);
    }

    // Test generateEntityClass DataSource wraps SQL exceptions for entity overload
    @Test
    public void testGenerateEntityClass_DataSource_WrapsSQLException() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("connection failed"));
        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateEntityClass(dataSource, "order_history"));
    }

    // generateEntityClass(DataSource, entityName, query, config) wraps SQLException from getConnection (L291-292)
    @Test
    public void testGenerateEntityClass_DataSourceEntityNameQueryConfig_WrapsSQLException() throws SQLException {
        final DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("connection failed"));

        assertThrows(UncheckedSQLException.class,
                () -> JdbcCodeGenerationUtil.generateEntityClassByQuery(dataSource, "MyEntity", "SELECT * FROM t WHERE 1=2", null));
    }

    // generateEntityClass(Connection, entityName, query, config) wraps SQLException from prepareStatement (L385-386)
    @Test
    public void testGenerateEntityClass_ConnectionEntityNameQueryConfig_WrapsSQLException() throws SQLException {
        final Connection conn = Mockito.mock(Connection.class);
        when(conn.prepareStatement(ArgumentMatchers.anyString())).thenThrow(new SQLException("prepare failed"));

        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateEntityClassByQuery(conn, "MyEntity", "SELECT * FROM t WHERE 1=2", null));
    }

    // Exercise catch blocks at lines 818–820 (SQLException → UncheckedSQLException)
    @Test
    public void testGenerateEntityClass_ResultSetMetaData_WrapsSQLException() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(rs.getMetaData()).thenThrow(new SQLException("metadata failed"));

        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateEntityClass("TestEntity", rs, null));
    }

    // srcDir set, but the destination .java path is occupied by a directory. IOUtil (since abacus-common
    // 8.0.1) classifies a failed open whose target is a directory as a wrong-kind argument and reports
    // IllegalArgumentException instead of letting the platform's FileNotFoundException through, so this
    // case no longer reaches the IOException → UncheckedIOException wrap — that wrap is covered by
    // testGenerateEntityClass_SrcDirWriteFails_ThrowsUncheckedIOException.
    @Test
    public void testGenerateEntityClass_SrcDirTargetIsDirectory_ThrowsIllegalArgumentException() throws Exception {
        final Path tempDir = Files.createTempDirectory("jdbcCodeGenTest");
        try {
            final Path targetDir = tempDir.resolve("OrderHistory.java");
            Files.createDirectories(targetDir);

            setupFullGenerateEntityClassMock();
            final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                    .srcDir(tempDir.toString())
                    .className("OrderHistory")
                    .build();

            final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config));
            assertTrue(ex.getMessage().contains("is a directory"), ex.getMessage());
        } finally {
            deleteRecursively(tempDir.toFile());
        }
    }

    // srcDir set but the on-disk write fails (destination .java pre-created read-only) -> the
    // IOException is wrapped as UncheckedIOException (L1019-1021).
    @Test
    public void testGenerateEntityClass_SrcDirWriteFails_ThrowsUncheckedIOException() throws Exception {
        setupFullGenerateEntityClassMock();
        final Path tempDir = Files.createTempDirectory("jdbcCodeGenIoFail");
        try {
            // Pre-create the destination .java file read-only so opening it for writing fails. Unlike a
            // directory sitting at that path (which IOUtil reports as IllegalArgumentException), this
            // keeps the failure a genuine IOException.
            final Path pkgDir = Files.createDirectories(tempDir.resolve("pkg"));
            final File blocked = pkgDir.resolve("OrderHistory.java").toFile();
            assertTrue(blocked.createNewFile());
            assertTrue(blocked.setReadOnly());
            // A privileged user (root) is not bound by the read-only flag; there is nothing to assert then.
            assumeTrue(!blocked.canWrite(), "the filesystem does not enforce the read-only flag for this user");

            final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                    .srcDir(tempDir.toString())
                    .packageName("pkg")
                    .className("OrderHistory")
                    .build();

            final UncheckedIOException ex = assertThrows(UncheckedIOException.class,
                    () -> JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config));
            assertInstanceOf(IOException.class, ex.getCause());
        } finally {
            deleteRecursively(tempDir.toFile());
        }
    }

    @Test
    public void testInvalidKeyColumnsAreRejectedBeforeDatabaseAccess() {
        final DataSource unusedSource = Mockito.mock(DataSource.class);
        final Connection unusedConnection = Mockito.mock(Connection.class);
        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateUpdateSql(unusedSource, "users", " "));
        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(unusedSource, "users", " "));
        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateUpdateSql(unusedConnection, "users", " "));
        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(unusedConnection, "users", " "));
        // A blank element of the keyColumnNames collection is reported under that parameter, not as 'keyColumnName'.
        for (final IllegalArgumentException e : List.of(
                assertThrows(IllegalArgumentException.class,
                        () -> JdbcCodeGenerationUtil.generateUpdateSql(unusedSource, "users", null, Arrays.asList("id", null), null)),
                assertThrows(IllegalArgumentException.class,
                        () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(unusedSource, "users", null, List.of("id", " "), null)),
                assertThrows(IllegalArgumentException.class,
                        () -> JdbcCodeGenerationUtil.generateUpdateSql(unusedConnection, "users", null, List.of("id", " "), null)),
                assertThrows(IllegalArgumentException.class,
                        () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(unusedConnection, "users", null, Arrays.asList("id", null), null)))) {
            assertEquals("An element of 'keyColumnNames' cannot be null or empty or blank", e.getMessage());
        }
        Mockito.verifyNoInteractions(unusedSource, unusedConnection);
    }

    @Test
    public void testNullConfigurationEntriesAreRejectedBeforeDatabaseAccess() {
        final DataSource unusedSource = Mockito.mock(DataSource.class);
        final Connection unusedConnection = Mockito.mock(Connection.class);
        final ResultSet unusedResultSet = Mockito.mock(ResultSet.class);
        final List<JdbcCodeGenerationUtil.EntityCodeConfig> configurations = List.of(
                JdbcCodeGenerationUtil.EntityCodeConfig.builder().customFieldMappings(Arrays.asList((FieldMapping) null)).build(),
                JdbcCodeGenerationUtil.EntityCodeConfig.builder().fieldTypeAnnotationArguments(Arrays.asList((Tuple.Tuple2<String, String>) null)).build());

        for (final JdbcCodeGenerationUtil.EntityCodeConfig config : configurations) {
            assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateEntityClass(unusedSource, "users", config));
            assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateEntityClass(unusedConnection, "users", config));
            assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateEntityClassByQuery(unusedSource, "Users", "SELECT 1", config));
            assertThrows(IllegalArgumentException.class,
                    () -> JdbcCodeGenerationUtil.generateEntityClassByQuery(unusedConnection, "Users", "SELECT 1", config));
            assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateEntityClass("Users", unusedResultSet, config));
        }

        Mockito.verifyNoInteractions(unusedSource, unusedConnection, unusedResultSet);
    }

    // The class/package name, annotation-class, read-only/non-updatable and additional-body checks don't depend on column
    // metadata but ran only after the public overloads had opened a connection and prepared (on the fallback path even
    // executed) the caller's query, which can be expensive, before rejecting the configuration.
    @Test
    public void testInvalidConfigurationIsRejectedBeforeDatabaseAccess() {
        final DataSource unusedSource = Mockito.mock(DataSource.class);
        final Connection unusedConnection = Mockito.mock(Connection.class);
        final List<JdbcCodeGenerationUtil.EntityCodeConfig> configurations = List.of(
                JdbcCodeGenerationUtil.EntityCodeConfig.builder().className("123Entity").build(),
                JdbcCodeGenerationUtil.EntityCodeConfig.builder().className("record").build(),
                JdbcCodeGenerationUtil.EntityCodeConfig.builder().packageName("com.example.bad-package").build(),
                JdbcCodeGenerationUtil.EntityCodeConfig.builder().readOnlyFields(List.of("status")).nonUpdatableFields(List.of("status")).build(),
                JdbcCodeGenerationUtil.EntityCodeConfig.builder().tableAnnotationClass(FieldOnlyTable.class).build(),
                JdbcCodeGenerationUtil.EntityCodeConfig.builder().idAnnotationClass(PrivateTable.class).build(),
                JdbcCodeGenerationUtil.EntityCodeConfig.builder().additionalClassBodySource("    private int;").build());

        for (final JdbcCodeGenerationUtil.EntityCodeConfig config : configurations) {
            assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateEntityClass(unusedSource, "users", config));
            assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateEntityClass(unusedConnection, "users", config));
            assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateEntityClassByQuery(unusedSource, "Users", "SELECT 1", config));
            assertThrows(IllegalArgumentException.class,
                    () -> JdbcCodeGenerationUtil.generateEntityClassByQuery(unusedConnection, "Users", "SELECT 1", config));
        }

        // Without a configured class name, the default derived from the entity name is checked as well.
        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateEntityClassByQuery(unusedSource, "1st report", "SELECT 1"));
        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateEntityClassByQuery(unusedConnection, "1st report", "SELECT 1"));

        Mockito.verifyNoInteractions(unusedSource, unusedConnection);
    }

    @Test
    public void testNullOptionalConfigurationListsStillMeanNoOverrides() {
        final JdbcCodeGenerationUtil.EntityCodeConfig emptyConfig = JdbcCodeGenerationUtil.EntityCodeConfig.builder().build();
        final JdbcCodeGenerationUtil.EntityCodeConfig nullLists = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .customFieldMappings(null)
                .fieldTypeAnnotationArguments(null)
                .build();

        assertEquals(JdbcCodeGenerationUtil.generateEntityClass("order_history", resultSet, emptyConfig),
                JdbcCodeGenerationUtil.generateEntityClass("order_history", resultSet, nullLists));
        assertEquals(JdbcCodeGenerationUtil.generateEntityClass("order_history", resultSet, emptyConfig),
                JdbcCodeGenerationUtil.generateEntityClass("order_history", resultSet, new JdbcCodeGenerationUtil.EntityCodeConfig()));
    }

    @Test
    public void testBlankEntityNameIsRejectedEvenWhenClassNameIsConfigured() {
        // A blank entityName used to reach the generator and emit an empty @Table name instead of failing.
        final Connection unusedConnection = Mockito.mock(Connection.class);
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().className("Users").build();

        assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateEntityClassByQuery(unusedConnection, " ", "SELECT * FROM users WHERE 1 > 2", config));
        Mockito.verifyNoInteractions(unusedConnection);
    }

    @Target(ElementType.TYPE)
    public @interface WrongTableMember {
        int name();
    }

    @Target(ElementType.TYPE)
    public @interface ExtraRequiredTableMember {
        String name();

        String required();
    }

    @Target(ElementType.FIELD)
    public @interface FieldOnlyTable {
        String name();
    }

    @Target(ElementType.TYPE)
    public @interface CustomTable {
        String name();
    }

    @Target(ElementType.FIELD)
    public @interface CustomColumn {
        String name();
    }

    @Target(ElementType.FIELD)
    public @interface CustomId {
    }

    @Target(ElementType.TYPE)
    private @interface PrivateTable {
        String name();
    }

    private static void deleteRecursively(final File file) {
        if (file.isDirectory()) {
            final File[] children = file.listFiles();
            if (children != null) {
                for (final File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        // A read-only file (see testGenerateEntityClass_SrcDirWriteFails_ThrowsUncheckedIOException)
        // cannot be deleted on Windows until the flag is cleared.
        file.setWritable(true);
        file.delete();
    }
}
