package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
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
            stmt.execute("CREATE TABLE other_row(log_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, name VARCHAR)");
            stmt.execute("CREATE SCHEMA other_schema");
            stmt.execute("CREATE TABLE other_schema.identity_row(log_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, name VARCHAR)");
            stmt.execute("CREATE TABLE \"identity_row\"(log_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, name VARCHAR)");
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
    }

    public interface QualifiedDao extends CrudDao<IdentityRow, Long, QualifiedDao> {
        @Query("INSERT INTO \"PUBLIC\" . \"IDENTITY_ROW\"(name) VALUES (?)")
        Long insertName(String name) throws SQLException;
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
