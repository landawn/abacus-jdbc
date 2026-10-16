package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.annotation.Table;
import com.landawn.abacus.jdbc.annotation.Bind;
import com.landawn.abacus.jdbc.annotation.BindList;
import com.landawn.abacus.jdbc.annotation.CacheResult;
import com.landawn.abacus.jdbc.annotation.MappedByKey;
import com.landawn.abacus.jdbc.annotation.MergedById;
import com.landawn.abacus.jdbc.annotation.OutParameter;
import com.landawn.abacus.jdbc.annotation.Query;
import com.landawn.abacus.jdbc.annotation.RefreshCache;
import com.landawn.abacus.jdbc.annotation.SqlFragment;
import com.landawn.abacus.jdbc.annotation.SqlFragmentList;
import com.landawn.abacus.jdbc.dao.CrudDao;
import com.landawn.abacus.jdbc.dao.DaoBase;
import com.landawn.abacus.jdbc.dao.NonUpdateCrudDao;
import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.u.Nullable;
import com.landawn.abacus.util.u.Optional;

class DaoImplQueryValidationTest extends TestBase {

    @ParameterizedTest(name = "{0}: {2}")
    @MethodSource({ "invalidDeclarations", "invalidCacheDeclarations" })
    void createDao_invalidDeclaration_rejectsBeforePreparingSql(final Class<? extends DaoBase> daoType, final Class<? extends RuntimeException> exceptionType,
            final String message) throws SQLException {
        final DataSource dataSource = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("H2");
        when(metadata.getDatabaseProductVersion()).thenReturn("2");

        final RuntimeException failure = assertThrows(exceptionType, () -> {
            if (NonUpdateCrudDao.class.isAssignableFrom(daoType)) {
                JdbcUtil.createDao(daoType, dataSource, JdbcUtil.DaoCreationOptions.builder().cache(Jdbc.DaoCache.createByMap()).build());
            } else {
                JdbcUtil.createDao(daoType, dataSource);
            }
        });

        assertTrue(failure.getMessage().contains(message), failure::getMessage);
        verify(connection, never()).prepareStatement(anyString());
        verify(connection, never()).prepareCall(anyString());
    }

