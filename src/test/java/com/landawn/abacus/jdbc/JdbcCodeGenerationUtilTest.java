package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.jdbc.JdbcCodeGenerationUtil.EntityCodeConfig.FieldMapping;
import com.landawn.abacus.util.EscapeUtil;
import com.landawn.abacus.util.NamingPolicy;
import com.landawn.abacus.util.Tuple;

class JdbcCodeGenerationUtilTest extends JdbcCodeGenerationUtilTestSupport {

    @Test
    public void testInsertConversionValidatesDataSourceBeforeSql() {
        assertTrue(
                assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(null, null)).getMessage().contains("ds"));
    }

    @Test
    public void testMinFunc() {
        assertEquals("min(score)", JdbcCodeGenerationUtil.MIN_FUNC.apply(TestEntity.class, Integer.class, "score"));
        assertEquals("min(score)", JdbcCodeGenerationUtil.MIN_FUNC.apply(TestEntity.class, int.class, "score"));
        assertNull(JdbcCodeGenerationUtil.MIN_FUNC.apply(TestEntity.class, Object.class, "payload"));
    }

    @Test
    public void testMaxFunc() {
        assertEquals("max(createdAt)", JdbcCodeGenerationUtil.MAX_FUNC.apply(TestEntity.class, String.class, "createdAt"));
        assertEquals("max(createdAt)", JdbcCodeGenerationUtil.MAX_FUNC.apply(TestEntity.class, char.class, "createdAt"));
        assertNull(JdbcCodeGenerationUtil.MAX_FUNC.apply(TestEntity.class, Runnable.class, "handler"));
    }

    @Test
    public void testEntityCodeConfigBuilder() {
        JdbcCodeGenerationUtil.EntityCodeConfig.JsonXmlConfig jsonXmlConfig = JdbcCodeGenerationUtil.EntityCodeConfig.JsonXmlConfig.builder()
                .namingPolicy(NamingPolicy.CAMEL_CASE)
                .ignoredFields("secret")
                .build();

        JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .className("DemoEntity")
                .useBoxedTypes(true)
                .jsonXmlConfig(jsonXmlConfig)
                .build();

        assertEquals("DemoEntity", config.getClassName());
        assertEquals(true, config.isUseBoxedTypes());
        assertEquals(jsonXmlConfig, config.getJsonXmlConfig());
    }

    // Test generateEntityClass with customized EntityCodeConfig fields
    @Test
    public void testEntityCodeConfig_GettersAndSetters() {
        JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .className("TestEntity")
                .packageName("com.test")
                .useBoxedTypes(false)
                .idFields(Arrays.asList("id"))
                .readOnlyFields(Arrays.asList("createdAt"))
                .nonUpdatableFields(Arrays.asList("version"))
                .build();

        assertEquals("TestEntity", config.getClassName());
        assertEquals("com.test", config.getPackageName());
        assertNull(config.getSrcDir());
    }

    // Regression: schema-controlled identifiers containing a double-quote, backslash, or newline
    // (legal in PostgreSQL/Oracle/SQL Server quoted/bracketed identifiers) used to be interpolated
    // raw into @Table(name = "...") / @Column(name = "...") string literals, producing malformed
    // (or syntactically valid-but-different) Java. Fix escapes via EscapeUtil.escapeJava.
    @Test
    public void testEscapeJava_HandlesQuoteBackslashNewline() {
        assertEquals("plain", EscapeUtil.escapeJava("plain"));
        assertEquals("a\\\"b", EscapeUtil.escapeJava("a\"b"));
        assertEquals("a\\\\b", EscapeUtil.escapeJava("a\\b"));
        assertEquals("a\\nb", EscapeUtil.escapeJava("a\nb"));
        assertEquals("a\\rb", EscapeUtil.escapeJava("a\rb"));
        assertEquals("a\\tb", EscapeUtil.escapeJava("a\tb"));
        assertNull(EscapeUtil.escapeJava(null));
        assertEquals("", EscapeUtil.escapeJava(""));
    }

    @Test
    public void testFieldMappingRejectsBlankColumnName() {
        // columnName is the selector of the mapping: a null one used to surface as an NPE from inside the
        // generator's key-mapper, and a blank one silently matched no column at all.
        assertThrows(IllegalArgumentException.class, () -> new FieldMapping(null, "createdAt", java.util.Date.class));
        assertThrows(IllegalArgumentException.class, () -> new FieldMapping(" ", "createdAt", java.util.Date.class));
    }

    @Test
    public void testEntityCodeConfigPublicOptionNames() throws NoSuchFieldException, NoSuchMethodException {
        final FieldMapping mapping = new FieldMapping("created_at", "createdAt", java.util.Date.class);
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .customFieldMappings(List.of(mapping))
                .fieldTypeAnnotationArguments(List.of(Tuple.of("createdAt", "name = \"Date\"")))
                .useBoxedTypes(true)
                .additionalClassBodySource("private String extra;")
                .generateChainAccessors(true)
                .generatePropNameTable(true)
                .build();

        assertEquals("created_at", config.getCustomFieldMappings().get(0).columnName());
        assertEquals("createdAt", config.getCustomFieldMappings().get(0).fieldName());
        assertEquals(java.util.Date.class, config.getCustomFieldMappings().get(0).fieldType());
        assertTrue(config.isUseBoxedTypes());
        assertTrue(config.isGenerateChainAccessors());
        assertTrue(config.isGeneratePropNameTable());

        assertThrows(NoSuchFieldException.class, () -> JdbcCodeGenerationUtil.EntityCodeConfig.class.getDeclaredField("customizedFields"));
        assertThrows(NoSuchFieldException.class, () -> JdbcCodeGenerationUtil.EntityCodeConfig.class.getDeclaredField("customizedFieldDbTypes"));
        assertThrows(NoSuchFieldException.class, () -> JdbcCodeGenerationUtil.EntityCodeConfig.class.getDeclaredField("useBoxedType"));
        assertThrows(NoSuchFieldException.class, () -> JdbcCodeGenerationUtil.EntityCodeConfig.class.getDeclaredField("additionalFieldsOrLines"));
        assertThrows(NoSuchFieldException.class, () -> JdbcCodeGenerationUtil.EntityCodeConfig.class.getDeclaredField("chainAccessor"));
        assertThrows(NoSuchFieldException.class, () -> JdbcCodeGenerationUtil.EntityCodeConfig.class.getDeclaredField("generateFieldNameTable"));

        assertThrows(NoSuchFieldException.class, () -> JdbcCodeGenerationUtil.EntityCodeConfig.class.getDeclaredField("idField"));
        assertThrows(NoSuchMethodException.class, () -> JdbcCodeGenerationUtil.EntityCodeConfig.class.getMethod("getIdField"));
        assertThrows(NoSuchMethodException.class, () -> JdbcCodeGenerationUtil.EntityCodeConfig.class.getMethod("setIdField", String.class));
        assertThrows(NoSuchMethodException.class,
                () -> JdbcCodeGenerationUtil.EntityCodeConfig.EntityCodeConfigBuilder.class.getMethod("idField", String.class));
    }
}
