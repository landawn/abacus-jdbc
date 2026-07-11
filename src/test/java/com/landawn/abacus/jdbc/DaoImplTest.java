package com.landawn.abacus.jdbc;

import static com.landawn.abacus.query.Dsl.PSC;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.jdbc.annotation.Bind;
import com.landawn.abacus.jdbc.annotation.CacheResult;
import com.landawn.abacus.jdbc.annotation.MappedByKey;
import com.landawn.abacus.jdbc.annotation.MergedById;
import com.landawn.abacus.jdbc.annotation.NonDBOperation;
import com.landawn.abacus.jdbc.annotation.OutParameter;
import com.landawn.abacus.jdbc.annotation.Query;
import com.landawn.abacus.jdbc.annotation.RefreshCache;
import com.landawn.abacus.jdbc.annotation.SqlSource;
import com.landawn.abacus.jdbc.annotation.Transactional;
import com.landawn.abacus.jdbc.dao.CrudDao;
import com.landawn.abacus.jdbc.dao.Dao;
import com.landawn.abacus.jdbc.dao.DaoBase;
import com.landawn.abacus.query.Filters;
import com.landawn.abacus.query.condition.Condition;
import com.landawn.abacus.query.condition.Criteria;
import com.landawn.abacus.query.condition.Expression;
import com.landawn.abacus.query.condition.Limit;
import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.ImmutableList;
import com.landawn.abacus.util.RowDataset;
import com.landawn.abacus.util.Throwables;
import com.landawn.abacus.util.Tuple.Tuple3;
import com.landawn.abacus.util.u;
import com.landawn.abacus.util.u.Optional;

@Tag("2025")
public class DaoImplTest extends TestBase {

    static class TestEntity {
        private long id;
        private String name;

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    static class IdOnlyEntity {
        @Id
        private long id;

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }
    }

    interface IdOnlyCrudDao extends CrudDao<IdOnlyEntity, Long, IdOnlyCrudDao> {
    }

    interface MergedDao {
        @Query("select * from test")
        @MergedById("id")
        Optional<TestEntity> findMerged();
    }

    interface ProcedureDao {
        @Query(value = "call test_proc(?, ?)", isProcedure = true)
        void callProc(@Bind("p1") String first, String second);
    }

    interface EmptyBindProcedureDao {
        @Query(value = "call test_proc(?, ?)", isProcedure = true)
        void callProc(@Bind("p1") String first, @Bind String second);
    }

    interface BatchBindListDao extends Dao<TestEntity, BatchBindListDao> {
        @Query(value = "UPDATE test SET status = 1 WHERE id IN ({ids})", isBatch = true)
        int updateBatch(@com.landawn.abacus.jdbc.annotation.BindList("ids") Collection<Long> ids) throws SQLException;
    }

    @SqlSource
    interface DefaultSqlSourceDao extends Dao<TestEntity, DefaultSqlSourceDao> {
        @Query("select * from test")
        List<TestEntity> list() throws SQLException;
    }

    interface AmbiguousOutParameterDao extends Dao<TestEntity, AmbiguousOutParameterDao> {
        @Query(value = "{call test_proc(?)}", isProcedure = true, op = OP.executeAndGetOutParameters)
        @OutParameter(name = "out", position = 1, sqlType = Types.INTEGER)
        Jdbc.OutParamResult call();
    }

    interface ZeroPositionOutParameterDao extends Dao<TestEntity, ZeroPositionOutParameterDao> {
        @Query(value = "{call test_proc(?)}", isProcedure = true, op = OP.executeAndGetOutParameters)
        @OutParameter(position = 0, sqlType = Types.INTEGER)
        Jdbc.OutParamResult call() throws SQLException;
    }

    interface IncompatibleRowMapperListDao {
        @Query("select * from test")
        List<String> list(Jdbc.RowMapper<TestEntity> mapper);
    }

    // Regression: a stored-procedure DAO method declared as Stream<Dataset> with op=OP.streamAll
    // used to fail at proxy creation time because the Stream-return guard rejected anything other
    // than OP.stream / OP.DEFAULT — even though the OP.streamAll dispatch branch (procedure path)
    // was explicitly written to handle it.
    interface StreamAllProcedureDao extends Dao<TestEntity, StreamAllProcedureDao> {
        @Query(value = "call test_proc()", isProcedure = true, op = OP.streamAll)
        com.landawn.abacus.util.stream.Stream<Dataset> streamAll();
    }

    interface PrimitiveExtractorDao {
        @Query("select count(*) from test")
        int count(Jdbc.ResultExtractor<Integer> extractor);
    }

    interface Marker {
    }

    interface BaseDao<T> extends Dao<T, BaseDao<T>> {
    }

    interface ReorderedGenericDao extends Marker, BaseDao<TestEntity> {
    }

    interface InvalidRowFilterPositionDao extends Dao<TestEntity, InvalidRowFilterPositionDao> {
        @Query("select * from test")
        List<TestEntity> findByFilter(Jdbc.RowFilter rowFilter);
    }

    interface QueryClassifierDao {
        List<TestEntity> getEntities();

        List<TestEntity> selectWithExtractor(Jdbc.ResultExtractor<List<TestEntity>> extractor);

        String existsAsString();

        boolean existsWithMapper(Jdbc.RowMapper<TestEntity> mapper);

        boolean notifyExists();
    }

    interface RollbackMaskDao extends Dao<TestEntity, RollbackMaskDao> {
        IllegalStateException PRIMARY_FAILURE = new IllegalStateException("primary DAO failure");

        @Transactional
        default void failInTransaction() {
            throw PRIMARY_FAILURE;
        }
    }

    // Interface with a method returning List<T> (TypeVariable) — triggers the ClassCastException bug before fix.
    interface GenericListDao<T> {
        List<T> listAll();

        // List<? extends TestEntity> — WildcardType argument — also triggered the bug.
        List<? extends TestEntity> listWildcard();
    }

    // Interface where the ResultExtractor parameter carries a TypeVariable type argument (e.g., ResultExtractor<T>).
    // Before the fix, createQueryFunctionByMethod threw ClassCastException trying to cast TypeVariable to ParameterizedType.
    interface GenericExtractorDao<T> {
        List<T> listWithExtractor(Jdbc.ResultExtractor<T> extractor);
    }

    // isFindFirst/isFindOnlyOne/isQueryForUnique test interfaces
    interface FindFirstOpDao {
        List<TestEntity> anyName();
    }

    interface FindFirstPrefixDao {
        List<TestEntity> findFirstByStatus(String status);
    }

    interface FindOnlyOneMethodDao {
        List<TestEntity> findOnlyOneById(long id);
    }

    interface QueryForUniqueMethodDao {
        List<TestEntity> queryForUniqueByName(String name);
    }

    interface AnyOpDaoForUnique {
        List<TestEntity> anyName();
    }

    // isListQuery branch test interfaces
    interface ListAllOpDao {
        java.util.Set<TestEntity> getEntities();
    }

    interface MappedByKeyDao {
        @MappedByKey(keyName = "id")
        java.util.Map<Long, TestEntity> findMapped();
    }