    private static Stream<Arguments> invalidDeclarations() {
        return Stream.of(Arguments.of(EmptySqlDao.class, IllegalArgumentException.class, "Sql script and id both"),
                Arguments.of(BothSqlSourcesDao.class, IllegalArgumentException.class, "Sql script and id both"),
                Arguments.of(InvalidSqlIdDao.class, IllegalArgumentException.class, "Invalid query identifiers"),
                Arguments.of(MissingSqlIdDao.class, IllegalArgumentException.class, "No predefined SQL found"),
                Arguments.of(MissingQueryDao.class, UnsupportedOperationException.class, "No SQL annotation found"),
                Arguments.of(JavaOptionalDao.class, UnsupportedOperationException.class, "Please use the OptionalXXX"),
                Arguments.of(StreamThrowsSqlDao.class, UnsupportedOperationException.class, "lazily evaluated"),
                Arguments.of(PositionalBindDao.class, UnsupportedOperationException.class, "@Bind parameters are defined for non-named query"),
                Arguments.of(MapperNotLastDao.class, UnsupportedOperationException.class, "RowMapper/ResultExtractor must be the last parameter"),
                Arguments.of(FilterNotLastDao.class, UnsupportedOperationException.class, "RowFilter/BiRowFilter must be the last parameter"),
                Arguments.of(MultipleFiltersDao.class, UnsupportedOperationException.class, "Only one RowFilter/BiRowFilter"),
                Arguments.of(FilterWithoutMapperDao.class, UnsupportedOperationException.class, "not supported without last parameter"),
                Arguments.of(MapperWrongOperationDao.class, UnsupportedOperationException.class, "not supported by queryOperation"),
                Arguments.of(ExtractorWrongOperationDao.class, UnsupportedOperationException.class, "not supported by queryOperation"),
                Arguments.of(ArrayFragmentDao.class, UnsupportedOperationException.class, "must be annotated with @SqlFragmentList or @BindList"),
                Arguments.of(ScalarFragmentListDao.class, UnsupportedOperationException.class, "must be Collection/Array"),
                Arguments.of(ScalarBindListDao.class, UnsupportedOperationException.class, "must be Collection/Array"),
                Arguments.of(NamedBindListDao.class, UnsupportedOperationException.class, "not supported for named or callable query"),
                Arguments.of(MissingFragmentDao.class, IllegalArgumentException.class, "are not found in sql annotated"),
                Arguments.of(SingleParameterCountDao.class, UnsupportedOperationException.class, "Don't set 'collectionAsSingleParameter'"),
                Arguments.of(OutWithoutProcedureDao.class, UnsupportedOperationException.class, "only supported by @Query methods with procedure=true"),
                Arguments.of(OutWithoutLocationDao.class, UnsupportedOperationException.class, "One of the attribute: (name, position)"),
                Arguments.of(OutWithBothLocationsDao.class, UnsupportedOperationException.class, "Only one of the attribute: (name, position)"),
                Arguments.of(MultipleResultsWithoutProcedureDao.class, UnsupportedOperationException.class,
                        "only supported by @Query methods with procedure=true"),
                Arguments.of(BatchScalarDao.class, UnsupportedOperationException.class, "the first parameter must be Collection"),
                Arguments.of(MappedUnknownKeyDao.class, IllegalArgumentException.class, "No method found by mapped key"),
                Arguments.of(MappedWrongOperationDao.class, IllegalArgumentException.class, "QueryOperation for method annotated by @MappedByKey"),
                Arguments.of(MappedWrongReturnDao.class, IllegalArgumentException.class, "annotated by @MappedByKey must be"),
                Arguments.of(MappedWrongKeyTypeDao.class, IllegalArgumentException.class, "annotated by @MappedByKey must be"),
                Arguments.of(MappedWrongValueTypeDao.class, IllegalArgumentException.class, "annotated by @MappedByKey must be"),
                Arguments.of(ConflictingMergeKeyDao.class, IllegalArgumentException.class, "must be same"),
                Arguments.of(MergedUnknownKeyDao.class, IllegalArgumentException.class, "No method found by merged id"),
                Arguments.of(MergedWrongOperationDao.class, IllegalArgumentException.class, "QueryOperation for method annotated by @MergedById"),
                Arguments.of(MergedWrongReturnDao.class, IllegalArgumentException.class, "annotated by @MergedById must be"),
                Arguments.of(NullableFindFirstDao.class, UnsupportedOperationException.class, "not supported by the specified queryOperation"),
                Arguments.of(StreamWrongOperationDao.class, UnsupportedOperationException.class, "not supported by the specified queryOperation"),
                Arguments.of(DatasetWrongOperationDao.class, UnsupportedOperationException.class, "not supported by the specified queryOperation"),
                Arguments.of(OptionalWrongOperationDao.class, UnsupportedOperationException.class, "not supported by the specified queryOperation"),
                Arguments.of(NullableWrongOperationDao.class, UnsupportedOperationException.class, "not supported by the specified queryOperation"));
    }

