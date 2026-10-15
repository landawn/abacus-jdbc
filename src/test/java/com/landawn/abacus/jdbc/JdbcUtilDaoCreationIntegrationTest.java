package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.annotation.Table;
import com.landawn.abacus.jdbc.JdbcUtil.DaoCreationOptions;
import com.landawn.abacus.jdbc.annotation.Bind;
import com.landawn.abacus.jdbc.annotation.CacheResult;
import com.landawn.abacus.jdbc.annotation.Query;
import com.landawn.abacus.jdbc.dao.CrudDao;
import com.landawn.abacus.jdbc.dao.NonUpdateCrudDao;
import com.landawn.abacus.query.Dsl;
import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.query.SqlMapper;

@Tag("integration")
class JdbcUtilDaoCreationIntegrationTest extends TestBase {
    private JdbcDataSource dataSource;
    private Connection keeper;

    @BeforeEach
    void createDatabase() throws SQLException {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:dao_options_" + System.nanoTime());
        keeper = dataSource.getConnection();
        JdbcUtil.executeUpdate(keeper, "CREATE TABLE option_record(id BIGINT PRIMARY KEY, name VARCHAR(40))");
        JdbcUtil.executeUpdate(keeper, "CREATE TABLE renamed_record(id BIGINT PRIMARY KEY, name VARCHAR(40))");
        JdbcUtil.executeUpdate(keeper, "INSERT INTO option_record VALUES(1, 'default')");
        JdbcUtil.executeUpdate(keeper, "INSERT INTO renamed_record VALUES(2, 'custom')");
    }

    @AfterEach
    void closeDatabase() throws SQLException {
        if (keeper != null) {
            keeper.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void nullAndEmptyOptionsUseEntityTableAndDefaultExecutor(final boolean nullOptions) throws SQLException {
        final DaoCreationOptions options = nullOptions ? null : DaoCreationOptions.builder().build();
        final RecordDao dao = JdbcUtil.createDao(RecordDao.class, dataSource, options);

        assertSame(dataSource, dao.dataSource());
        assertEquals("option_record", dao.targetTableName());
        assertSame(Record.class, dao.targetEntityClass());
        assertNotNull(dao.executor());
        assertEquals("default", dao.get(1L).get().getName());
        assertFalse(dao.get(2L).isPresent());
    }

    @Test
    void suppliedOptionsControlTableSqlMapperCacheAndExecutor() throws Exception {
        final SqlMapper mapper = new SqlMapper();
        mapper.add("findName", ParsedSql.parse("SELECT name FROM renamed_record WHERE id = :id"), Map.of());
        final Jdbc.DaoCache cache = Jdbc.DaoCache.createByMap();
        final AtomicInteger executions = new AtomicInteger();
        final Executor executor = task -> {
            executions.incrementAndGet();
            task.run();
        };
        final DaoCreationOptions options = DaoCreationOptions.builder()
                .targetTableName("renamed_record")
                .dsl(Dsl.PSC)
                .sqlMapper(mapper)
                .cache(cache)
                .executor(executor)
                .build();
        final CachedRecordDao dao = JdbcUtil.createDao(CachedRecordDao.class, dataSource, options);

        assertEquals("renamed_record", dao.targetTableName());
        assertSame(executor, dao.executor());
        assertEquals(mapper.get("findName"), dao.sqlMapper().get("findName"));
        assertEquals("custom", dao.get(2L).get().getName());
        assertEquals("custom", dao.findName(2L));
        JdbcUtil.executeUpdate(keeper, "UPDATE renamed_record SET name = 'changed' WHERE id = 2");
        assertEquals("custom", dao.findName(2L), "The supplied cache must serve the original result");
        cache.clear();
        assertEquals("changed", dao.findName(2L));
        assertEquals(1L, dao.callAsync(d -> d.count(com.landawn.abacus.query.Filters.eq("id", 2L))).get().longValue());
        assertEquals(1, executions.get());
        assertFalse(dao.get(1L).isPresent());
    }

    @Test
    void tableExistsDataSourceOverloadTracksCreationAndDrop() throws SQLException {
        assertTrue(JdbcUtil.tableExists(dataSource, "option_record"));
        assertFalse(JdbcUtil.tableExists(dataSource, "missing_record"));
        assertTrue(JdbcUtil.dropTableIfExists(keeper, "option_record"));
        assertFalse(JdbcUtil.tableExists(dataSource, "option_record"));
        assertFalse(keeper.isClosed());
    }

    public interface RecordDao extends CrudDao<Record, Long, RecordDao> {
    }

    public interface CachedRecordDao extends NonUpdateCrudDao<Record, Long, CachedRecordDao> {
        @CacheResult(enabled = true)
        @Query("findName")
        String findName(@Bind("id") long id) throws SQLException;
    }

    @Table("option_record")
    @lombok.Data
    public static class Record {
        @Id
        private Long id;
        private String name;
    }
}