    interface UpdateOpDao {
        int updateCount();
    }

    // isExistsQuery branch test interfaces
    interface ExistsOpDao {
        boolean checkExists();
    }

    // getFirstReturnEleType / getSecondReturnEleType / getFirstReturnEleEleType test interfaces
    interface FirstReturnEleDao {
        java.util.List<TestEntity> getEntities();
    }

    interface SecondReturnEleDao {
        java.util.Map<String, TestEntity> getMapped();
    }

    interface FirstReturnEleEleDao {
        java.util.List<java.util.List<TestEntity>> getNested();
    }

    interface SecondReturnEleEleDao {
        java.util.List<java.util.Set<TestEntity>> getNestedSets();
    }

    // Non-parameterized return type for getFirstReturnEleType null path
    interface PlainReturnDao {
        TestEntity getEntity();
    }

    // Default method for createMethodHandle test
    interface DefaultMethodDao {
        default String greeting() {
            return "hello";
        }
    }

    interface NoArgDefaultMethodDao extends Dao<TestEntity, NoArgDefaultMethodDao> {
        @NonDBOperation
        default String greeting() {
            return "hello";
        }
    }

    interface MultipleSqlDefaultMethodDao extends Dao<TestEntity, MultipleSqlDefaultMethodDao> {
        @Query({ "SELECT 1", "SELECT 2" })
        @NonDBOperation
        default String consumeAndMutateFirstSql(final String... sqls) {
            final String firstSql = sqls[0];
            sqls[0] = "mutated";
            return firstSql;
        }
    }

    static final class StubQuery extends AbstractQuery<PreparedStatement, StubQuery> {
        private final Dataset dataset;

        StubQuery(Dataset dataset) {
            super(mock(PreparedStatement.class));
            this.dataset = dataset;
        }

        @Override
        public <R> R query(Jdbc.ResultExtractor<? extends R> resultExtractor) {
            return (R) dataset;
        }
    }

    @Test
    void testMergedByIdReturnsAbacusOptional() throws Exception {
        Method daoMethod = MergedDao.class.getMethod("findMerged");
        Method factory = DaoImpl.class.getDeclaredMethod("createQueryFunctionByMethod", Class.class, Method.class, String.class, List.class, Map.class,
                boolean.class, boolean.class, boolean.class, OP.class, boolean.class, String.class);
        factory.setAccessible(true);

        Dataset dataset = new RowDataset(ImmutableList.of("id", "name"), ImmutableList.of(ImmutableList.of(1L), ImmutableList.of("name")));

        @SuppressWarnings("unchecked")
        Throwables.BiFunction<AbstractQuery, Object[], Object, SQLException> func = (Throwables.BiFunction<AbstractQuery, Object[], Object, SQLException>) factory
                .invoke(null, TestEntity.class, daoMethod, null, List.of("id"), null, true, false, false, OP.DEFAULT, false, "MergedDao.findMerged");

        Object result = func.apply(new StubQuery(dataset), new Object[0]);

        assertTrue(result instanceof Optional);

        TestEntity resultEntity = (TestEntity) ((Optional<?>) result).orElseNull();
        assertNotNull(resultEntity);
        assertEquals(1L, resultEntity.getId());
        assertEquals("name", resultEntity.getName());
    }

    @Test
    public void testMergedByIdReturnsEmptyOptionalForEmptyDataset() throws Exception {
        Method daoMethod = MergedDao.class.getMethod("findMerged");
        Method factory = DaoImpl.class.getDeclaredMethod("createQueryFunctionByMethod", Class.class, Method.class, String.class, List.class, Map.class,
                boolean.class, boolean.class, boolean.class, OP.class, boolean.class, String.class);
        factory.setAccessible(true);

        Dataset dataset = new RowDataset(List.of("id", "name"), List.of(List.of(), List.of()));

        @SuppressWarnings("unchecked")
        Throwables.BiFunction<AbstractQuery, Object[], Object, SQLException> func = (Throwables.BiFunction<AbstractQuery, Object[], Object, SQLException>) factory
                .invoke(null, TestEntity.class, daoMethod, null, List.of("id"), null, true, false, false, OP.DEFAULT, false, "MergedDao.findMergedEmpty");

        Object result = func.apply(new StubQuery(dataset), new Object[0]);

        assertTrue(result instanceof Optional);
        assertTrue(((Optional<?>) result).isEmpty());
    }

    @Test
    void testProcedureBindRequiresAllParamsBound() throws Exception {
        Method daoMethod = ProcedureDao.class.getMethod("callProc", String.class, String.class);
        DaoImpl.QueryInfo queryInfo = new DaoImpl.QueryInfo("call test_proc(?, ?)", null, 0, 0, false, 0, OP.DEFAULT, false, false, false, false, true, false);

        Method factory = DaoImpl.class.getDeclaredMethod("createParametersSetter", DaoImpl.QueryInfo.class, String.class, Method.class, Class[].class,
                int.class, int.class, int[].class, boolean[].class, int.class);
        factory.setAccessible(true);

        int[] stmtParamIndexes = new int[] { 0, 1 };
        boolean[] bindListParamFlags = new boolean[] { false, false };

        InvocationTargetException ex = assertThrows(InvocationTargetException.class, () -> factory.invoke(null, queryInfo, "ProcedureDao.callProc", daoMethod,
                daoMethod.getParameterTypes(), 2, 2, stmtParamIndexes, bindListParamFlags, 2));

        assertTrue(ex.getCause() instanceof UnsupportedOperationException);
        assertTrue(ex.getCause().getMessage().contains("either all procedure parameters must be bound"));
    }

    // BUG FIX: a bare @Bind (empty value) among named procedure parameters used to pass DAO
    // initialization and then bind the empty string at call time, failing with an obscure driver
    // error. Bind's documented contract declares the combination unsupported; reject it eagerly
    // like the sibling all-or-none check above.
    @Test
    @Tag("2025")
    void testProcedureBindRejectsEmptyBindNameAmongNamedOnes() throws Exception {
        Method daoMethod = EmptyBindProcedureDao.class.getMethod("callProc", String.class, String.class);
        DaoImpl.QueryInfo queryInfo = new DaoImpl.QueryInfo("call test_proc(?, ?)", null, 0, 0, false, 0, OP.DEFAULT, false, false, false, false, true, false);

        Method factory = DaoImpl.class.getDeclaredMethod("createParametersSetter", DaoImpl.QueryInfo.class, String.class, Method.class, Class[].class,
                int.class, int.class, int[].class, boolean[].class, int.class);
        factory.setAccessible(true);

        int[] stmtParamIndexes = new int[] { 0, 1 };
        boolean[] bindListParamFlags = new boolean[] { false, false };

        InvocationTargetException ex = assertThrows(InvocationTargetException.class, () -> factory.invoke(null, queryInfo, "EmptyBindProcedureDao.callProc",
                daoMethod, daoMethod.getParameterTypes(), 2, 2, stmtParamIndexes, bindListParamFlags, 2));

        assertTrue(ex.getCause() instanceof UnsupportedOperationException);
        assertTrue(ex.getCause().getMessage().contains("non-empty"));
    }