    private static Stream<Arguments> invalidCacheDeclarations() {
        return Stream.of(Arguments.of(NegativeCacheLifetimeDao.class, UnsupportedOperationException.class, "maxLiveTimeMillis"),
                Arguments.of(NegativeCacheIdleDao.class, UnsupportedOperationException.class, "maxIdleTimeMillis"),
                Arguments.of(NegativeCacheMinSizeDao.class, UnsupportedOperationException.class, "minSize"),
                Arguments.of(NegativeCacheMaxSizeDao.class, UnsupportedOperationException.class, "maxSize"),
                Arguments.of(ReversedCacheSizeDao.class, UnsupportedOperationException.class, "minSize"),
                Arguments.of(CachedStreamDao.class, UnsupportedOperationException.class, "not cacheable"),
                Arguments.of(RefreshWithoutCacheDao.class, UnsupportedOperationException.class, "none of them is annotated with @CacheResult"),
                // op = update/largeUpdate on a plain SELECT used to be ignored: the method ran as a query and returned a column value.
                Arguments.of(UpdateOperationOnSelectDao.class, UnsupportedOperationException.class,
                        "The SELECT statement of method: " + UpdateOperationOnSelectDao.class.getName().replace('$', '.')
                                + ".count is not supported by the specified queryOperation: update"),
                Arguments.of(LargeUpdateOperationOnSelectDao.class, UnsupportedOperationException.class,
                        "is not supported by the specified queryOperation: largeUpdate"),
                Arguments.of(UpdateOperationOnReadOnlyCteDao.class, UnsupportedOperationException.class,
                        "is not supported by the specified queryOperation: update"),
                Arguments.of(UpdateOperationOnSelectForUpdateDao.class, UnsupportedOperationException.class,
                        "is not supported by the specified queryOperation: update"));
    }

    // A SELECT that may write data keeps running as a query whatever its declared operation, so it is not rejected:
    // a data-modifying CTE, a DB2/H2 data-change delta table (which SqlParser alone does not recognize) and SELECT ... INTO.
    @ParameterizedTest(name = "{0}")
    @MethodSource("updateOperationOnSelectsThatMayWrite")
    void createDao_updateOperationOnSelectThatMayWrite_isNotRejected(final Class<? extends DaoBase> daoType) throws SQLException {
        final DataSource dataSource = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("H2");
        when(metadata.getDatabaseProductVersion()).thenReturn("2");

        assertDoesNotThrow(() -> JdbcUtil.createDao(daoType, dataSource));
    }

    private static Stream<Arguments> updateOperationOnSelectsThatMayWrite() {
        return Stream.of(Arguments.of(UpdateOperationOnDataModifyingCteDao.class), Arguments.of(UpdateOperationOnDeltaTableDao.class),
                Arguments.of(UpdateOperationOnSelectIntoDao.class));
    }

    interface UpdateOperationOnSelectDao extends CrudDao<Record, Long, UpdateOperationOnSelectDao> {
        @Query(value = "SELECT COUNT(*) FROM validation_record", op = QueryOperation.update)
        int count() throws SQLException;
    }

    interface LargeUpdateOperationOnSelectDao extends CrudDao<Record, Long, LargeUpdateOperationOnSelectDao> {
        @Query(value = "SELECT COUNT(*) FROM validation_record", op = QueryOperation.largeUpdate)
        long count() throws SQLException;
    }

    interface UpdateOperationOnReadOnlyCteDao extends CrudDao<Record, Long, UpdateOperationOnReadOnlyCteDao> {
        @Query(value = "WITH t AS (SELECT id FROM validation_record) SELECT COUNT(*) FROM t", op = QueryOperation.update)
        int count() throws SQLException;
    }

    interface UpdateOperationOnSelectForUpdateDao extends CrudDao<Record, Long, UpdateOperationOnSelectForUpdateDao> {
        @Query(value = "SELECT id FROM validation_record WHERE id = ? FOR UPDATE", op = QueryOperation.update)
        int lock(long id) throws SQLException;
    }

    interface UpdateOperationOnDataModifyingCteDao extends CrudDao<Record, Long, UpdateOperationOnDataModifyingCteDao> {
        @Query(value = "WITH changed AS (UPDATE validation_record SET name = 'x' RETURNING id) SELECT COUNT(*) FROM changed", op = QueryOperation.update)
        int touch() throws SQLException;
    }

    interface UpdateOperationOnDeltaTableDao extends CrudDao<Record, Long, UpdateOperationOnDeltaTableDao> {
        @Query(value = "SELECT COUNT(*) FROM FINAL TABLE (UPDATE validation_record SET name = 'x')", op = QueryOperation.update)
        int touch() throws SQLException;
    }

