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
import com.landawn.abacus.util.u.Optional;

/**
 * Regression tests for the default methods of {@link CrudReadOps}: {@code get} wrapping, {@code notExists},
 * and the id-chunking / de-duplication / duplicate-row detection of {@code batchGet} and {@code count(ids)}.
 */
public class CrudReadOpsTest extends TestBase {

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
