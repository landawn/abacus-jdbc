package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.jdbc.DaoImplUpsertRegressionTest.IdentityRow;
import com.landawn.abacus.jdbc.DaoImplUpsertRegressionTest.NullableRow;
import com.landawn.abacus.jdbc.annotation.Bind;
import com.landawn.abacus.jdbc.annotation.Query;
import com.landawn.abacus.jdbc.annotation.SqlFragment;
import com.landawn.abacus.jdbc.dao.CrudDao;
import com.landawn.abacus.util.EntityId;
import com.landawn.abacus.util.u.Optional;

class DaoImplGeneratedKeyRegressionTest extends TestBase {
    private JdbcDataSource ds;
    private Connection keeper;
    private KeyDao dao;

    @BeforeEach
    void openDatabase() throws SQLException {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:key_regression_" + UUID.randomUUID());
        keeper = ds.getConnection();
        try (Statement stmt = keeper.createStatement()) {
            // RETURN_GENERATED_KEYS returns CREATED before ID. Reading its first column as a Long fails after INSERT.
            stmt.execute(
                    "CREATE TABLE identity_row(created TIMESTAMP DEFAULT CURRENT_TIMESTAMP, id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, name VARCHAR)");
            // Other tables need the same protection: H2 returns CREATED before LOG_ID unless the key is requested by name.
            stmt.execute(
                    "CREATE TABLE other_row(created TIMESTAMP DEFAULT CURRENT_TIMESTAMP, log_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, name VARCHAR)");
            stmt.execute("CREATE SCHEMA other_schema");
            stmt.execute(
                    "CREATE TABLE other_schema.identity_row(created TIMESTAMP DEFAULT CURRENT_TIMESTAMP, log_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, name VARCHAR)");
            stmt.execute(
                    "CREATE TABLE \"identity_row\"(created TIMESTAMP DEFAULT CURRENT_TIMESTAMP, log_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, name VARCHAR)");
        }
        dao = JdbcUtil.createDao(KeyDao.class, ds);
    }

    @AfterEach
    void closeDatabase() throws SQLException {
        keeper.close();
    }

    public interface KeyDao extends CrudDao<IdentityRow, Long, KeyDao> {
        @Query("INSERT INTO identity_row(name) VALUES (:name)")
        Long named(@Bind("name") String name) throws SQLException;

        @Query("INSERT INTO identity_row(name) VALUES (?)")
        Long positional(String name) throws SQLException;

        @Query("INSERT INTO identity_row(name) VALUES (CONCAT(:first, :last))")
        Optional<Long> optional(@Bind("first") String first, @Bind("last") String last) throws SQLException;

        @Query("/* INSERT INTO other_row */ INSERT /* target */ INTO \"IDENTITY_ROW\"(name) VALUES (?)")
        Long quoted(String name) throws SQLException;

        @Query(value = "INSERT INTO identity_row(name) VALUES (?)", batch = true, collectionAsSingleParameter = true)
        List<Long> positionalBatch(List<String> names, int batchSize) throws SQLException;

        @Query(value = "INSERT INTO identity_row(name) VALUES (:name)", batch = true)
        List<Long> namedBatch(List<Map<String, Object>> rows, int batchSize) throws SQLException;

        @Query("INSERT INTO {table}(name) VALUES (:name)")
        Long dynamic(@SqlFragment("table") String table, @Bind("name") String name) throws SQLException;

        @Query("INSERT INTO other_schema.identity_row(name) VALUES (?)")
        Long otherSchema(String name) throws SQLException;

        @Query("INSERT INTO other_row(name) VALUES (:name)")
        void copy(IdentityRow row) throws SQLException;

        @Query("INSERT INTO {table}(name) VALUES (:name)")
        Long copyTo(@SqlFragment("table") String table, IdentityRow row) throws SQLException;

        @Query(value = "INSERT INTO {table}(name) VALUES (:name)", batch = true)
        List<Long> copyBatch(@SqlFragment("table") String table, List<IdentityRow> rows, int batchSize) throws SQLException;

        @Query(value = "INSERT INTO other_row(name) VALUES (:name)", batch = true)
        void copyBatchVoid(List<IdentityRow> rows, int batchSize) throws SQLException;
    }

    public interface QualifiedDao extends CrudDao<IdentityRow, Long, QualifiedDao> {
        @Query("INSERT INTO \"PUBLIC\" . \"IDENTITY_ROW\"(name) VALUES (?)")
        Long insertName(String name) throws SQLException;
    }

    public interface GeneratedColumnDao extends CrudDao<IdentityRow, Long, GeneratedColumnDao> {
        @Query("INSERT INTO {table}(code, name) VALUES (:code, :name)")
        long number(@SqlFragment("table") String table, @Bind("code") String code, @Bind("name") String name) throws SQLException;