    interface UpdateOperationOnSelectIntoDao extends CrudDao<Record, Long, UpdateOperationOnSelectIntoDao> {
        @Query(value = "SELECT id INTO validation_archive FROM validation_record", op = QueryOperation.update)
        int archive() throws SQLException;
    }

    interface NegativeCacheLifetimeDao extends NonUpdateCrudDao<Record, Long, NegativeCacheLifetimeDao> {
        @CacheResult(enabled = true, maxLiveTimeMillis = -1)
        default String findValue() {
            return "value";
        }
    }

    interface NegativeCacheIdleDao extends NonUpdateCrudDao<Record, Long, NegativeCacheIdleDao> {
        @CacheResult(enabled = true, maxIdleTimeMillis = -1)
        default String findValue() {
            return "value";
        }
    }

    interface NegativeCacheMinSizeDao extends NonUpdateCrudDao<Record, Long, NegativeCacheMinSizeDao> {
        @CacheResult(enabled = true, minSize = -1)
        default List<String> findValues() {
            return List.of();
        }
    }

    interface NegativeCacheMaxSizeDao extends NonUpdateCrudDao<Record, Long, NegativeCacheMaxSizeDao> {
        @CacheResult(enabled = true, maxSize = -1)
        default List<String> findValues() {
            return List.of();
        }
    }

    interface ReversedCacheSizeDao extends NonUpdateCrudDao<Record, Long, ReversedCacheSizeDao> {
        @CacheResult(enabled = true, minSize = 2, maxSize = 1)
        default List<String> findValues() {
            return List.of();
        }
    }

    interface CachedStreamDao extends NonUpdateCrudDao<Record, Long, CachedStreamDao> {
        @CacheResult(enabled = true)
        default com.landawn.abacus.util.stream.Stream<String> findValues() {
            return com.landawn.abacus.util.stream.Stream.empty();
        }
    }

    interface RefreshWithoutCacheDao extends NonUpdateCrudDao<Record, Long, RefreshWithoutCacheDao> {
        @RefreshCache
        default int updateValue() {
            return 1;
        }
    }

    interface EmptySqlDao extends CrudDao<Record, Long, EmptySqlDao> {
        @Query
        int read() throws SQLException;
    }

    interface BothSqlSourcesDao extends CrudDao<Record, Long, BothSqlSourcesDao> {
        @Query(value = "SELECT 1", id = "read")
        int read() throws SQLException;
    }

    interface InvalidSqlIdDao extends CrudDao<Record, Long, InvalidSqlIdDao> {
        @Query(id = "invalid-id")
        int read() throws SQLException;
    }

    interface MissingSqlIdDao extends CrudDao<Record, Long, MissingSqlIdDao> {
        @Query(id = "missingSql")
        int read() throws SQLException;
    }

    interface MissingQueryDao extends CrudDao<Record, Long, MissingQueryDao> {
        int read() throws SQLException;
    }

    interface JavaOptionalDao extends CrudDao<Record, Long, JavaOptionalDao> {
        @Query("SELECT id FROM validation_record")
        java.util.Optional<Long> read() throws SQLException;
    }

    interface StreamThrowsSqlDao extends CrudDao<Record, Long, StreamThrowsSqlDao> {
        @Query("SELECT id FROM validation_record")
        com.landawn.abacus.util.stream.Stream<Long> read() throws SQLException;
    }

    interface PositionalBindDao extends CrudDao<Record, Long, PositionalBindDao> {
        @Query("SELECT id FROM validation_record WHERE id = ?")
        Long read(@Bind("id") long id) throws SQLException;
    }

    interface MapperNotLastDao extends CrudDao<Record, Long, MapperNotLastDao> {
        @Query("SELECT id FROM validation_record WHERE id = ?")
        List<Long> read(Jdbc.RowMapper<Long> mapper, long id) throws SQLException;
    }

