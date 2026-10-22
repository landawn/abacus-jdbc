package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.exception.DuplicateResultException;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.query.condition.Condition;
import com.landawn.abacus.util.EntityId;
import com.landawn.abacus.util.u.Optional;

/**
 * Regression tests for the default methods of {@link CrudReadOps}: {@code get} wrapping, {@code notExists},
 * and the id-chunking / de-duplication / duplicate-row detection of {@code batchGet} and {@code count(ids)}.
 */
public class CrudReadOpsTest extends TestBase {

    public static class RowKey {
        private Long tenantId;
        private Long rowId;
        private String name;

        public RowKey() {
        }

        public RowKey(final Long tenantId, final Long rowId, final String name) {
            this.tenantId = tenantId;
            this.rowId = rowId;
            this.name = name;
        }

        public Long getTenantId() {
            return tenantId;
        }

        public void setTenantId(final Long tenantId) {
            this.tenantId = tenantId;
        }

        public Long getRowId() {
            return rowId;
        }

        public void setRowId(final Long rowId) {
            this.rowId = rowId;
        }

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }
    }

    public record RowKeyRecord(Long tenantId, byte[] rowId, String name) {
    }

    // batchGet/count(ids) de-duplicate composite ids by the declared id properties their condition matches on: an EntityId key,
    // a Map entry or a bean property beyond them does not make two ids of one row distinct; values compare by content.
    @Test
    public void testDeclaredIdValuesKey_ComparesTheDeclaredIdPropertiesOnly() {
        final List<String> idPropNames = List.of("tenantId", "rowId");

        assertEquals(CrudReadOps.declaredIdValuesKey(EntityId.of("tenantId", 1L, "rowId", 2L), idPropNames),
                CrudReadOps.declaredIdValuesKey(EntityId.of("rowId", 2L, "tenantId", 1L, "name", "ignored"), idPropNames));
        assertEquals(CrudReadOps.declaredIdValuesKey(Map.of("tenantId", 1L, "rowId", 2L), idPropNames),
                CrudReadOps.declaredIdValuesKey(Map.of("tenantId", 1L, "rowId", 2L, "name", "ignored"), idPropNames));
        assertEquals(CrudReadOps.declaredIdValuesKey(new RowKey(1L, 2L, "a"), idPropNames),
                CrudReadOps.declaredIdValuesKey(new RowKey(1L, 2L, "b"), idPropNames));
        assertEquals(CrudReadOps.declaredIdValuesKey(new RowKey(1L, 2L, "a"), idPropNames),
                CrudReadOps.declaredIdValuesKey(Map.of("tenantId", 1L, "rowId", 2L), idPropNames));
        assertEquals(CrudReadOps.declaredIdValuesKey(new RowKeyRecord(1L, new byte[] { 7 }, "a"), idPropNames),
                CrudReadOps.declaredIdValuesKey(new RowKeyRecord(1L, new byte[] { 7 }, "b"), idPropNames), "array components compare by content");

        assertFalse(CrudReadOps.declaredIdValuesKey(EntityId.of("tenantId", 1L, "rowId", 2L), idPropNames)
                .equals(CrudReadOps.declaredIdValuesKey(EntityId.of("tenantId", 1L, "rowId", 3L), idPropNames)));
        assertFalse(CrudReadOps.declaredIdValuesKey(new RowKey(1L, 2L, "a"), idPropNames)
                .equals(CrudReadOps.declaredIdValuesKey(new RowKey(1L, null, "a"), idPropNames)));

        // Null and other elements are returned as they are: an invalid one is rejected by DaoUtil.checkCompositeIds before the
        // de-duplication, a null Map/bean id is skipped by DaoUtil.idsToCondition.
        assertSame(null, CrudReadOps.declaredIdValuesKey(null, idPropNames));
        final Object other = "1-2";
        assertSame(other, CrudReadOps.declaredIdValuesKey(other, idPropNames));
    }

    interface IdAnnotatedCrudDao extends CrudDao<IdAnnotatedEntity, Long, IdAnnotatedCrudDao> {
    }

    static final class IdAnnotatedEntity {
        @Id
        private Long id;
        private String name;

        IdAnnotatedEntity() {
        }

        IdAnnotatedEntity(final Long id) {
            this.id = id;
        }

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

    private static IdAnnotatedCrudDao newDao() {
        final IdAnnotatedCrudDao dao = Mockito.mock(IdAnnotatedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        Mockito.doReturn(IdAnnotatedEntity.class).when(dao).targetEntityClass();
        return dao;
    }

    // Stubs list(selectPropNames, cond) to return one entity per id carried by the chunk condition.
    private static void stubListByChunkIds(final IdAnnotatedCrudDao dao) throws SQLException {
        Mockito.doAnswer(inv -> {
            final Condition cond = inv.getArgument(1);
            final List<IdAnnotatedEntity> result = new ArrayList<>();
            for (final Object id : cond.parameters()) {
                result.add(new IdAnnotatedEntity((Long) id));
            }
            return result;
        }).when(dao).list(ArgumentMatchers.<Collection<String>> any(), ArgumentMatchers.any(Condition.class));
    }

    private static List<Long> idsOf(final List<IdAnnotatedEntity> entities) {
        final List<Long> ids = new ArrayList<>(entities.size());
        for (final IdAnnotatedEntity e : entities) {
            ids.add(e.getId());
        }
        return ids;
    }

    @Test
    public void testGet_EmptyWhenGetOrNullReturnsNull() throws SQLException {
        final IdAnnotatedCrudDao dao = newDao();
        when(dao.getOrNull(1L)).thenReturn(null);

        final Optional<IdAnnotatedEntity> result = dao.get(1L);

        assertFalse(result.isPresent());
        verify(dao).getOrNull(1L);
    }

    @Test
    public void testGet_WithSelectPropNames_DelegatesToGetOrNullWithSameProps() throws SQLException {
        final IdAnnotatedCrudDao dao = newDao();
        final IdAnnotatedEntity entity = new IdAnnotatedEntity(2L);
        final List<String> props = List.of("id", "name");
        when(dao.getOrNull(2L, props)).thenReturn(entity);

        assertSame(entity, dao.get(2L, props).orElseNull());
        assertFalse(dao.get(3L, props).isPresent());
        verify(dao).getOrNull(2L, props);
        verify(dao).getOrNull(3L, props);
    }

    @Test
    public void testNotExists_NegatesExistsById() throws SQLException {
        final IdAnnotatedCrudDao dao = newDao();
        when(dao.exists(7L)).thenReturn(true, false);

        assertFalse(dao.notExists(7L));
        assertTrue(dao.notExists(7L));
    }

    @Test
    public void testBatchGet_EmptyIds_ReturnsEmptyWithoutQuerying() throws SQLException {
        final IdAnnotatedCrudDao dao = newDao();

        assertEquals(List.of(), dao.batchGet(List.of(), null, 10));
        assertEquals(List.of(), dao.batchGet((Collection<Long>) null, null, 10));
        verify(dao, never()).list(ArgumentMatchers.<Collection<String>> any(), ArgumentMatchers.any(Condition.class));
    }

    @Test
    public void testBatchGet_NonPositiveBatchSize_RejectedEvenForEmptyIds() {
        final IdAnnotatedCrudDao dao = newDao();

        assertThrows(IllegalArgumentException.class, () -> dao.batchGet(List.of(), null, 0));
        assertThrows(IllegalArgumentException.class, () -> dao.batchGet(List.of(1L), null, -1));
    }

    @Test
    public void testBatchGet_ChunksIdsByBatchSizeAndPreservesChunkOrder() throws SQLException {
        final IdAnnotatedCrudDao dao = newDao();
        stubListByChunkIds(dao);
        final List<Long> ids = List.of(1L, 2L, 3L, 4L, 5L);

        final List<IdAnnotatedEntity> result = dao.batchGet(ids, List.of("id"), 2);

        assertEquals(ids, idsOf(result));

        final ArgumentCaptor<Condition> condCaptor = ArgumentCaptor.forClass(Condition.class);
        verify(dao, Mockito.times(3)).list(ArgumentMatchers.eq(List.of("id")), condCaptor.capture());
        final List<Condition> chunks = condCaptor.getAllValues();
        assertEquals(List.of(1L, 2L), chunks.get(0).parameters());
        assertEquals(List.of(3L, 4L), chunks.get(1).parameters());
        assertEquals(List.of(5L), chunks.get(2).parameters());
    }

    @Test
    public void testBatchGet_BatchSizeEqualToIdCount_SingleChunk() throws SQLException {
        final IdAnnotatedCrudDao dao = newDao();
        stubListByChunkIds(dao);
        final List<Long> ids = List.of(1L, 2L, 3L);

        assertEquals(ids, idsOf(dao.batchGet(ids, null, 3)));
        verify(dao, Mockito.times(1)).list(ArgumentMatchers.<Collection<String>> isNull(), ArgumentMatchers.any(Condition.class));
    }

    // Duplicates that straddle a chunk boundary must not produce the same entity twice.
    @Test
    public void testBatchGet_DuplicateIdsAcrossChunks_QueriedOnceAndReturnedOnce() throws SQLException {
        final IdAnnotatedCrudDao dao = newDao();
        stubListByChunkIds(dao);

        final List<IdAnnotatedEntity> result = dao.batchGet(List.of(1L, 2L, 1L), null, 2);

        assertEquals(List.of(1L, 2L), idsOf(result));
        final ArgumentCaptor<Condition> condCaptor = ArgumentCaptor.forClass(Condition.class);
        verify(dao, Mockito.times(1)).list(ArgumentMatchers.<Collection<String>> isNull(), condCaptor.capture());
        assertEquals(List.of(1L, 2L), condCaptor.getValue().parameters());
    }

    @Test
    public void testBatchGet_SetInput_IsQueriedInIterationOrder() throws SQLException {
        final IdAnnotatedCrudDao dao = newDao();
        stubListByChunkIds(dao);
        final Set<Long> ids = new LinkedHashSet<>(Arrays.asList(30L, 10L, 20L));

        assertEquals(List.of(30L, 10L, 20L), idsOf(dao.batchGet(ids, null, 10)));
    }

    @Test
    public void testBatchGet_ChunkReturningMoreRowsThanItsIds_ThrowsDuplicateResult() throws SQLException {
        final IdAnnotatedCrudDao dao = newDao();
        Mockito.doReturn(List.of(new IdAnnotatedEntity(1L), new IdAnnotatedEntity(1L)))
                .when(dao)
                .list(ArgumentMatchers.<Collection<String>> any(), ArgumentMatchers.any(Condition.class));

        final DuplicateResultException e = assertThrows(DuplicateResultException.class, () -> dao.batchGet(List.of(1L), null, 5));
        assertTrue(e.getMessage().contains("batchSize=5"));
    }

    @Test
    public void testBatchGet_RejectsMapIdsForSingleIdEntity() {
        final IdAnnotatedCrudDao dao = newDao();
        final List<Object> ids = List.of(Map.of("id", 1L));

        @SuppressWarnings({ "unchecked", "rawtypes" })
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> dao.batchGet((Collection) ids, null, 5));
        assertTrue(e.getMessage().contains("single id"));
    }

    interface CompositeIdCrudDao extends CrudDao<CompositeIdEntity, CompositeId, CompositeIdCrudDao> {
    }

    static final class CompositeIdEntity {
        @Id
        private Long tenantId;
        @Id
        private Long rowId;

        CompositeIdEntity() {
        }

        CompositeIdEntity(final Long tenantId, final Long rowId) {
            this.tenantId = tenantId;
            this.rowId = rowId;
        }

        public Long getTenantId() {
            return tenantId;
        }

        public void setTenantId(final Long tenantId) {
            this.tenantId = tenantId;
        }

        public Long getRowId() {
            return rowId;
        }

        public void setRowId(final Long rowId) {
            this.rowId = rowId;
        }
    }

    // Like most hand-written composite id classes, this one does not override equals/hashCode.
    static final class CompositeId {
        private Long tenantId;
        private Long rowId;

        CompositeId() {
        }

        CompositeId(final Long tenantId, final Long rowId) {
            this.tenantId = tenantId;
            this.rowId = rowId;
        }

        public Long getTenantId() {
            return tenantId;
        }

        public void setTenantId(final Long tenantId) {
            this.tenantId = tenantId;
        }

        public Long getRowId() {
            return rowId;
        }

        public void setRowId(final Long rowId) {
            this.rowId = rowId;
        }
    }

    // Equal composite ids given as beans without equals() were only de-duplicated by identity, so duplicates straddling a
    // chunk boundary returned (and counted) the same row twice, contradicting "duplicate ids are treated as one".
    @Test
    public void testBatchGetAndCount_CompositeBeanIdsWithoutEquals_DeduplicatedByIdValues() throws SQLException {
        final CompositeIdCrudDao dao = Mockito.mock(CompositeIdCrudDao.class, Mockito.CALLS_REAL_METHODS);
        Mockito.doReturn(CompositeIdEntity.class).when(dao).targetEntityClass();
        // One row per OR-branch of the chunk condition, whose parameters are the (tenantId, rowId) pairs.
        Mockito.doAnswer(inv -> {
            final List<Object> params = ((Condition) inv.getArgument(1)).parameters();
            final List<CompositeIdEntity> result = new ArrayList<>();
            for (int i = 0; i < params.size(); i += 2) {
                result.add(new CompositeIdEntity((Long) params.get(i), (Long) params.get(i + 1)));
            }
            return result;
        }).when(dao).list(ArgumentMatchers.<Collection<String>> any(), ArgumentMatchers.any(Condition.class));
        Mockito.doAnswer(inv -> ((Condition) inv.getArgument(0)).parameters().size() / 2).when(dao).count(ArgumentMatchers.any(Condition.class));

        final List<CompositeId> ids = List.of(new CompositeId(1L, 2L), new CompositeId(3L, 4L), new CompositeId(1L, 2L));

        assertEquals(2, dao.batchGet(ids, null, 2).size());
        verify(dao, Mockito.times(1)).list(ArgumentMatchers.<Collection<String>> any(), ArgumentMatchers.any(Condition.class));

        // A Set holding such ids (distinct by identity only) is de-duplicated by id values too.
        assertEquals(2, dao.batchGet(new LinkedHashSet<>(ids), null, 2).size());
        verify(dao, Mockito.times(2)).list(ArgumentMatchers.<Collection<String>> any(), ArgumentMatchers.any(Condition.class));

        final List<CompositeId> manyIds = new ArrayList<>();
        for (long i = 0; i < JdbcUtil.DEFAULT_BATCH_SIZE; i++) {
            manyIds.add(new CompositeId(i, i));
        }
        manyIds.add(new CompositeId(0L, 0L)); // equal to the first id, but would fall into a second chunk

        assertEquals(JdbcUtil.DEFAULT_BATCH_SIZE, dao.count(manyIds));
        verify(dao, Mockito.times(1)).count(ArgumentMatchers.any(Condition.class));
    }

    interface BinaryKeyCrudDao extends CrudDao<BinaryKeyEntity, BinaryKeyId, BinaryKeyCrudDao> {
    }

    static final class BinaryKeyEntity {
        @Id
        private Long tenantId;
        @Id
        private byte[] uid;

        BinaryKeyEntity() {
        }

        BinaryKeyEntity(final Long tenantId, final byte[] uid) {
            this.tenantId = tenantId;
            this.uid = uid;
        }

        public Long getTenantId() {
            return tenantId;
        }

        public void setTenantId(final Long tenantId) {
            this.tenantId = tenantId;
        }

        public byte[] getUid() {
            return uid;
        }

        public void setUid(final byte[] uid) {
            this.uid = uid;
        }
    }

    // A composite id with a binary (e.g. BINARY(16) UUID) component, whose equals/hashCode compare that component by content.
    static final class BinaryKeyId {
        private Long tenantId;
        private byte[] uid;

        BinaryKeyId() {
        }

        BinaryKeyId(final Long tenantId, final byte[] uid) {
            this.tenantId = tenantId;
            this.uid = uid;
        }

        public Long getTenantId() {
            return tenantId;
        }

        public void setTenantId(final Long tenantId) {
            this.tenantId = tenantId;
        }

        public byte[] getUid() {
            return uid;
        }

        public void setUid(final byte[] uid) {
            this.uid = uid;
        }

        @Override
        public boolean equals(final Object obj) {
            return obj instanceof final BinaryKeyId other && java.util.Objects.equals(tenantId, other.tenantId) && Arrays.equals(uid, other.uid);
        }

        @Override
        public int hashCode() {
            return 31 * java.util.Objects.hashCode(tenantId) + Arrays.hashCode(uid);
        }
    }

    // BUG FIX: de-duplicating bean ids by their id property values compared a byte[] component by reference, so two equal
    // binary ids (equal by the id class's own equals, and de-duplicated before the by-value change) stayed apart: a
    // duplicate straddling a chunk boundary returned the same row twice and was counted twice.
    @Test
    public void testBatchGetAndCount_CompositeBeanIdsWithArrayComponent_DeduplicatedByContent() throws SQLException {
        final BinaryKeyCrudDao dao = Mockito.mock(BinaryKeyCrudDao.class, Mockito.CALLS_REAL_METHODS);
        Mockito.doReturn(BinaryKeyEntity.class).when(dao).targetEntityClass();
        // One row per OR-branch of the chunk condition, whose parameters are the (tenantId, uid) pairs.
        Mockito.doAnswer(inv -> {
            final List<Object> params = ((Condition) inv.getArgument(1)).parameters();
            final List<BinaryKeyEntity> result = new ArrayList<>();
            for (int i = 0; i < params.size(); i += 2) {
                result.add(new BinaryKeyEntity((Long) params.get(i), (byte[]) params.get(i + 1)));
            }
            return result;
        }).when(dao).list(ArgumentMatchers.<Collection<String>> any(), ArgumentMatchers.any(Condition.class));
        Mockito.doAnswer(inv -> ((Condition) inv.getArgument(0)).parameters().size() / 2).when(dao).count(ArgumentMatchers.any(Condition.class));

        // Separate but equal byte[] instances.
        final List<BinaryKeyId> ids = List.of(new BinaryKeyId(1L, new byte[] { 1, 2 }), new BinaryKeyId(3L, new byte[] { 3 }),
                new BinaryKeyId(1L, new byte[] { 1, 2 }));

        assertEquals(2, dao.batchGet(ids, null, 2).size());
        verify(dao, Mockito.times(1)).list(ArgumentMatchers.<Collection<String>> any(), ArgumentMatchers.any(Condition.class));

        final List<BinaryKeyId> manyIds = new ArrayList<>();
        for (long i = 0; i < JdbcUtil.DEFAULT_BATCH_SIZE; i++) {
            manyIds.add(new BinaryKeyId(i, new byte[] { (byte) i }));
        }
        manyIds.add(new BinaryKeyId(0L, new byte[] { 0 })); // equal to the first id, but would fall into a second chunk

        assertEquals(JdbcUtil.DEFAULT_BATCH_SIZE, dao.count(manyIds));
        verify(dao, Mockito.times(1)).count(ArgumentMatchers.any(Condition.class));
    }

    interface BinaryIdCrudDao extends CrudDao<BinaryIdEntity, byte[], BinaryIdCrudDao> {
    }

    static final class BinaryIdEntity {
        @Id
        private byte[] uid;

        BinaryIdEntity() {
        }

        BinaryIdEntity(final byte[] uid) {
            this.uid = uid;
        }

        public byte[] getUid() {
            return uid;
        }

        public void setUid(final byte[] uid) {
            this.uid = uid;
        }
    }

    // BUG FIX: a Set of single array ids (e.g. byte[] binary UUIDs collected from the rows of a join column) was copied as
    // is, but a Set keeps content-equal arrays apart: a duplicate straddling a chunk boundary returned the same row twice
    // and was counted twice, while the same ids in a List were de-duplicated by content.
    @Test
    public void testBatchGetAndCount_SetOfBinaryIds_DeduplicatedByContent() throws SQLException {
        final BinaryIdCrudDao dao = Mockito.mock(BinaryIdCrudDao.class, Mockito.CALLS_REAL_METHODS);
        Mockito.doReturn(BinaryIdEntity.class).when(dao).targetEntityClass();
        // One row per id in the chunk's IN list.
        Mockito.doAnswer(inv -> {
            final List<BinaryIdEntity> result = new ArrayList<>();
            for (final Object uid : ((Condition) inv.getArgument(1)).parameters()) {
                result.add(new BinaryIdEntity((byte[]) uid));
            }
            return result;
        }).when(dao).list(ArgumentMatchers.<Collection<String>> any(), ArgumentMatchers.any(Condition.class));
        Mockito.doAnswer(inv -> ((Condition) inv.getArgument(0)).parameters().size()).when(dao).count(ArgumentMatchers.any(Condition.class));

        final Set<byte[]> ids = new LinkedHashSet<>(List.of(new byte[] { 1, 2 }, new byte[] { 3 }, new byte[] { 1, 2 }));
        assertEquals(3, ids.size());

        assertEquals(2, dao.batchGet(ids, null, 2).size());
        verify(dao, Mockito.times(1)).list(ArgumentMatchers.<Collection<String>> any(), ArgumentMatchers.any(Condition.class));

        final Set<byte[]> manyIds = new LinkedHashSet<>();
        for (int i = 0; i < JdbcUtil.DEFAULT_BATCH_SIZE; i++) {
            manyIds.add(new byte[] { (byte) i, (byte) (i >> 8) });
        }
        manyIds.add(new byte[] { 0, 0 }); // equal to the first id, but would fall into a second chunk

        assertEquals(JdbcUtil.DEFAULT_BATCH_SIZE, dao.count(manyIds));
        verify(dao, Mockito.times(1)).count(ArgumentMatchers.any(Condition.class));
    }

    // BUG FIX: Map ids were de-duplicated by Map.equals, which compares an array value (a byte[] id component) by
    // reference, so a duplicate straddling a chunk boundary returned the same row twice.
    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Test
    public void testBatchGet_CompositeMapIdsWithArrayValue_DeduplicatedByContent() throws SQLException {
        final BinaryKeyCrudDao dao = Mockito.mock(BinaryKeyCrudDao.class, Mockito.CALLS_REAL_METHODS);
        Mockito.doReturn(BinaryKeyEntity.class).when(dao).targetEntityClass();
        // One row per OR-branch of the chunk condition (two parameters each).
        Mockito.doAnswer(inv -> {
            final List<BinaryKeyEntity> result = new ArrayList<>();
            for (int i = 0, size = ((Condition) inv.getArgument(1)).parameters().size(); i < size; i += 2) {
                result.add(new BinaryKeyEntity());
            }
            return result;
        }).when(dao).list(ArgumentMatchers.<Collection<String>> any(), ArgumentMatchers.any(Condition.class));

        final List mapIds = List.of(Map.of("tenantId", 1L, "uid", new byte[] { 1, 2 }), Map.of("tenantId", 3L, "uid", new byte[] { 3 }),
                Map.of("tenantId", 1L, "uid", new byte[] { 1, 2 }));

        assertEquals(2, dao.batchGet(mapIds, null, 2).size());
        verify(dao, Mockito.times(1)).list(ArgumentMatchers.<Collection<String>> any(), ArgumentMatchers.any(Condition.class));
    }

    // count(Collection) shares distinctIds with batchGet but chunks by JdbcUtil.DEFAULT_BATCH_SIZE: a Map id whose byte[]
    // value is content-equal to an earlier id (and would land in a second chunk) must be dropped, so each id is counted once
    // by a single count(Condition) call. Before the content-based key, Map.equals compared the arrays by reference.
    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Test
    public void testCount_CompositeMapIdsWithArrayValue_DeduplicatedByContent() throws SQLException {
        final BinaryKeyCrudDao dao = Mockito.mock(BinaryKeyCrudDao.class, Mockito.CALLS_REAL_METHODS);
        Mockito.doReturn(BinaryKeyEntity.class).when(dao).targetEntityClass();
        // One row per OR-branch of the chunk condition (two parameters each).
        Mockito.doAnswer(inv -> ((Condition) inv.getArgument(0)).parameters().size() / 2).when(dao).count(ArgumentMatchers.any(Condition.class));

        final List mapIds = new ArrayList<>();
        for (int i = 0; i < JdbcUtil.DEFAULT_BATCH_SIZE; i++) {
            mapIds.add(Map.of("tenantId", (long) i, "uid", new byte[] { (byte) i, (byte) (i >> 8) }));
        }
        mapIds.add(Map.of("tenantId", 0L, "uid", new byte[] { 0, 0 })); // equal to the first id, but would fall into a second chunk

        assertEquals(JdbcUtil.DEFAULT_BATCH_SIZE, dao.count(mapIds));
        verify(dao, Mockito.times(1)).count(ArgumentMatchers.any(Condition.class));
    }

    // Guard (passes before and after the bean-id de-duplication): EntityId composite ids keep being de-duplicated by their
    // own (deep) equals(), Map composite ids by their content, and neither is read as a bean.
    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Test
    public void testBatchGet_CompositeEntityIdAndMapIds_DeduplicatedByEquals() throws SQLException {
        final CompositeIdCrudDao dao = Mockito.mock(CompositeIdCrudDao.class, Mockito.CALLS_REAL_METHODS);
        Mockito.doReturn(CompositeIdEntity.class).when(dao).targetEntityClass();
        Mockito.doAnswer(inv -> {
            final List<Object> params = ((Condition) inv.getArgument(1)).parameters();
            final List<CompositeIdEntity> result = new ArrayList<>();
            for (int i = 0; i < params.size(); i += 2) {
                result.add(new CompositeIdEntity((Long) params.get(i), (Long) params.get(i + 1)));
            }
            return result;
        }).when(dao).list(ArgumentMatchers.<Collection<String>> any(), ArgumentMatchers.any(Condition.class));

        final List entityIds = List.of(EntityId.of("tenantId", 1L, "rowId", 2L), EntityId.of("tenantId", 3L, "rowId", 4L),
                EntityId.of("tenantId", 1L, "rowId", 2L));
        assertEquals(2, dao.batchGet(entityIds, null, 2).size());

        final List mapIds = List.of(Map.of("tenantId", 1L, "rowId", 2L), Map.of("tenantId", 3L, "rowId", 4L), Map.of("tenantId", 1L, "rowId", 2L));
        assertEquals(2, dao.batchGet(mapIds, null, 2).size());

        verify(dao, Mockito.times(2)).list(ArgumentMatchers.<Collection<String>> any(), ArgumentMatchers.any(Condition.class));
    }

    // BUG FIX: batchGet/count rejected a Map or bean id following an EntityId with a ClassCastException, while an EntityId
    // following a Map or bean id was an IllegalArgumentException; the exception type depended on the order of 'ids'.
    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Test
    public void testBatchGetAndCount_EntityIdMixedWithMapOrBeanIds_IllegalArgumentInEitherOrder() throws SQLException {
        final CompositeIdCrudDao dao = Mockito.mock(CompositeIdCrudDao.class, Mockito.CALLS_REAL_METHODS);
        Mockito.doReturn(CompositeIdEntity.class).when(dao).targetEntityClass();

        final EntityId entityId = EntityId.of("tenantId", 1L, "rowId", 2L);
        final Map<String, Object> map = Map.of("tenantId", 3L, "rowId", 4L);
        final CompositeId bean = new CompositeId(5L, 6L);

        for (final List ids : List.of(List.of(entityId, map), List.of(map, entityId), List.of(entityId, bean), List.of(bean, entityId))) {
            assertThrows(IllegalArgumentException.class, () -> dao.batchGet(ids, null, 5), String.valueOf(ids));
            assertThrows(IllegalArgumentException.class, () -> dao.batchGet(ids), String.valueOf(ids));
            assertThrows(IllegalArgumentException.class, () -> dao.count(ids), String.valueOf(ids));
        }

        verify(dao, never()).list(ArgumentMatchers.<Collection<String>> any(), ArgumentMatchers.any(Condition.class));
        verify(dao, never()).count(ArgumentMatchers.any(Condition.class));
    }

    // An entity whose single id is null matches no row. It used to abort the whole batch with a NullPointerException
    // ("element cannot be mapped to a null key") from the id grouping instead of just being left unrefreshed.
    @Test
    public void testBatchRefresh_NullSingleIdEntity_LeftUnrefreshedInsteadOfNpe() throws SQLException {
        final IdAnnotatedCrudDao dao = newDao();
        final IdAnnotatedEntity saved = new IdAnnotatedEntity(1L);
        saved.setName("stale");
        final IdAnnotatedEntity unsaved = new IdAnnotatedEntity(null);
        unsaved.setName("new");
        final IdAnnotatedEntity dbEntity = new IdAnnotatedEntity(1L);
        dbEntity.setName("fresh");
        Mockito.doReturn(List.of(dbEntity)).when(dao).list(ArgumentMatchers.<Collection<String>> any(), ArgumentMatchers.any(Condition.class));

        assertEquals(1, dao.batchRefresh(List.of(saved, unsaved), List.of("name"), 10));
        assertEquals("fresh", saved.getName());
        assertEquals("new", unsaved.getName());

        final ArgumentCaptor<Condition> condCaptor = ArgumentCaptor.forClass(Condition.class);
        verify(dao).list(ArgumentMatchers.<Collection<String>> any(), condCaptor.capture());
        assertEquals(List.of(1L), condCaptor.getValue().parameters());

        // Only null ids: nothing can match, so nothing is queried.
        assertEquals(0, dao.batchRefresh(List.of(unsaved)));
        assertEquals("new", unsaved.getName());
        verify(dao, Mockito.times(1)).list(ArgumentMatchers.<Collection<String>> any(), ArgumentMatchers.any(Condition.class));
    }

    @Test
    public void testCount_SumsAcrossChunksAndDeduplicates() throws SQLException {
        final IdAnnotatedCrudDao dao = newDao();
        final List<Long> ids = new ArrayList<>();
        for (long i = 0; i <= JdbcUtil.DEFAULT_BATCH_SIZE; i++) {
            ids.add(i);
        }
        ids.add(0L); // duplicate: must not create a third chunk or be counted twice

        Mockito.doAnswer(inv -> ((Condition) inv.getArgument(0)).parameters().size()).when(dao).count(ArgumentMatchers.any(Condition.class));

        assertEquals(JdbcUtil.DEFAULT_BATCH_SIZE + 1, dao.count(ids));

        final ArgumentCaptor<Condition> condCaptor = ArgumentCaptor.forClass(Condition.class);
        verify(dao, Mockito.times(2)).count(condCaptor.capture());
        assertEquals(JdbcUtil.DEFAULT_BATCH_SIZE, condCaptor.getAllValues().get(0).parameters().size());
        assertEquals(1, condCaptor.getAllValues().get(1).parameters().size());
    }

    @Test
    public void testCount_EmptyIds_ReturnsZeroWithoutQuerying() throws SQLException {
        final IdAnnotatedCrudDao dao = newDao();

        assertEquals(0, dao.count(List.of()));
        assertEquals(0, dao.count((Collection<Long>) null));
        verify(dao, never()).count(ArgumentMatchers.any(Condition.class));
    }
}