    // BUG FIX: a batch @Query whose Collection parameter also carries @BindList used to pass DAO
    // initialization even though the collection cannot simultaneously supply the batch rows and be
    // expanded into IN-clause placeholders — the first call failed with a confusing bind-shape error.
    // The combination must be rejected at creation time.
    @Test
    @Tag("2025")
    void testBatchWithBindListRejectedAtCreation() throws SQLException {
        final DataSource dataSource = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        Mockito.when(dataSource.getConnection()).thenReturn(connection);
        Mockito.when(connection.getMetaData()).thenReturn(metadata);
        Mockito.when(metadata.getDatabaseProductName()).thenReturn("H2");
        Mockito.when(metadata.getDatabaseProductVersion()).thenReturn("2");

        final UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
                () -> JdbcUtil.createDao(BatchBindListDao.class, dataSource));

        assertTrue(thrown.getMessage().contains("@BindList"));
    }

    @Test
    void testResultExtractorPrimitiveReturnTypeCompatibility() throws Exception {
        Method daoMethod = PrimitiveExtractorDao.class.getMethod("count", Jdbc.ResultExtractor.class);
        Method factory = DaoImpl.class.getDeclaredMethod("createQueryFunctionByMethod", Class.class, Method.class, String.class, List.class, Map.class,
                boolean.class, boolean.class, boolean.class, OP.class, boolean.class, String.class);
        factory.setAccessible(true);

        @SuppressWarnings("unchecked")
        Throwables.BiFunction<AbstractQuery, Object[], Object, SQLException> func = (Throwables.BiFunction<AbstractQuery, Object[], Object, SQLException>) factory
                .invoke(null, TestEntity.class, daoMethod, null, null, null, true, true, false, OP.DEFAULT, false, "PrimitiveExtractorDao.count");

        assertNotNull(func);

        // Verify the function is actually invocable and returns a value from the extractor
        Dataset dataset = new RowDataset(ImmutableList.of("cnt"), ImmutableList.of(ImmutableList.of(42)));
        Jdbc.ResultExtractor<Integer> extractor = rs -> 42;
        Object result = func.apply(new StubQuery(dataset), new Object[] { extractor });
        // The factory should produce a function that returns a non-null result from the query
        assertNotNull(result);
        // Verify the result is a Dataset containing the expected data
        assertTrue(result instanceof Dataset, "Expected a Dataset result from the query function");
        assertEquals(1, ((Dataset) result).size(), "Dataset should contain one row");
    }

    @Test
    void testCreateDaoWithReorderedGenericInterfaces() throws Exception {
        ReorderedGenericDao dao = DaoImpl.createDao(ReorderedGenericDao.class, null, mockDataSourceForDaoCreation(), PSC, null, null, null);
        assertNotNull(dao);
        // Verify the proxy implements both the DAO interface and the Marker interface
        assertTrue(dao instanceof BaseDao, "Should implement BaseDao");
        assertTrue(dao instanceof Marker, "Should implement Marker");
        // Verify the proxy is not accidentally the raw interface class itself
        assertFalse(dao.getClass().equals(ReorderedGenericDao.class), "Should be a proxy, not the interface itself");
    }

    @Test
    void testCreateDaoWithIdOnlyEntityDoesNotBuildEmptyUpdateSql() throws Exception {
        IdOnlyCrudDao dao = DaoImpl.createDao(IdOnlyCrudDao.class, null, mockDataSourceForDaoCreation(), PSC, null, null, null);

        assertNotNull(dao);
        assertEquals(0, dao.update(new IdOnlyEntity()));
    }

    @Test
    void testCreateDaoRejectsRowFilterInUnsupportedPosition() throws Exception {
        assertThrows(UnsupportedOperationException.class,
                () -> DaoImpl.createDao(InvalidRowFilterPositionDao.class, null, mockDataSourceForDaoCreation(), PSC, null, null, null));
    }

    // Regression: @Query(isProcedure=true, op=OP.streamAll) Stream<Dataset> proc()
    // previously threw "is not supported the specified op: streamAll" at proxy creation
    // because the Stream-return guard in createQueryFunctionByMethod only allowed
    // OP.stream and OP.DEFAULT — the dedicated OP.streamAll handler was unreachable.
    @Test
    void testCreateDaoAcceptsStreamAllForProcedure() throws Exception {
        StreamAllProcedureDao dao = assertDoesNotThrow(
                () -> DaoImpl.createDao(StreamAllProcedureDao.class, null, mockDataSourceForDaoCreation(), PSC, null, null, null));
        assertNotNull(dao);
    }

    // DAO query classifier branches are private factory inputs exercised through reflection.
    @Test
    public void testIsListQuery_ListOpRejectsNonCollection() throws Exception {
        Method method = QueryClassifierDao.class.getMethod("getEntities");
        Method classifier = DaoImpl.class.getDeclaredMethod("isListQuery", Method.class, Class.class, OP.class, String.class);
        classifier.setAccessible(true);

        InvocationTargetException ex = assertThrows(InvocationTargetException.class,
                () -> classifier.invoke(null, method, TestEntity.class, OP.list, "QueryClassifierDao.getEntities"));

        assertTrue(ex.getCause() instanceof UnsupportedOperationException);
    }

    @Test
    public void testIsListQuery_DefaultSinglePrefixCollection() throws Exception {
        Method method = QueryClassifierDao.class.getMethod("getEntities");
        Method classifier = DaoImpl.class.getDeclaredMethod("isListQuery", Method.class, Class.class, OP.class, String.class);
        classifier.setAccessible(true);

        Object result = classifier.invoke(null, method, List.class, OP.DEFAULT, "QueryClassifierDao.getEntities");

        assertEquals(false, result);
    }

    @Test
    public void testIsListQuery_ResultExtractorParameter() throws Exception {
        Method method = QueryClassifierDao.class.getMethod("selectWithExtractor", Jdbc.ResultExtractor.class);
        Method classifier = DaoImpl.class.getDeclaredMethod("isListQuery", Method.class, Class.class, OP.class, String.class);
        classifier.setAccessible(true);

        Object result = classifier.invoke(null, method, List.class, OP.DEFAULT, "QueryClassifierDao.selectWithExtractor");

        assertEquals(false, result);
    }

    @Test
    public void testIsExistsQuery_ExplicitOpRejectsNonBoolean() throws Exception {
        Method method = QueryClassifierDao.class.getMethod("existsAsString");
        Method classifier = DaoImpl.class.getDeclaredMethod("isExistsQuery", Method.class, OP.class, String.class);
        classifier.setAccessible(true);

        InvocationTargetException ex = assertThrows(InvocationTargetException.class,
                () -> classifier.invoke(null, method, OP.exists, "QueryClassifierDao.existsAsString"));

        assertTrue(ex.getCause() instanceof UnsupportedOperationException);
    }

    @Test
    public void testIsExistsQuery_MapperParameter() throws Exception {
        Method method = QueryClassifierDao.class.getMethod("existsWithMapper", Jdbc.RowMapper.class);
        Method classifier = DaoImpl.class.getDeclaredMethod("isExistsQuery", Method.class, OP.class, String.class);
        classifier.setAccessible(true);

        Object result = classifier.invoke(null, method, OP.DEFAULT, "QueryClassifierDao.existsWithMapper");

        assertEquals(false, result);
    }

    @Test
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void testExistsOperationDoesNotNegateMethodMerelyStartingWithNot() throws Exception {
        final Method method = QueryClassifierDao.class.getMethod("notifyExists");
        final Method factory = DaoImpl.class.getDeclaredMethod("createQueryFunctionByMethod", Class.class, Method.class, String.class, List.class, Map.class,
                boolean.class, boolean.class, boolean.class, OP.class, boolean.class, String.class);
        factory.setAccessible(true);
        final Throwables.BiFunction<AbstractQuery, Object[], Boolean, SQLException> function = (Throwables.BiFunction<AbstractQuery, Object[], Boolean, SQLException>) factory
                .invoke(null, TestEntity.class, method, null, null, null, false, false, false, OP.exists, false, "QueryClassifierDao.notifyExists");
        final AbstractQuery query = mock(AbstractQuery.class);
        org.mockito.Mockito.when(query.exists()).thenReturn(true);

        assertTrue(function.apply(query, new Object[0]));
        org.mockito.Mockito.verify(query).exists();
        org.mockito.Mockito.verify(query, org.mockito.Mockito.never()).notExists();
    }

    // QueryInfo: sql ends with ";" - trims it (L6536 branch)
    @Test
    void testQueryInfo_SqlWithTrailingSemicolon() {
        DaoImpl.QueryInfo qi = new DaoImpl.QueryInfo("SELECT 1;", null, 0, 0, false, 0, OP.DEFAULT, false, false, true, false, false, false);
        assertEquals("SELECT 1", qi.sql);
    }

    // QueryInfo: pre-parsed sql provided (L6537 non-null branch)
    @Test
    void testQueryInfo_WithPreParsedSql() {
        com.landawn.abacus.query.ParsedSql parsed = com.landawn.abacus.query.ParsedSql.parse("SELECT 1");
        DaoImpl.QueryInfo qi = new DaoImpl.QueryInfo("SELECT 1", parsed, 0, 0, false, 0, OP.DEFAULT, false, false, true, false, false, false);
        assertEquals(parsed, qi.parsedSql);
    }

    // QueryInfo: fragmentsContainNamedParameters=true with named SQL -> isNamedQuery=true
    @Test
    void testQueryInfo_FragmentContainsNamedParameters_NamedSql() {
        DaoImpl.QueryInfo qi = new DaoImpl.QueryInfo("SELECT * FROM t WHERE id = :id", null, 0, 0, false, 0, OP.DEFAULT, false, false, true, false, false,
                true);
        assertTrue(qi.isNamedQuery);
    }

    // QueryInfo: fragmentsContainNamedParameters=true with positional SQL -> throws
    @Test
    void testQueryInfo_FragmentContainsNamedParameters_PositionalSql_Throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new DaoImpl.QueryInfo("SELECT * FROM t WHERE id = ?", null, 0, 0, false, 0, OP.DEFAULT, false, false, true, false, false, true));
    }

    /**
     * Regression test: isListQuery must not throw ClassCastException when the collection's
     * type argument is a TypeVariable (e.g., List&lt;T&gt;) rather than a Class or ParameterizedType.
     */
    @Test
    void testIsListQuery_TypeVariableArgument_DoesNotThrow() throws Exception {
        Method method = GenericListDao.class.getMethod("listAll");
        Method classifier = DaoImpl.class.getDeclaredMethod("isListQuery", Method.class, Class.class, OP.class, String.class);
        classifier.setAccessible(true);

        // Should not throw ClassCastException — returns false because paramClassInReturnType is null
        assertDoesNotThrow(() -> classifier.invoke(null, method, List.class, OP.DEFAULT, "GenericListDao.listAll"));
    }

    /**
     * Regression test: createQueryFunctionByMethod must not throw ClassCastException when the ResultExtractor
     * parameter has a TypeVariable type argument (e.g., ResultExtractor&lt;T&gt;).
     */
    @Test
    void testCreateQueryFunctionByMethod_ResultExtractorWithTypeVariable_DoesNotThrow() throws Exception {
        Method method = GenericExtractorDao.class.getMethod("listWithExtractor", Jdbc.ResultExtractor.class);
        Method factory = DaoImpl.class.getDeclaredMethod("createQueryFunctionByMethod", Class.class, Method.class, String.class, List.class, Map.class,
                boolean.class, boolean.class, boolean.class, OP.class, boolean.class, String.class);
        factory.setAccessible(true);

        // Should not throw ClassCastException when ResultExtractor<T> (TypeVariable) is the last param.
        assertDoesNotThrow(() -> factory.invoke(null, TestEntity.class, method, null, null, null, true, false, true, OP.DEFAULT, false,
                "GenericExtractorDao.listWithExtractor"));
    }

    /**
     * Regression test: isListQuery must not throw ClassCastException when the collection's
     * type argument is a WildcardType (e.g., List&lt;? extends TestEntity&gt;) rather than a Class or ParameterizedType.
     */
    @Test
    void testIsListQuery_WildcardTypeArgument_DoesNotThrow() throws Exception {
        Method method = GenericListDao.class.getMethod("listWildcard");
        Method classifier = DaoImpl.class.getDeclaredMethod("isListQuery", Method.class, Class.class, OP.class, String.class);
        classifier.setAccessible(true);

        // Should not throw ClassCastException — returns false because paramClassInReturnType is null
        assertDoesNotThrow(() -> classifier.invoke(null, method, List.class, OP.DEFAULT, "GenericListDao.listWildcard"));
    }

    @CacheResult(enabled = true)
    @RefreshCache
    interface CacheDisabledOverrideDao extends com.landawn.abacus.jdbc.dao.UncheckedNoUpdateDao<TestEntity, CacheDisabledOverrideDao> {
        // No @NonDBOperation: must reach the proxy's cache wrapper so the resolution logic actually runs.
        @CacheResult(enabled = false)
        default String findCached() {
            return "fresh-result";
        }

        default String findCachedDefault() {
            return "fresh-result";
        }

        @RefreshCache(enabled = false)
        default String updateData() {
            return "refresh-result";
        }
    }

    /**
     * Regression: when the DAO class has @CacheResult(enabled=true) and a method has @CacheResult(enabled=false),
     * the method-level explicit disable must override the class-level annotation. Previously the
     * opt-out annotation was silently filtered out, causing the class-level annotation to take
     * effect anyway.
     */
    @Test
    void testMethodLevelDisabledCacheResultOverridesClassLevel() throws Exception {
        java.util.concurrent.atomic.AtomicInteger putCount = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger getCount = new java.util.concurrent.atomic.AtomicInteger();
        Jdbc.DaoCache recordingCache = new Jdbc.DaoCache() {
            @Override
            public Object get(String defaultCacheKey, Object daoProxy, Object[] args, Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
                getCount.incrementAndGet();
                return null;
            }

            @Override
            public boolean put(String defaultCacheKey, Object result, Object daoProxy, Object[] args,
                    Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
                putCount.incrementAndGet();
                return true;
            }

            @Override
            public boolean put(String defaultCacheKey, Object result, long liveTime, long maxIdleTime, Object daoProxy, Object[] args,
                    Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
                putCount.incrementAndGet();
                return true;
            }

            @Override
            public void update(String defaultCacheKey, Object result, Object daoProxy, Object[] args,
                    Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
            }
        };

        CacheDisabledOverrideDao dao = DaoImpl.createDao(CacheDisabledOverrideDao.class, null, mockDataSourceForDaoCreation(), PSC, null, recordingCache, null);

        // Method explicitly disabled - must NOT consult the cache or write to it.
        assertEquals("fresh-result", dao.findCached());
        assertEquals(0, getCount.get(), "Disabled method must not query the cache");
        assertEquals(0, putCount.get(), "Disabled method must not write to the cache");

        // Method without explicit disable - class-level @CacheResult(enabled=true) applies (filter matches "find" prefix).
        dao.findCachedDefault();
        assertTrue(getCount.get() > 0 || putCount.get() > 0, "Enabled method should interact with cache");
    }

    /**
     * Regression: same override semantics for @RefreshCache. Method-level enabled=false must take precedence
     * over a class-level @RefreshCache annotation.
     */
    @Test
    void testMethodLevelDisabledRefreshCacheOverridesClassLevel() throws Exception {
        java.util.concurrent.atomic.AtomicInteger updateCount = new java.util.concurrent.atomic.AtomicInteger();
        Jdbc.DaoCache recordingCache = new Jdbc.DaoCache() {
            @Override
            public Object get(String defaultCacheKey, Object daoProxy, Object[] args, Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
                return null;
            }

            @Override
            public boolean put(String defaultCacheKey, Object result, Object daoProxy, Object[] args,
                    Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
                return true;
            }

            @Override
            public boolean put(String defaultCacheKey, Object result, long liveTime, long maxIdleTime, Object daoProxy, Object[] args,
                    Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
                return true;
            }

            @Override
            public void update(String defaultCacheKey, Object result, Object daoProxy, Object[] args,
                    Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
                updateCount.incrementAndGet();
            }
        };

        CacheDisabledOverrideDao dao = DaoImpl.createDao(CacheDisabledOverrideDao.class, null, mockDataSourceForDaoCreation(), PSC, null, recordingCache, null);
        assertEquals("refresh-result", dao.updateData());
        assertEquals(0, updateCount.get(), "@RefreshCache(enabled=false) method must not invalidate the cache");
    }

    // isFindFirst: OP.findFirst returns true regardless of method name (line 521)
    @Test
    public void testIsFindFirst_OpFindFirst() throws Exception {
        Method method = FindFirstOpDao.class.getMethod("anyName");
        Method m = DaoImpl.class.getDeclaredMethod("isFindFirst", Method.class, OP.class);
        m.setAccessible(true);

        assertTrue((boolean) m.invoke(null, method, OP.findFirst));
    }

    // isFindFirst: OP.DEFAULT with method starting "findFirst" returns true (line 524)
    @Test
    public void testIsFindFirst_DefaultWithFindFirstPrefix() throws Exception {
        Method method = FindFirstPrefixDao.class.getMethod("findFirstByStatus", String.class);
        Method m = DaoImpl.class.getDeclaredMethod("isFindFirst", Method.class, OP.class);
        m.setAccessible(true);

        assertTrue((boolean) m.invoke(null, method, OP.DEFAULT));
    }

    // isFindFirst: OP.DEFAULT with "findOnlyOne" prefix returns false (line 524)
    @Test
    public void testIsFindFirst_DefaultFindOnlyOneReturnsFalse() throws Exception {
        Method method = FindOnlyOneMethodDao.class.getMethod("findOnlyOneById", long.class);
        Method m = DaoImpl.class.getDeclaredMethod("isFindFirst", Method.class, OP.class);
        m.setAccessible(true);

        assertFalse((boolean) m.invoke(null, method, OP.DEFAULT));
    }

    // isFindOnlyOne: OP.findOnlyOne returns true (line 530)
    @Test
    public void testIsFindOnlyOne_OpFindOnlyOne() throws Exception {
        Method method = FindFirstOpDao.class.getMethod("anyName");
        Method m = DaoImpl.class.getDeclaredMethod("isFindOnlyOne", Method.class, OP.class);
        m.setAccessible(true);

        assertTrue((boolean) m.invoke(null, method, OP.findOnlyOne));
    }

    // isFindOnlyOne: OP.DEFAULT with method starting "findOnlyOne" returns true (line 533)
    @Test
    public void testIsFindOnlyOne_DefaultWithFindOnlyOnePrefix() throws Exception {
        Method method = FindOnlyOneMethodDao.class.getMethod("findOnlyOneById", long.class);
        Method m = DaoImpl.class.getDeclaredMethod("isFindOnlyOne", Method.class, OP.class);
        m.setAccessible(true);

        assertTrue((boolean) m.invoke(null, method, OP.DEFAULT));
    }

    // isQueryForUnique: OP.queryForUnique returns true (line 538)
    @Test
    public void testIsQueryForUnique_OpQueryForUnique() throws Exception {
        Method method = AnyOpDaoForUnique.class.getMethod("anyName");
        Method m = DaoImpl.class.getDeclaredMethod("isQueryForUnique", Method.class, OP.class);
        m.setAccessible(true);

        assertTrue((boolean) m.invoke(null, method, OP.queryForUnique));
    }

    // isQueryForUnique: OP.DEFAULT with method starting "queryForUnique" returns true (line 541)
    @Test
    public void testIsQueryForUnique_DefaultWithQueryForUniquePrefix() throws Exception {
        Method method = QueryForUniqueMethodDao.class.getMethod("queryForUniqueByName", String.class);
        Method m = DaoImpl.class.getDeclaredMethod("isQueryForUnique", Method.class, OP.class);
        m.setAccessible(true);

        assertTrue((boolean) m.invoke(null, method, OP.DEFAULT));
    }

    // isListQuery: OP.listAll with valid Collection subtype returns true (line 437)
    @Test
    public void testIsListQuery_ListAllOpWithValidCollection() throws Exception {
        Method method = ListAllOpDao.class.getMethod("getEntities");
        Method m = DaoImpl.class.getDeclaredMethod("isListQuery", Method.class, Class.class, OP.class, String.class);
        m.setAccessible(true);

        assertTrue((boolean) m.invoke(null, method, java.util.Set.class, OP.listAll, "ListAllOpDao.getEntities"));
    }

    // isListQuery: @MappedByKey annotation returns true (line 439)
    @Test
    public void testIsListQuery_MappedByKeyAnnotation() throws Exception {
        Method method = MappedByKeyDao.class.getMethod("findMapped");
        Method m = DaoImpl.class.getDeclaredMethod("isListQuery", Method.class, Class.class, OP.class, String.class);
        m.setAccessible(true);

        assertTrue((boolean) m.invoke(null, method, java.util.Map.class, OP.DEFAULT, "MappedByKeyDao.findMapped"));
    }

    // isListQuery: non-DEFAULT non-list op returns false (line 441)
    @Test
    public void testIsListQuery_UpdateOpReturnsFalse() throws Exception {
        Method method = UpdateOpDao.class.getMethod("updateCount");
        Method m = DaoImpl.class.getDeclaredMethod("isListQuery", Method.class, Class.class, OP.class, String.class);
        m.setAccessible(true);

        assertFalse((boolean) m.invoke(null, method, int.class, OP.update, "UpdateOpDao.updateCount"));
    }

    // isExistsQuery: OP.exists with boolean return returns true (line 500)
    @Test
    public void testIsExistsQuery_OpExistsReturnsTrue() throws Exception {
        Method method = ExistsOpDao.class.getMethod("checkExists");
        Method m = DaoImpl.class.getDeclaredMethod("isExistsQuery", Method.class, OP.class, String.class);
        m.setAccessible(true);

        assertTrue((boolean) m.invoke(null, method, OP.exists, "ExistsOpDao.checkExists"));
    }

    // isExistsQuery: non-DEFAULT non-exists op returns false (line 502)
    @Test
    public void testIsExistsQuery_ListOpReturnsFalse() throws Exception {
        Method method = ExistsOpDao.class.getMethod("checkExists");
        Method m = DaoImpl.class.getDeclaredMethod("isExistsQuery", Method.class, OP.class, String.class);
        m.setAccessible(true);

        assertFalse((boolean) m.invoke(null, method, OP.list, "ExistsOpDao.checkExists"));
    }

    // isSingleReturnType: checks Optional, Nullable, primitive types
    @Test
    public void testIsSingleReturnType() throws Exception {
        Method m = DaoImpl.class.getDeclaredMethod("isSingleReturnType", Class.class);
        m.setAccessible(true);

        assertTrue((boolean) m.invoke(null, u.Optional.class));
        assertTrue((boolean) m.invoke(null, u.Nullable.class));
        assertTrue((boolean) m.invoke(null, u.OptionalInt.class));
        assertTrue((boolean) m.invoke(null, int.class));
        assertTrue((boolean) m.invoke(null, java.util.Optional.class));
        assertFalse((boolean) m.invoke(null, String.class));
        assertFalse((boolean) m.invoke(null, TestEntity.class));
    }

    // isFindOrListTargetClass: checks bean, map, list, array, record classes
    @Test
    public void testIsFindOrListTargetClass() throws Exception {
        Method m = DaoImpl.class.getDeclaredMethod("isFindOrListTargetClass", Class.class);
        m.setAccessible(true);

        assertTrue((boolean) m.invoke(null, TestEntity.class));
        assertTrue((boolean) m.invoke(null, java.util.Map.class));
        assertTrue((boolean) m.invoke(null, java.util.List.class));
        assertTrue((boolean) m.invoke(null, Object[].class));
        assertFalse((boolean) m.invoke(null, String.class));
    }

    // getFirstReturnEleType: returns element type from parameterized return type
    @Test
    public void testGetFirstReturnEleType_List() throws Exception {
        Method method = FirstReturnEleDao.class.getMethod("getEntities");
        Method m = DaoImpl.class.getDeclaredMethod("getFirstReturnEleType", Method.class);
        m.setAccessible(true);

        assertEquals(TestEntity.class, m.invoke(null, method));
    }

    // getFirstReturnEleType: null for non-parameterized return type
    @Test
    public void testGetFirstReturnEleType_PlainReturn() throws Exception {
        Method method = PlainReturnDao.class.getMethod("getEntity");
        Method m = DaoImpl.class.getDeclaredMethod("getFirstReturnEleType", Method.class);
        m.setAccessible(true);

        assertEquals(null, m.invoke(null, method));
    }

    // getSecondReturnEleType: returns second element type from Map return
    @Test
    public void testGetSecondReturnEleType_Map() throws Exception {
        Method method = SecondReturnEleDao.class.getMethod("getMapped");
        Method m = DaoImpl.class.getDeclaredMethod("getSecondReturnEleType", Method.class);
        m.setAccessible(true);

        assertEquals(TestEntity.class, m.invoke(null, method));
    }

    // getFirstReturnEleEleType: returns nested element type from List<List<>> return
    @Test
    public void testGetFirstReturnEleEleType_NestedList() throws Exception {
        Method method = FirstReturnEleEleDao.class.getMethod("getNested");
        Method m = DaoImpl.class.getDeclaredMethod("getFirstReturnEleEleType", Method.class);
        m.setAccessible(true);

        assertEquals(TestEntity.class, m.invoke(null, method));
    }

    // getFirstReturnEleEleType: returns nested element type from List<Set<>> return
    @Test
    public void testGetFirstReturnEleEleType_NestedSet() throws Exception {
        Method method = SecondReturnEleEleDao.class.getMethod("getNestedSets");
        Method m = DaoImpl.class.getDeclaredMethod("getFirstReturnEleEleType", Method.class);
        m.setAccessible(true);

        assertEquals(TestEntity.class, m.invoke(null, method));
    }

    // createMethodHandle: creates a method handle for a default interface method (line 375)
    @Test
    public void testCreateMethodHandle_DefaultMethod() throws Exception {
        Method method = DefaultMethodDao.class.getMethod("greeting");
        Method m = DaoImpl.class.getDeclaredMethod("createMethodHandle", Method.class);
        m.setAccessible(true);

        Object handle = m.invoke(null, method);
        assertNotNull(handle);
        assertTrue(handle instanceof java.lang.invoke.MethodHandle);
    }

    // QueryInfo: sql without trailing semicolon is not modified (line 6649)
    @Test
    public void testQueryInfo_SqlWithoutTrailingSemicolon() {
        DaoImpl.QueryInfo qi = new DaoImpl.QueryInfo("SELECT 1", null, 10, 20, true, 50, OP.update, true, true, false, false, false, false);
        assertEquals("SELECT 1", qi.sql);
        assertEquals(10, qi.queryTimeout);
        assertEquals(20, qi.fetchSize);
        assertTrue(qi.isBatch);
        assertEquals(50, qi.batchSize);
        assertEquals(OP.update, qi.op);
        assertTrue(qi.isSingleParameter);
        assertTrue(qi.autoSetSysTimeParam);
        assertFalse(qi.isSelect);
        assertFalse(qi.isInsert);
        assertFalse(qi.isProcedure);
        assertFalse(qi.isNamedQuery);
    }

    @Test
    public void testCreateDao_DefaultSqlSourceDoesNotLoadEmptyMapper() throws SQLException {
        assertDoesNotThrow(() -> DaoImpl.createDao(DefaultSqlSourceDao.class, null, mockDataSourceForDaoCreation(), PSC, null, null, null));
    }

    @Test
    public void testCreateDao_InvokesNoArgDefaultMethod() throws Exception {
        NoArgDefaultMethodDao dao = DaoImpl.createDao(NoArgDefaultMethodDao.class, null, mockDataSourceForDaoCreation(), PSC, null, null, null);

        assertEquals("hello", dao.greeting());
    }

    @Test
    public void testCreateDao_MultipleQuerySqlArrayIsIsolatedPerInvocation() throws Exception {
        MultipleSqlDefaultMethodDao dao = DaoImpl.createDao(MultipleSqlDefaultMethodDao.class, null, mockDataSourceForDaoCreation(), PSC, null, null, null);

        assertEquals("SELECT 1", dao.consumeAndMutateFirstSql());
        assertEquals("SELECT 1", dao.consumeAndMutateFirstSql());
    }

    @Test
    public void testCreateDao_RejectsOutParameterWithNameAndPosition() throws SQLException {
        DataSource ds = mockDataSourceForDaoCreation();

        assertThrows(UnsupportedOperationException.class, () -> DaoImpl.createDao(AmbiguousOutParameterDao.class, null, ds, PSC, null, null, null));
    }

    @Test
    public void testCreateDao_RejectsOutParameterWithZeroPosition() throws SQLException {
        DataSource ds = mockDataSourceForDaoCreation();

        final UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> DaoImpl.createDao(ZeroPositionOutParameterDao.class, null, ds, PSC, null, null, null));

        assertTrue(e.getMessage().contains("@OutParameter position must be greater than 0"), e.getMessage());
    }

    @Test
    public void testPrepareQueryWithConditionDoesNotConfigureLargeResultStatement() throws SQLException {
        DataSource ds = mockDataSourceForDaoCreation();
        IdOnlyCrudDao dao = DaoImpl.createDao(IdOnlyCrudDao.class, null, ds, PSC, null, null, null);
        PreparedQuery query = mock(PreparedQuery.class);

        org.mockito.Mockito
                .when(query.configureStatement(org.mockito.ArgumentMatchers.<Throwables.Consumer<? super PreparedStatement, ? extends SQLException>> any()))
                .thenReturn(query);
        org.mockito.Mockito.when(query.settParameters(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(query);

        try (MockedStatic<JdbcUtil> jdbcUtil = org.mockito.Mockito.mockStatic(JdbcUtil.class)) {
            jdbcUtil.when(() -> JdbcUtil.prepareQuery(org.mockito.ArgumentMatchers.same(ds), org.mockito.ArgumentMatchers.anyString())).thenReturn(query);

            assertSame(query, dao.prepareQuery(List.of("id"), Filters.eq("id", 1L)));
        }

        org.mockito.Mockito.verify(query, org.mockito.Mockito.never())
                .configureStatement(org.mockito.ArgumentMatchers.<Throwables.Consumer<? super PreparedStatement, ? extends SQLException>> any());
    }

    @Test
    public void testPrepareNamedQueryWithConditionDoesNotConfigureLargeResultStatement() throws SQLException {
        DataSource ds = mockDataSourceForDaoCreation();
        IdOnlyCrudDao dao = DaoImpl.createDao(IdOnlyCrudDao.class, null, ds, PSC, null, null, null);
        NamedQuery query = mock(NamedQuery.class);

        org.mockito.Mockito
                .when(query.configureStatement(org.mockito.ArgumentMatchers.<Throwables.Consumer<? super PreparedStatement, ? extends SQLException>> any()))
                .thenReturn(query);
        org.mockito.Mockito.when(query.settParameters(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(query);

        try (MockedStatic<JdbcUtil> jdbcUtil = org.mockito.Mockito.mockStatic(JdbcUtil.class)) {
            jdbcUtil.when(() -> JdbcUtil.prepareNamedQuery(org.mockito.ArgumentMatchers.same(ds), org.mockito.ArgumentMatchers.anyString())).thenReturn(query);

            assertSame(query, dao.prepareNamedQuery(List.of("id"), Filters.eq("id", 1L)));
        }

        org.mockito.Mockito.verify(query, org.mockito.Mockito.never())
                .configureStatement(org.mockito.ArgumentMatchers.<Throwables.Consumer<? super PreparedStatement, ? extends SQLException>> any());
    }

    @Test
    public void testIsListQuery_IncompatibleRowMapperTypeIsNotListQuery() throws Exception {
        Method daoMethod = IncompatibleRowMapperListDao.class.getMethod("list", Jdbc.RowMapper.class);
        Method isListQuery = DaoImpl.class.getDeclaredMethod("isListQuery", Method.class, Class.class, OP.class, String.class);
        isListQuery.setAccessible(true);

        assertFalse((Boolean) isListQuery.invoke(null, daoMethod, List.class, OP.DEFAULT, "IncompatibleRowMapperListDao.list"));
    }

    private static DataSource mockDataSourceForDaoCreation() throws SQLException {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        DatabaseMetaData meta = mock(DatabaseMetaData.class);

        org.mockito.Mockito.when(ds.getConnection()).thenReturn(conn);
        org.mockito.Mockito.when(conn.getMetaData()).thenReturn(meta);
        org.mockito.Mockito.when(meta.getDatabaseProductName()).thenReturn("MySQL");
        org.mockito.Mockito.when(meta.getDatabaseProductVersion()).thenReturn("8.0");

        return ds;
    }

    private static Condition invokeHandleLimit(final Condition cond, final int count) throws Exception {
        final Method handleLimit = DaoImpl.class.getDeclaredMethod("handleLimit", Condition.class, int.class);
        handleLimit.setAccessible(true);

        return (Condition) handleLimit.invoke(null, cond, count);
    }

    @Test
    public void testSumUpdateCountsDoesNotOverflowAtIntBoundary() throws Exception {
        final Method sumUpdateCounts = DaoImpl.class.getDeclaredMethod("sumUpdateCounts", int[].class);
        sumUpdateCounts.setAccessible(true);

        assertEquals(2L * Integer.MAX_VALUE, sumUpdateCounts.invoke(null, (Object) new int[] { Integer.MAX_VALUE, Integer.MAX_VALUE }));
        assertEquals(3L, sumUpdateCounts.invoke(null, (Object) new int[] { 2, Statement.SUCCESS_NO_INFO, Statement.EXECUTE_FAILED, 1 }));
    }

    @Test
    public void testSumLargeUpdateCountsIgnoresJdbcSentinelsAndDetectsOverflow() throws Exception {
        final Method sumUpdateCounts = DaoImpl.class.getDeclaredMethod("sumUpdateCounts", long[].class);
        sumUpdateCounts.setAccessible(true);

        assertEquals(3L, sumUpdateCounts.invoke(null, (Object) new long[] { 2, Statement.SUCCESS_NO_INFO, Statement.EXECUTE_FAILED, 1 }));

        final InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> sumUpdateCounts.invoke(null, (Object) new long[] { Long.MAX_VALUE, 1 }));
        assertTrue(thrown.getCause() instanceof ArithmeticException);
    }

    // Regression: handleLimit's Expression-already-has-limit check used to omit " FETCH FIRST ", so an
    // Expression containing FETCH FIRST (which DaoImpl itself emits for Oracle/SQLServer/DB2 and which
    // users may write inline) would fall through to the count>0 branch and get wrapped in a Criteria
    // with a second LIMIT/FETCH appended — producing invalid SQL on those databases.
    @Test
    public void testHandleLimit_FetchFirstExpressionNotDuplicated() throws Exception {
        Method handleLimit = DaoImpl.class.getDeclaredMethod("handleLimit", com.landawn.abacus.query.condition.Condition.class, int.class);
        handleLimit.setAccessible(true);

        Expression expr = Filters.expr("id > 0 FETCH FIRST 10 ROWS ONLY");

        // Prior bug → wrapped in Criteria with extra FETCH FIRST appended. After fix, returned as-is.
        Object out = handleLimit.invoke(null, expr, 5);
        assertSame(expr, out, "Expression already containing FETCH FIRST must not be re-wrapped");

        // Sanity: an Expression containing a plain LIMIT is also returned as-is (already covered before fix).
        Expression limitExpr = Filters.expr("id > 0 LIMIT 10");
        Object out2 = handleLimit.invoke(null, limitExpr, 5);
        assertSame(limitExpr, out2, "Expression already containing LIMIT must not be re-wrapped");
    }

    @Test
    public void testHandleLimit_StandaloneLimitRetainedForLimitDialect() throws Exception {
        final Limit original = Filters.limit(20, 5);

        assertSame(original, invokeHandleLimit(original, -1));
    }

    @Test
    public void testHandleLimit_CriteriaLimitRetainedAndAppendedForLimitDialect() throws Exception {
        final Criteria withLimit = Criteria.builder().where(Filters.eq("id", 1)).limit(20, 5).build();
        final Criteria retained = (Criteria) invokeHandleLimit(withLimit, -1);

        assertEquals(20, retained.getLimit().getCount());
        assertEquals(5, retained.getLimit().getOffset());
        assertNotNull(retained.getWhere());

        final Criteria withoutLimit = Criteria.builder().where(Filters.eq("id", 1)).build();
        final Criteria mysqlLimited = (Criteria) invokeHandleLimit(withoutLimit, 3);
        assertEquals(3, mysqlLimited.getLimit().getCount());
        assertEquals(0, mysqlLimited.getLimit().getOffset());
    }

    @Test
    public void testHandleLimit_BuildsCriteriaForBareConditions() throws Exception {
        final Criteria whereLimited = (Criteria) invokeHandleLimit(Filters.eq("id", 1), 2);
        assertNotNull(whereLimited.getWhere());
        assertEquals(2, whereLimited.getLimit().getCount());

        final Criteria orderLimited = (Criteria) invokeHandleLimit(Filters.orderBy("id"), 2);
        assertNotNull(orderLimited.getOrderBy());
        assertEquals(2, orderLimited.getLimit().getCount());

        final Criteria groupLimited = (Criteria) invokeHandleLimit(Filters.groupBy("status"), 2);
        assertNotNull(groupLimited.getGroupBy());
        assertEquals(2, groupLimited.getLimit().getCount());

        // A null condition with a positive count yields a standalone Limit (no wrapping Criteria).
        final Limit noConditionLimited = (Limit) invokeHandleLimit(null, 2);
        assertEquals(2, noConditionLimited.getCount());
        assertEquals(0, noConditionLimited.getOffset());
    }

    // Regression: getApplicableDaoForJoinEntity used to return a cache hit blindly. Because the cache key
    // mixes System.identityHashCode(ds) — which is not guaranteed unique across live objects — a hit
    // for a DAO bound to DataSource A could be served for an unrelated lookup against DataSource B.
    // After the fix, a cache hit is only returned when the cached DAO's dataSource() also matches.
    @Test
    public void testGetApplicableDaoForJoinEntity_HitVerifiesDataSourceMatch() throws Exception {
        DataSource ds1 = mockDataSourceForDaoCreation();
        DataSource ds2 = mockDataSourceForDaoCreation();

        // Create a real DAO bound to ds1 — registers it in DaoImpl.daoPool keyed on ds1's identity.
        IdOnlyCrudDao daoForDs1 = DaoImpl.createDao(IdOnlyCrudDao.class, null, ds1, PSC, null, null, null);

        // Poison joinEntityDaoPool with daoForDs1 under the key that would be computed for ds2 + IdOnlyEntity.
        // The cache-hit path used to return daoForDs1; the fix forces fall-through when ds doesn't match.
        java.lang.reflect.Field poolField = DaoImpl.class.getDeclaredField("joinEntityDaoPool");
        poolField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Dao<?, ?>> pool = (Map<String, Dao<?, ?>>) poolField.get(null);

        String poisonedKey = com.landawn.abacus.util.ClassUtil.getCanonicalClassName(IdOnlyEntity.class) + "_" + System.identityHashCode(ds2);
        pool.put(poisonedKey, daoForDs1);

        try {
            Method m = DaoImpl.class.getDeclaredMethod("getApplicableDaoForJoinEntity", Class.class, DataSource.class, DaoBase.class);
            m.setAccessible(true);

            // Create a real DAO bound to ds2 so the fall-through scan can find it.
            IdOnlyCrudDao daoForDs2 = DaoImpl.createDao(IdOnlyCrudDao.class, null, ds2, PSC, null, null, null);

            Object resolved = m.invoke(null, IdOnlyEntity.class, ds2, daoForDs2);

            assertSame(daoForDs2, resolved, "DAO bound to ds2 must be returned, not the poisoned ds1 entry");
            assertFalse(resolved == daoForDs1, "Must not return the cached DAO bound to a different DataSource");
        } finally {
            pool.remove(poisonedKey);
        }
    }

    @Test
    public void testHandleLimit_BareUnionConditionDoesNotClassCast() throws Exception {
        final Condition union = Filters.union(Filters.subQuery("select 1"));

        final Criteria limited = (Criteria) invokeHandleLimit(union, 2);

        assertEquals(1, limited.getSetOperations().size());
        assertEquals(2, limited.getLimit().getCount());
    }

    @Test
    public void testTransactionalWrapperPreservesPrimaryFailureWhenRollbackFails() throws SQLException {
        final DataSource dataSource = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        Mockito.when(dataSource.getConnection()).thenReturn(connection);
        Mockito.when(connection.getMetaData()).thenReturn(metadata);
        Mockito.when(metadata.getDatabaseProductName()).thenReturn("H2");
        Mockito.when(metadata.getDatabaseProductVersion()).thenReturn("2");
        Mockito.when(connection.getAutoCommit()).thenReturn(true);
        Mockito.when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        Mockito.doThrow(new SQLException("rollback failed")).when(connection).rollback();
        final RollbackMaskDao dao = JdbcUtil.createDao(RollbackMaskDao.class, dataSource);

        final IllegalStateException thrown = assertThrows(IllegalStateException.class, dao::failInTransaction);

        assertSame(RollbackMaskDao.PRIMARY_FAILURE, thrown);
        assertEquals(1, thrown.getSuppressed().length);
    }
}