    interface FilterNotLastDao extends CrudDao<Record, Long, FilterNotLastDao> {
        @Query("SELECT id FROM validation_record WHERE id = ?")
        List<Long> read(Jdbc.RowFilter filter, long id) throws SQLException;
    }

    interface MultipleFiltersDao extends CrudDao<Record, Long, MultipleFiltersDao> {
        @Query("SELECT id FROM validation_record")
        List<Long> read(Jdbc.RowFilter first, Jdbc.RowFilter second) throws SQLException;
    }

    interface FilterWithoutMapperDao extends CrudDao<Record, Long, FilterWithoutMapperDao> {
        @Query("SELECT id FROM validation_record")
        List<Long> read(Jdbc.RowFilter filter) throws SQLException;
    }

    interface MapperWrongOperationDao extends CrudDao<Record, Long, MapperWrongOperationDao> {
        @Query(value = "SELECT id FROM validation_record", op = QueryOperation.query)
        Dataset read(Jdbc.RowMapper<Long> mapper) throws SQLException;
    }

    interface ExtractorWrongOperationDao extends CrudDao<Record, Long, ExtractorWrongOperationDao> {
        @Query(value = "SELECT id FROM validation_record", op = QueryOperation.list)
        List<Long> read(Jdbc.ResultExtractor<List<Long>> extractor) throws SQLException;
    }

    interface ArrayFragmentDao extends CrudDao<Record, Long, ArrayFragmentDao> {
        @Query("SELECT {columns} FROM validation_record")
        List<Long> read(@SqlFragment("columns") String[] columns) throws SQLException;
    }

    interface ScalarFragmentListDao extends CrudDao<Record, Long, ScalarFragmentListDao> {
        @Query("SELECT {columns} FROM validation_record")
        List<Long> read(@SqlFragmentList("columns") String columns) throws SQLException;
    }

    interface ScalarBindListDao extends CrudDao<Record, Long, ScalarBindListDao> {
        @Query("SELECT id FROM validation_record WHERE id IN ({ids})")
        List<Long> read(@BindList("ids") long ids) throws SQLException;
    }

    interface NamedBindListDao extends CrudDao<Record, Long, NamedBindListDao> {
        @Query("SELECT id FROM validation_record WHERE id > :id AND id IN ({ids})")
        List<Long> read(@Bind("id") long id, @BindList("ids") List<Long> ids) throws SQLException;
    }

    interface MissingFragmentDao extends CrudDao<Record, Long, MissingFragmentDao> {
        @Query("SELECT id FROM validation_record")
        List<Long> read(@SqlFragment("missing") String fragment) throws SQLException;
    }

    interface SingleParameterCountDao extends CrudDao<Record, Long, SingleParameterCountDao> {
        @Query(value = "SELECT id FROM validation_record WHERE id = ? OR id = ?", collectionAsSingleParameter = true)
        List<Long> read(long first, long second) throws SQLException;
    }

    interface OutWithoutProcedureDao extends CrudDao<Record, Long, OutWithoutProcedureDao> {
        @Query("SELECT id FROM validation_record")
        @OutParameter(position = 1, sqlType = Types.BIGINT)
        List<Long> read() throws SQLException;
    }

    interface OutWithoutLocationDao extends CrudDao<Record, Long, OutWithoutLocationDao> {
        @Query(value = "{call read_id(?)}", procedure = true)
        @OutParameter(sqlType = Types.BIGINT)
        int read() throws SQLException;
    }

    interface OutWithBothLocationsDao extends CrudDao<Record, Long, OutWithBothLocationsDao> {
        @Query(value = "{call read_id(?)}", procedure = true)
        @OutParameter(name = "id", position = 1, sqlType = Types.BIGINT)
        int read() throws SQLException;
    }

    interface MultipleResultsWithoutProcedureDao extends CrudDao<Record, Long, MultipleResultsWithoutProcedureDao> {
        @Query(value = "SELECT id FROM validation_record", op = QueryOperation.listAll)
        List<List<Long>> read() throws SQLException;
    }