        @Query("INSERT INTO {table}(code, name) VALUES (:code, :name)")
        String text(@SqlFragment("table") String table, @Bind("code") String code, @Bind("name") String name) throws SQLException;

        @Query("INSERT INTO {table}(code, name) VALUES (:code, :name)")
        Optional<Long> optional(@SqlFragment("table") String table, @Bind("code") String code, @Bind("name") String name) throws SQLException;

        @Query(value = "INSERT INTO {table}(code, name) VALUES (:code, :name)", batch = true)
        List<Long> batch(@SqlFragment("table") String table, List<Map<String, Object>> rows, int batchSize) throws SQLException;

        @Query("INSERT INTO generated_primary(name) VALUES (:name)")
        String generatedPrimary(@Bind("name") String name) throws SQLException;
    }

    public interface AuditInserts {
        @Query("INSERT INTO other_row(name) VALUES (:name)")
        long log(@Bind("name") String name) throws SQLException;

        @Query("INSERT INTO other_row(name) VALUES (?)")
        Optional<Long> logOptional(String name) throws SQLException;

        @Query("INSERT INTO other_row(name) VALUES (:name)")
        int logNarrow(@Bind("name") String name) throws SQLException;

        @Query(value = "INSERT INTO other_row(name) VALUES (?)", batch = true, collectionAsSingleParameter = true)
        List<Long> logBatch(List<String> names, int batchSize) throws SQLException;

        @Query(value = "INSERT INTO other_row(name) VALUES (:name)", batch = true)
        List<Integer> logNamedBatch(List<Map<String, Object>> rows, int batchSize) throws SQLException;

        @Query("INSERT INTO {table}(name) VALUES (:name)")
        Object dynamic(@SqlFragment("table") String table, @Bind("name") String name) throws SQLException;

        @Query("INSERT INTO other_row(name) VALUES (:name)")
        void logObject(@Bind("name") Object name) throws SQLException;

        @Query("INSERT INTO other_row(name) VALUES (:name)")
        void logInterface(@Bind("name") Serializable name) throws SQLException;

        @Query("INSERT INTO plain_row(name) VALUES (?)")
        Optional<Long> noKey(String name) throws SQLException;

        @Query(value = "INSERT INTO plain_row(name) VALUES (?)", batch = true, collectionAsSingleParameter = true)
        List<Long> noKeys(List<String> names) throws SQLException;

        @Query("INSERT INTO zero_row(name) VALUES (?)")
        Optional<Long> zeroKey(String name) throws SQLException;

        @Query(value = "INSERT INTO zero_row(name) VALUES (?)", batch = true, collectionAsSingleParameter = true)
        List<Long> zeroKeys(List<String> names) throws SQLException;

        @Query("INSERT INTO text_row(name) VALUES (?)")
        String textKey(String name) throws SQLException;

        @Query(value = "INSERT INTO text_row(name) VALUES (?)", batch = true, collectionAsSingleParameter = true)
        List<Long> numericTextKeys(List<String> names, int batchSize) throws SQLException;
    }

    public interface CompositeAuditDao extends CrudDao<NullableRow, EntityId, CompositeAuditDao>, AuditInserts {
        @Query("INSERT INTO other_row(name) VALUES (:payload)")
        void copy(NullableRow row) throws SQLException;

        @Query(value = "INSERT INTO other_row(name) VALUES (:payload)", batch = true)
        void copyBatch(List<NullableRow> rows) throws SQLException;
    }

    public record CompositeKey(Long tenantId, Long rowId) {
    }

    public interface RecordAuditDao extends CrudDao<NullableRow, CompositeKey, RecordAuditDao>, AuditInserts {
    }

    public interface CustomKeyDao extends CrudDao<IdentityRow, Long, CustomKeyDao>, AuditInserts {
        @Override
        default Jdbc.BiRowMapper<Long> idExtractor() {
            return (rs, columns) -> rs.getLong("ID") + 1000;
        }

        @Query("INSERT INTO identity_row(name) VALUES (?)")
        Long ownTable(String name) throws SQLException;

        @Query("INSERT INTO other_row(name) VALUES (:name)")
        Long copy(IdentityRow row) throws SQLException;
    }

    private AuditInserts auditDao(final boolean recordId) {
        return recordId ? JdbcUtil.createDao(RecordAuditDao.class, ds) : JdbcUtil.createDao(CompositeAuditDao.class, ds);
    }

    private void execute(final String sql) throws SQLException {
        try (Statement stmt = keeper.createStatement()) {
            stmt.execute(sql);
        }
    }