    interface BatchScalarDao extends CrudDao<Record, Long, BatchScalarDao> {
        @Query(value = "UPDATE validation_record SET name = ?", batch = true)
        int updateName(String name) throws SQLException;
    }

    interface MappedUnknownKeyDao extends CrudDao<Record, Long, MappedUnknownKeyDao> {
        @Query("SELECT * FROM validation_record")
        @MappedByKey("missing")
        Map<Long, Record> read() throws SQLException;
    }

    interface MappedWrongOperationDao extends CrudDao<Record, Long, MappedWrongOperationDao> {
        @Query(value = "SELECT * FROM validation_record", op = QueryOperation.findFirst)
        @MappedByKey("id")
        Map<Long, Record> read() throws SQLException;
    }

    interface MappedWrongReturnDao extends CrudDao<Record, Long, MappedWrongReturnDao> {
        @Query("SELECT * FROM validation_record")
        @MappedByKey("id")
        List<Record> read() throws SQLException;
    }

    interface MappedWrongKeyTypeDao extends CrudDao<Record, Long, MappedWrongKeyTypeDao> {
        @Query("SELECT * FROM validation_record")
        @MappedByKey("id")
        Map<String, Record> read() throws SQLException;
    }

    interface MappedWrongValueTypeDao extends CrudDao<Record, Long, MappedWrongValueTypeDao> {
        @Query("SELECT * FROM validation_record")
        @MappedByKey("id")
        Map<Long, String> read() throws SQLException;
    }

    interface ConflictingMergeKeyDao extends CrudDao<Record, Long, ConflictingMergeKeyDao> {
        @Query("SELECT * FROM validation_record")
        @MappedByKey("id")
        @MergedById("name")
        Map<Long, Record> read() throws SQLException;
    }

    interface MergedUnknownKeyDao extends CrudDao<Record, Long, MergedUnknownKeyDao> {
        @Query("SELECT * FROM validation_record")
        @MergedById("missing")
        List<Record> read() throws SQLException;
    }

    interface MergedWrongOperationDao extends CrudDao<Record, Long, MergedWrongOperationDao> {
        @Query(value = "SELECT * FROM validation_record", op = QueryOperation.query)
        @MergedById("id")
        Dataset read() throws SQLException;
    }

    interface MergedWrongReturnDao extends CrudDao<Record, Long, MergedWrongReturnDao> {
        @Query("SELECT * FROM validation_record")
        @MergedById("id")
        List<String> read() throws SQLException;
    }

    interface NullableFindFirstDao extends CrudDao<Record, Long, NullableFindFirstDao> {
        @Query(value = "SELECT id FROM validation_record", op = QueryOperation.findFirst)
        Nullable<Long> read() throws SQLException;
    }

    interface StreamWrongOperationDao extends CrudDao<Record, Long, StreamWrongOperationDao> {
        @Query(value = "SELECT id FROM validation_record", op = QueryOperation.queryForSingle)
        com.landawn.abacus.util.stream.Stream<Long> read();
    }

    interface DatasetWrongOperationDao extends CrudDao<Record, Long, DatasetWrongOperationDao> {
        @Query(value = "SELECT id FROM validation_record", op = QueryOperation.queryForSingle)
        Dataset read() throws SQLException;
    }

    interface OptionalWrongOperationDao extends CrudDao<Record, Long, OptionalWrongOperationDao> {
        @Query(value = "SELECT id FROM validation_record", op = QueryOperation.update)
        Optional<Long> read() throws SQLException;
    }

    interface NullableWrongOperationDao extends CrudDao<Record, Long, NullableWrongOperationDao> {
        @Query(value = "SELECT id FROM validation_record", op = QueryOperation.update)
        Nullable<Long> read() throws SQLException;
    }

    @Table("validation_record")
    public static class Record {
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