    private long count(final String table) throws SQLException {
        try (Statement stmt = keeper.createStatement();
             ResultSet rows = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    // A case-insensitive database can preserve the stored spelling while getColumns still matches patterns exactly.
    // Both a generated PK and a generated alternative to a supplied PK must use the identifiers resolved by getPrimaryKeys.
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = { "PUBLIC | AuditLog | auditlog | true", "PUBLIC | AuditLog | auditlog | false",
            "PUBLIC | AuditLog | PUBLIC.auditlog | true", "PUBLIC | AuditLog | PUBLIC.auditlog | false", "MixedSchema | AuditLog | MixedSchema.auditlog | true",
            "MixedSchema | AuditLog | MixedSchema.auditlog | false", "MixedSchema | Audit%_Log | MixedSchema.\"audit%_log\" | true",
            "MixedSchema | Audit%_Log | MixedSchema.\"audit%_log\" | false" })
    void generatedKeysUseStoredIdentifierSpelling(final String schema, final String storedTable, final String insertTable, final boolean generatedPrimaryKey)
            throws SQLException {
        final JdbcDataSource caseInsensitive = new JdbcDataSource();
        caseInsensitive.setURL("jdbc:h2:mem:key_case_" + UUID.randomUUID() + ";DATABASE_TO_UPPER=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE");
        try (Connection conn = caseInsensitive.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE identity_row(id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, name VARCHAR)");
            stmt.execute("CREATE SCHEMA IF NOT EXISTS \"" + schema + "\"");
            final String qualifiedTable = "\"" + schema + "\".\"" + storedTable + "\"";
            stmt.execute("CREATE TABLE " + qualifiedTable + "(code VARCHAR" + (generatedPrimaryKey ? "" : " PRIMARY KEY")
                    + ", created TIMESTAMP DEFAULT CURRENT_TIMESTAMP, log_id BIGINT GENERATED ALWAYS AS IDENTITY" + (generatedPrimaryKey ? " PRIMARY KEY" : "")
                    + ", name VARCHAR)");
            // A wildcard-like name must still resolve only this table after its stored spelling is recovered.
            stmt.execute("CREATE TABLE \"" + schema + "\".\"AuditOtherLog\"(code VARCHAR PRIMARY KEY, wrong_id BIGINT GENERATED ALWAYS AS IDENTITY)");
            final GeneratedColumnDao generated = JdbcUtil.createDao(GeneratedColumnDao.class, caseInsensitive);
            assertEquals(1L, generated.number(insertTable, "a", "one"));
            assertEquals("2", generated.text(insertTable, "b", "two"));
            assertEquals(3L, generated.optional(insertTable, "c", "three").get());
            assertEquals(List.of(4L), generated.batch(insertTable, List.of(Map.of("code", "d", "name", "four")), 1));
            assertEquals(List.of(5L, 6L, 7L), generated.batch(insertTable,
                    List.of(Map.of("code", "e", "name", "five"), Map.of("code", "f", "name", "six"), Map.of("code", "g", "name", "seven")), 2));
            try (ResultSet rows = stmt.executeQuery("SELECT COUNT(*), MAX(log_id) FROM " + qualifiedTable)) {
                assertTrue(rows.next());
                assertEquals(7L, rows.getLong(1));
                assertEquals(7L, rows.getLong(2));
            }
        }
    }

    // A case-insensitive database that keeps mixed-case spellings (e.g. SQL Server) resolves another spelling of the
    // DAO's table to that table: the INSERT must keep the entity ID strategy and write the key back. It was treated as
    // another table, leaving the entity ID unset and failing a composite DAO after its row had been inserted.
    @Test
    void caseInsensitiveSpellingsOfTheDaoTableKeepTheEntityIdStrategy() throws SQLException {
        final JdbcDataSource caseInsensitive = new JdbcDataSource();
        caseInsensitive.setURL("jdbc:h2:mem:key_case_" + UUID.randomUUID() + ";DATABASE_TO_UPPER=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE");
        try (Connection conn = caseInsensitive.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(
                    "CREATE TABLE identity_row(created TIMESTAMP DEFAULT CURRENT_TIMESTAMP, id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, name VARCHAR)");
            stmt.execute(
                    "CREATE TABLE nullable_row(tenant_id BIGINT DEFAULT 7, row_id BIGINT GENERATED ALWAYS AS IDENTITY, match_key VARCHAR, payload VARCHAR, name VARCHAR)");
            final KeyDao keyDao = JdbcUtil.createDao(KeyDao.class, caseInsensitive);
            final IdentityRow row = existingRow(999L);
            assertEquals(1L, keyDao.copyTo("IDENTITY_ROW", row));
            assertEquals(1L, row.getId());
            final List<IdentityRow> rows = List.of(existingRow(998L), existingRow(999L));
            assertEquals(List.of(2L, 3L), keyDao.copyBatch("PUBLIC.Identity_Row", rows, 1));
            assertEquals(2L, rows.get(0).getId());
            assertEquals(3L, rows.get(1).getId());

            final Object compositeId = JdbcUtil.createDao(CompositeAuditDao.class, caseInsensitive).dynamic("NULLABLE_ROW", "own table");
            assertEquals(7L, ((EntityId) compositeId).<Long> get("tenantId"));
            assertEquals(1L, ((EntityId) compositeId).<Long> get("rowId"));
        }
    }

    @Test
    void quotedTablesKeepTheirDistinctKeyMetadata() throws SQLException {
        execute("CREATE TABLE \"AuditLog\"(code VARCHAR PRIMARY KEY, log_id BIGINT GENERATED ALWAYS AS IDENTITY(START WITH 10), name VARCHAR)");
        execute("CREATE TABLE \"auditlog\"(code VARCHAR PRIMARY KEY, other_id BIGINT GENERATED ALWAYS AS IDENTITY(START WITH 100), name VARCHAR)");
        final GeneratedColumnDao generated = JdbcUtil.createDao(GeneratedColumnDao.class, ds);
        assertEquals(10L, generated.number("\"AuditLog\"", "a", "upper"));
        assertEquals(100L, generated.number("\"auditlog\"", "b", "lower"));
        assertEquals(1L, count("\"AuditLog\""));
        assertEquals(1L, count("\"auditlog\""));
    }

    // A supplied PK (even one with a default) must not shadow an identity on another column. Return-type conversion
    // previously either failed after committing the INSERT or silently returned the supplied string PK.
    @ParameterizedTest
    @ValueSource(strings = { "supplied_key", "other_schema.supplied_key", "\"Supplied%_Key\"" })
    void suppliedPrimaryKeyDoesNotShadowGeneratedIdentity(final String table) throws SQLException {
        execute("CREATE TABLE " + table + "(code VARCHAR(20) DEFAULT 'fallback' PRIMARY KEY, created TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                + "\"Log_Id\" BIGINT GENERATED ALWAYS AS IDENTITY, name VARCHAR, computed_name VARCHAR GENERATED ALWAYS AS (name))");
        // A pattern-similar table must not contribute generated-column candidates to the metadata lookup.
        execute("CREATE TABLE suppliedXkey(code VARCHAR PRIMARY KEY, wrong_id BIGINT GENERATED ALWAYS AS IDENTITY)");
        final GeneratedColumnDao generated = JdbcUtil.createDao(GeneratedColumnDao.class, ds);
        assertEquals(1L, generated.number(table, "c", "one"));
        assertEquals("2", generated.text(table, "d", "two"));
        assertEquals(3L, generated.optional(table, "e", "three").get());
        assertEquals(3L, count(table));
        try (Statement stmt = keeper.createStatement();
             ResultSet row = stmt.executeQuery("SELECT code, \"Log_Id\" FROM " + table + " WHERE name = 'one'")) {
            assertTrue(row.next());
            assertEquals("c", row.getString(1));
            assertEquals(1L, row.getLong(2));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 2, 10 })
    void batchesReturnGeneratedIdentitiesInsteadOfSuppliedPrimaryKeys(final int batchSize) throws SQLException {
        execute("CREATE TABLE supplied_key(code VARCHAR PRIMARY KEY, log_id BIGINT GENERATED ALWAYS AS IDENTITY, name VARCHAR)");
        final GeneratedColumnDao generated = JdbcUtil.createDao(GeneratedColumnDao.class, ds);
        assertEquals(List.of(1L), generated.batch("supplied_key", List.of(Map.of("code", "a", "name", "one")), batchSize));
        assertEquals(List.of(2L, 3L, 4L), generated.batch("supplied_key",
                List.of(Map.of("code", "b", "name", "two"), Map.of("code", "c", "name", "three"), Map.of("code", "d", "name", "four")), batchSize));
        assertEquals(4L, count("supplied_key"));
    }

    @Test
    void uniqueComputedColumnCanBeReturnedForASuppliedPrimaryKey() throws SQLException {
        execute("CREATE TABLE supplied_key(code VARCHAR PRIMARY KEY, log_id VARCHAR GENERATED ALWAYS AS (name), name VARCHAR)");
        final GeneratedColumnDao generated = JdbcUtil.createDao(GeneratedColumnDao.class, ds);
        assertEquals("generated value", generated.text("supplied_key", "supplied", "generated value"));
    }

    @Test
    void generatedPrimaryKeyStillWinsOverOtherGeneratedColumns() throws SQLException {
        execute("CREATE TABLE generated_primary(log_id VARCHAR GENERATED ALWAYS AS (name) PRIMARY KEY, "
                + "other_id BIGINT GENERATED ALWAYS AS IDENTITY, name VARCHAR)");
        assertEquals("generated value", JdbcUtil.createDao(GeneratedColumnDao.class, ds).generatedPrimary("generated value"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "", ", first_id BIGINT GENERATED ALWAYS AS IDENTITY, second_id BIGINT GENERATED ALWAYS AS IDENTITY",
            ", first_value VARCHAR GENERATED ALWAYS AS (name), second_value VARCHAR GENERATED ALWAYS AS (UPPER(name))" })
    void unresolvedGeneratedColumnsWithASuppliedPrimaryKeyFailBeforeWriting(final String extraColumns) throws SQLException {
        execute("CREATE TABLE supplied_key(code VARCHAR DEFAULT 'default key' PRIMARY KEY, name VARCHAR" + extraColumns + ")");
        final GeneratedColumnDao generated = JdbcUtil.createDao(GeneratedColumnDao.class, ds);
        final SQLException error = assertThrows(SQLException.class, () -> generated.number("supplied_key", "c", "one"));
        assertTrue(error.getMessage().contains("generated key"));
        assertThrows(SQLException.class, () -> generated.text("supplied_key", "c", "one"));
        assertThrows(SQLException.class, () -> generated.optional("supplied_key", "c", "one"));
        assertThrows(SQLException.class,
                () -> generated.batch("supplied_key", List.of(Map.of("code", "a", "name", "one"), Map.of("code", "b", "name", "two")), 1));
        assertEquals(0L, count("supplied_key"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "unknown", "unsupported", "null" })
    void missingGenerationMetadataForAKnownPrimaryKeyFailsBeforeWriting(final String mode) throws SQLException {
        execute("CREATE TABLE supplied_key(code VARCHAR PRIMARY KEY, log_id BIGINT GENERATED ALWAYS AS IDENTITY, name VARCHAR)");
        final JdbcDataSource wrapped = spy(ds);
        doAnswer(call -> {
            final Connection realConnection = ds.getConnection();
            final Connection connection = spy(realConnection);
            final DatabaseMetaData realMetadata = realConnection.getMetaData();
            final DatabaseMetaData metadata = spy(realMetadata);
            doReturn(metadata).when(connection).getMetaData();
            doAnswer(columnsCall -> {
                if (mode.equals("null")) {
                    // JDBC specifies a ResultSet, but tolerate non-conforming wrappers as for getPrimaryKeys.
                    return null;
                }
                final ResultSet columns = spy(realMetadata.getColumns(columnsCall.getArgument(0), columnsCall.getArgument(1), columnsCall.getArgument(2),
                        columnsCall.getArgument(3)));
                if (mode.equals("unknown")) {
                    doReturn("").when(columns).getString("IS_AUTOINCREMENT");
                    doReturn("").when(columns).getString("IS_GENERATEDCOLUMN");
                } else {
                    doThrow(new SQLFeatureNotSupportedException("generation flags unavailable")).when(columns).getString("IS_GENERATEDCOLUMN");
                }
                return columns;
            }).when(metadata).getColumns(any(), anyString(), anyString(), isNull());
            return connection;
        }).when(wrapped).getConnection();
        final GeneratedColumnDao generated = JdbcUtil.createDao(GeneratedColumnDao.class, wrapped);
        assertThrows(SQLException.class, () -> generated.number("supplied_key", "c", "one"));
        assertEquals(0L, count("supplied_key"));
    }

    // Empty metadata is JDBC's unavailable result; also tolerate unsupported methods and non-conforming null results.
    // The fallback still requires one returned column, and failed multi-row batches must roll back.
    @ParameterizedTest
    @ValueSource(strings = { "empty", "unsupported", "legacy", "null" })
    void unavailablePrimaryKeyMetadataUsesOnlyUnambiguousDriverKeys(final String mode) throws SQLException {
        execute("CREATE TABLE fallback_row(log_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, name VARCHAR)");
        final JdbcDataSource wrapped = spy(ds);
        doAnswer(call -> {
            final Connection realConnection = ds.getConnection();
            final Connection connection = spy(realConnection);
            final DatabaseMetaData realMetadata = realConnection.getMetaData();
            final DatabaseMetaData metadata = spy(realMetadata);
            doReturn(metadata).when(connection).getMetaData();
            doAnswer(keysCall -> switch (mode) {
                case "empty" -> realMetadata.getPrimaryKeys(realConnection.getCatalog(), "PUBLIC", "MISSING_METADATA_TABLE");
                case "unsupported" -> throw new SQLFeatureNotSupportedException("primary-key metadata unavailable");
                case "legacy" -> throw new AbstractMethodError("primary-key metadata unavailable");
                case "null" -> null;
                default -> throw new AssertionError(mode);
            }).when(metadata).getPrimaryKeys(any(), any(), any());
            return connection;
        }).when(wrapped).getConnection();
        final KeyDao fallback = JdbcUtil.createDao(KeyDao.class, wrapped);
        assertEquals(1L, fallback.dynamic("fallback_row", "one"));
        final List<IdentityRow> rows = List.of(existingRow(998L), existingRow(999L));
        assertEquals(List.of(2L), fallback.copyBatch("fallback_row", rows.subList(0, 1), 1));
        assertEquals(List.of(3L, 4L), fallback.copyBatch("fallback_row", rows, 1));
        assertEquals(4L, count("fallback_row"));

        final SQLException error = assertThrows(SQLException.class, () -> fallback.dynamic("other_row", "ambiguous"));
        assertTrue(error.getMessage().contains("generated-key columns"));
        assertThrows(SQLException.class, () -> fallback.copyBatch("other_row", rows, 1));
        // Only the autocommitted scalar INSERT remains; its ambiguous keys become available after execution.
        assertEquals(1L, count("other_row"));
        assertEquals(998L, rows.get(0).getId());
        assertEquals(999L, rows.get(1).getId());
    }

    // getPrimaryKeys takes a literal schema, but getColumns takes a schema pattern. Escaping only the latter must
    // select this schema's identity rather than the similarly named schema's key or a default in the target table.
    @ParameterizedTest
    @ValueSource(strings = { "S_1", "S%1" })
    void schemaWildcardCharactersStayLiteralForPrimaryKeyLookup(final String schema) throws SQLException {
        execute("CREATE SCHEMA \"" + schema + "\"");
        execute("CREATE SCHEMA SX1");
        final String table = "\"" + schema + "\".key_probe";
        execute("CREATE TABLE " + table + "(code VARCHAR PRIMARY KEY, wrong_id BIGINT DEFAULT 999, "
                + "right_id BIGINT GENERATED ALWAYS AS IDENTITY, name VARCHAR)");
        execute("CREATE TABLE SX1.key_probe(wrong_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, name VARCHAR)");
        final GeneratedColumnDao generated = JdbcUtil.createDao(GeneratedColumnDao.class, ds);
        assertEquals(1L, generated.number(table, "a", "one"));
        assertEquals("2", generated.text(table, "b", "two"));
        assertEquals(List.of(3L, 4L), generated.batch(table, List.of(Map.of("code", "c", "name", "three"), Map.of("code", "d", "name", "four")), 1));
        assertEquals(4L, count(table));
        assertEquals(0L, count("SX1.key_probe"));
    }

    private static IdentityRow existingRow(final long id) {
        final IdentityRow row = new IdentityRow();
        row.setId(id);
        row.setName("copy " + id);
        return row;
    }

    @Test
    void crossTableEntityArgumentsNeverReceiveTheDestinationId() throws SQLException {
        final IdentityRow row = existingRow(999L);
        dao.copy(row);
        assertEquals(999L, row.getId());
        assertEquals(2L, dao.copyTo("other_row", row));
        assertEquals(999L, row.getId());
        assertEquals(1L, dao.copyTo("identity_row", row));
        assertEquals(1L, row.getId());
        assertEquals(3L, dao.copyTo("PUBLIC.other_row", row));
        assertEquals(1L, row.getId());
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 2, 10 })
    void crossTableEntityBatchesKeepInputIds(final int batchSize) throws SQLException {
        final List<IdentityRow> rows = List.of(existingRow(998L), existingRow(999L));
        assertEquals(List.of(1L), dao.copyBatch("other_row", rows.subList(0, 1), batchSize));
        assertEquals(List.of(2L, 3L), dao.copyBatch("other_row", rows, batchSize));
        dao.copyBatchVoid(rows, batchSize);
        assertEquals(998L, rows.get(0).getId());
        assertEquals(999L, rows.get(1).getId());
        assertEquals(List.of(1L, 2L), dao.copyBatch("identity_row", rows, batchSize));
        assertEquals(1L, rows.get(0).getId());
        assertEquals(2L, rows.get(1).getId());
        assertEquals(5L, count("other_row"));
    }

    @Test
    void crossTableCompositeKeysAreRejectedBeforeWriting() throws SQLException {
        execute("CREATE TABLE composite_other(a BIGINT DEFAULT 7, b BIGINT GENERATED ALWAYS AS IDENTITY, name VARCHAR, PRIMARY KEY(a, b))");
        final SQLException error = assertThrows(SQLException.class, () -> dao.dynamic("composite_other", "ambiguous"));
        assertTrue(error.getMessage().contains("composite"));
        assertEquals(0L, count("composite_other"));
    }

    @Test
    void crossTableMultipleGeneratedValuesWithoutAKeyAreNotSilentlyConverted() throws SQLException {
        execute("CREATE TABLE ambiguous_other(created TIMESTAMP DEFAULT CURRENT_TIMESTAMP, sequence_value BIGINT GENERATED ALWAYS AS IDENTITY, name VARCHAR)");
        final SQLException error = assertThrows(SQLException.class, () -> dao.dynamic("ambiguous_other", "ambiguous"));
        assertTrue(error.getMessage().contains("generated-key columns"));
        // JDBC only exposes these returned columns after execution; a multi-row batch must roll back on that failure.
        final List<IdentityRow> rows = List.of(existingRow(998L), existingRow(999L));
        assertThrows(SQLException.class, () -> dao.copyBatch("ambiguous_other", rows, 1));
        assertEquals(1L, count("ambiguous_other"));
        assertEquals(998L, rows.get(0).getId());
        assertEquals(999L, rows.get(1).getId());
    }

    @Test
    void crossTableEntityCopiesIgnoreCompositeAndCustomExtractors() throws SQLException {
        final NullableRow composite = new NullableRow();
        composite.setTenantId(7L);
        composite.setRowId(999L);
        composite.setPayload("copy");
        final CompositeAuditDao compositeDao = JdbcUtil.createDao(CompositeAuditDao.class, ds);
        compositeDao.copy(composite);
        compositeDao.copyBatch(List.of(composite, composite));
        assertEquals(7L, composite.getTenantId());
        assertEquals(999L, composite.getRowId());

        final IdentityRow custom = existingRow(998L);
        assertEquals(4L, JdbcUtil.createDao(CustomKeyDao.class, ds).copy(custom));
        assertEquals(998L, custom.getId());
    }

    @Test
    void crossTableCopiesDoNotRegisterAnEntityIdRollback() throws SQLException {
        final IdentityRow row = existingRow(999L);
        final SqlTransaction transaction = JdbcUtil.beginTransaction(ds);
        try {
            assertEquals(1L, dao.copyTo("other_row", row));
            assertEquals(999L, row.getId());
            row.setId(1000L);
        } finally {
            transaction.rollback();
        }
        assertEquals(1000L, row.getId());
        assertEquals(0L, count("other_row"));
    }

    // A composite DAO used to wrap LOG_ID in its own EntityId, then throw converting it after the row committed.
    // Record IDs also exercise the default-key predicate, which must not inspect DAO properties on a scalar key.
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void crossTableScalarAndOptionalKeysIgnoreTheCompositeDaoId(final boolean recordId) throws SQLException {
        final AuditInserts audit = auditDao(recordId);
        assertEquals(1L, audit.log("named"));
        assertEquals(2L, audit.logOptional("positional").get());
        assertEquals(3, audit.logNarrow("converted"));
        audit.logObject("object");
        audit.logInterface("interface");
        assertEquals(5L, count("other_row"));
    }

    @ParameterizedTest
    @CsvSource({ "false, 1", "false, 2", "false, 10", "true, 1", "true, 2", "true, 10" })
    void crossTableBatchKeysCoverNamedPositionalAndChunkedCalls(final boolean recordId, final int batchSize) throws SQLException {
        final AuditInserts audit = auditDao(recordId);
        assertEquals(List.of(), audit.logBatch(null, batchSize));
        assertEquals(List.of(), audit.logBatch(List.of(), batchSize));
        assertEquals(List.of(1L), audit.logBatch(List.of("one"), batchSize));
        assertEquals(List.of(2L, 3L, 4L), audit.logBatch(List.of("two", "three", "four"), batchSize));
        assertEquals(List.of(5), audit.logNamedBatch(List.of(Map.of("name", "five")), batchSize));
        assertEquals(List.of(6, 7, 8), audit.logNamedBatch(List.of(Map.of("name", "six"), Map.of("name", "seven"), Map.of("name", "eight")), batchSize));
        assertEquals(8L, count("other_row"));
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void crossTableMissingAndZeroKeysUseTheReturnedValue(final boolean recordId) throws SQLException {
        execute("CREATE TABLE plain_row(name VARCHAR)");
        execute("CREATE TABLE zero_row(log_id BIGINT GENERATED ALWAYS AS IDENTITY(START WITH 0), name VARCHAR)");
        final AuditInserts audit = auditDao(recordId);
        assertFalse(audit.noKey("no key").isPresent());
        assertEquals(List.of(), audit.noKeys(List.of("no", "keys")));
        assertFalse(audit.zeroKey("zero").isPresent());
        execute("ALTER TABLE zero_row ALTER COLUMN log_id RESTART WITH 0");
        assertEquals(List.of(), audit.zeroKeys(List.of("zero again")));
        assertEquals(List.of(1L, 2L), audit.zeroKeys(List.of("one", "two")));
        assertEquals(3L, count("plain_row"));
        assertEquals(4L, count("zero_row"));
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void dynamicDestinationsKeepTheEntityIdStrategyOnlyForTheDaoTable(final boolean recordId) throws SQLException {
        execute("CREATE TABLE nullable_row(tenant_id BIGINT DEFAULT 7, row_id BIGINT GENERATED ALWAYS AS IDENTITY, match_key VARCHAR, payload VARCHAR, name VARCHAR)");
        final AuditInserts audit = auditDao(recordId);
        final Object first = audit.dynamic("nullable_row", "own table");
        if (recordId) {
            assertEquals(new CompositeKey(7L, 1L), first);
        } else {
            assertEquals(7L, ((EntityId) first).<Long> get("tenantId"));
            assertEquals(1L, ((EntityId) first).<Long> get("rowId"));
        }
        assertEquals(1L, audit.dynamic("other_row", "other table"));
        final Object second = audit.dynamic("PUBLIC.nullable_row", "own table again");
        if (recordId) {
            assertEquals(new CompositeKey(7L, 2L), second);
        } else {
            assertEquals(2L, ((EntityId) second).<Long> get("rowId"));
        }
    }

    @Test
    void customEntityExtractorDoesNotReadOtherTablesKeys() throws SQLException {
        execute("CREATE TABLE text_row(log_id VARCHAR GENERATED ALWAYS AS (name), name VARCHAR)");
        final CustomKeyDao custom = JdbcUtil.createDao(CustomKeyDao.class, ds);
        assertEquals(1001L, custom.ownTable("own"));
        assertEquals(1L, custom.log("other"));
        assertEquals(List.of(2L, 3L), custom.logBatch(List.of("other again", "another"), 1));
        assertEquals("a text key", custom.textKey("a text key"));
        assertEquals(1002L, custom.ownTable("own again"));
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void crossTableTextKeysAreNotReadThroughTheDaoIdType(final boolean recordId) throws SQLException {
        execute("CREATE TABLE text_row(log_id VARCHAR GENERATED ALWAYS AS (name), name VARCHAR)");
        assertEquals("a nonnumeric key", auditDao(recordId).textKey("a nonnumeric key"));
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 10 })
    void crossTableBatchKeyConversionFailsBeforeCommit(final int batchSize) throws SQLException {
        execute("CREATE TABLE text_row(log_id VARCHAR GENERATED ALWAYS AS (name), name VARCHAR)");
        final AuditInserts audit = auditDao(false);
        assertThrows(NumberFormatException.class, () -> audit.numericTextKeys(List.of("1", "not a number"), batchSize));
        assertEquals(0L, count("text_row"));
    }

    @Test
    void scalarInsertsSelectTheEntityIdInsteadOfAnotherGeneratedColumn() throws SQLException {
        assertEquals(1L, dao.named("named"));
        assertEquals(2L, dao.positional("positional"));
        assertEquals(3L, dao.optional("opt", "ional").get());
        assertEquals(4L, dao.quoted("quoted"));
        assertEquals("optional", dao.get(3L).get().getName());
    }

    @Test
    void insertDestinationIgnoresCtesAndQuotedInsertText() throws ReflectiveOperationException {
        // H2 does not accept a leading WITH before INSERT. Exercise destination resolution for that SQL shape directly,
        // including a data-changing CTE whose INSERT targets a different table from the outer statement.
        final Method target = DaoImpl.class.getDeclaredMethod("insertTargetTable", String.class);
        target.setAccessible(true);
        assertEquals("identity_row", target.invoke(null,
                "WITH seed AS (INSERT INTO other_row(name) VALUES ('INSERT INTO decoy') RETURNING name) INSERT INTO identity_row(name) SELECT name FROM seed"));
        assertEquals("identity_row", target.invoke(null, "(INSERT INTO identity_row(name) VALUES ('INTO other_row'))"));
    }

    @Test
    void namedAndPositionalBatchesKeepKeySelectionAcrossChunks() throws SQLException {
        assertEquals(List.of(1L, 2L), dao.positionalBatch(List.of("a", "b"), 1));
        assertEquals(List.of(3L, 4L), dao.namedBatch(List.of(Map.of("name", "c"), Map.of("name", "d")), 1));
    }

    @Test
    void runtimeTableFragmentsSelectKeysForTheirActualDestination() throws SQLException {
        assertEquals(1L, dao.dynamic("identity_row", "entity"));
        assertEquals(1L, dao.dynamic("other_row", "other"));
        assertEquals(2L, dao.dynamic("identity_row", "entity again"));
        assertEquals(3L, dao.dynamic("PUBLIC.identity_row", "qualified entity"));
        assertEquals(1L, dao.otherSchema("same name, different schema"));
        assertEquals(1L, dao.dynamic("\"identity_row\"", "same spelling, different quoted case"));
    }

    @Test
    void qualifiedEntityTableUsesItsDeclaredKeyColumns() throws SQLException {
        final QualifiedDao qualified = JdbcUtil.createDao(QualifiedDao.class, ds,
                JdbcUtil.DaoCreationOptions.builder().targetTableName("PUBLIC.identity_row").build());

        assertEquals(1L, qualified.insertName("qualified"));
        final KeyDao unqualifiedSql = JdbcUtil.createDao(KeyDao.class, ds,
                JdbcUtil.DaoCreationOptions.builder().targetTableName("PUBLIC.identity_row").build());
        assertEquals(2L, unqualifiedSql.named("unqualified SQL for the qualified DAO"));
    }
}
