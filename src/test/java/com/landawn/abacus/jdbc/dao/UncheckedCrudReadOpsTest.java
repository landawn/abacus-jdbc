package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

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
 * Regression tests for the default methods of {@link UncheckedCrudReadOps}, mirroring {@link CrudReadOpsTest}
 * so the unchecked twin keeps the same {@code get}/{@code notExists}/{@code batchGet}/{@code count(ids)} semantics.
 */
public class UncheckedCrudReadOpsTest extends TestBase {

    interface IdAnnotatedUncheckedCrudDao extends UncheckedCrudDao<IdAnnotatedEntity, Long, IdAnnotatedUncheckedCrudDao> {
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

    private static IdAnnotatedUncheckedCrudDao newDao() {
        final IdAnnotatedUncheckedCrudDao dao = Mockito.mock(IdAnnotatedUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        Mockito.doReturn(IdAnnotatedEntity.class).when(dao).targetEntityClass();
        return dao;
    }

    private static void stubListByChunkIds(final IdAnnotatedUncheckedCrudDao dao) {
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
    public void testGet_EmptyWhenGetOrNullReturnsNull() {
        final IdAnnotatedUncheckedCrudDao dao = newDao();
        when(dao.getOrNull(1L)).thenReturn(null);

        final Optional<IdAnnotatedEntity> result = dao.get(1L);

        assertFalse(result.isPresent());
        verify(dao).getOrNull(1L);
    }

    @Test
    public void testGet_WithSelectPropNames_DelegatesToGetOrNullWithSameProps() {
        final IdAnnotatedUncheckedCrudDao dao = newDao();
        final IdAnnotatedEntity entity = new IdAnnotatedEntity(2L);
        final List<String> props = List.of("id", "name");
        when(dao.getOrNull(2L, props)).thenReturn(entity);

        assertSame(entity, dao.get(2L, props).orElseNull());
        assertFalse(dao.get(3L, props).isPresent());
        verify(dao).getOrNull(2L, props);
        verify(dao).getOrNull(3L, props);
    }

    @Test
    public void testNotExists_NegatesExistsById() {
        final IdAnnotatedUncheckedCrudDao dao = newDao();
        when(dao.exists(7L)).thenReturn(true, false);

        assertFalse(dao.notExists(7L));
        assertTrue(dao.notExists(7L));
    }

    @Test
    public void testBatchGet_EmptyIds_ReturnsEmptyWithoutQuerying() {
        final IdAnnotatedUncheckedCrudDao dao = newDao();

        assertEquals(List.of(), dao.batchGet(List.of(), null, 10));
        assertEquals(List.of(), dao.batchGet((Collection<Long>) null, null, 10));
        verify(dao, never()).list(ArgumentMatchers.<Collection<String>> any(), ArgumentMatchers.any(Condition.class));
    }

    @Test
    public void testBatchGet_NonPositiveBatchSize_Rejected() {
        final IdAnnotatedUncheckedCrudDao dao = newDao();

        assertThrows(IllegalArgumentException.class, () -> dao.batchGet(List.of(), null, 0));
        assertThrows(IllegalArgumentException.class, () -> dao.batchGet(List.of(1L), -1));
    }

    @Test
    public void testBatchGet_ChunksIdsByBatchSizeAndPreservesChunkOrder() {
        final IdAnnotatedUncheckedCrudDao dao = newDao();
        stubListByChunkIds(dao);
        final List<Long> ids = List.of(1L, 2L, 3L, 4L, 5L);

        assertEquals(ids, idsOf(dao.batchGet(ids, List.of("id"), 2)));

        final ArgumentCaptor<Condition> condCaptor = ArgumentCaptor.forClass(Condition.class);
        verify(dao, Mockito.times(3)).list(ArgumentMatchers.eq(List.of("id")), condCaptor.capture());
        final List<Condition> chunks = condCaptor.getAllValues();
        assertEquals(List.of(1L, 2L), chunks.get(0).parameters());
        assertEquals(List.of(3L, 4L), chunks.get(1).parameters());
        assertEquals(List.of(5L), chunks.get(2).parameters());
    }

    @Test
    public void testBatchGet_DuplicateIdsAcrossChunks_QueriedOnceAndReturnedOnce() {
        final IdAnnotatedUncheckedCrudDao dao = newDao();
        stubListByChunkIds(dao);

        assertEquals(List.of(1L, 2L), idsOf(dao.batchGet(List.of(1L, 2L, 1L), 2)));

        final ArgumentCaptor<Condition> condCaptor = ArgumentCaptor.forClass(Condition.class);
        verify(dao, Mockito.times(1)).list(ArgumentMatchers.<Collection<String>> isNull(), condCaptor.capture());
        assertEquals(List.of(1L, 2L), condCaptor.getValue().parameters());
    }

    @Test
    public void testBatchGet_ChunkReturningMoreRowsThanItsIds_ThrowsDuplicateResult() {
        final IdAnnotatedUncheckedCrudDao dao = newDao();
        Mockito.doReturn(List.of(new IdAnnotatedEntity(1L), new IdAnnotatedEntity(1L)))
                .when(dao)
                .list(ArgumentMatchers.<Collection<String>> any(), ArgumentMatchers.any(Condition.class));

        assertThrows(DuplicateResultException.class, () -> dao.batchGet(List.of(1L), null, 5));
        assertThrows(DuplicateResultException.class, () -> dao.batchGet(List.of(1L)));
    }

    // Mirrors CrudReadOpsTest: a null single id leaves that entity unrefreshed instead of failing the batch with an NPE.
    @Test
    public void testBatchRefresh_NullSingleIdEntity_LeftUnrefreshedInsteadOfNpe() {
        final IdAnnotatedUncheckedCrudDao dao = newDao();
        final IdAnnotatedEntity saved = new IdAnnotatedEntity(1L);
        saved.setName("stale");
        final IdAnnotatedEntity unsaved = new IdAnnotatedEntity(null);
        unsaved.setName("new");
        final IdAnnotatedEntity dbEntity = new IdAnnotatedEntity(1L);
        dbEntity.setName("fresh");
        Mockito.doReturn(List.of(dbEntity)).when(dao).list(ArgumentMatchers.<Collection<String>> any(), ArgumentMatchers.any(Condition.class));

        assertEquals(1, dao.batchRefresh(List.of(saved, unsaved), List.of("name")));
        assertEquals("fresh", saved.getName());
        assertEquals("new", unsaved.getName());
        assertEquals(0, dao.batchRefresh(List.of(unsaved), 5));
        verify(dao, Mockito.times(1)).list(ArgumentMatchers.<Collection<String>> any(), ArgumentMatchers.any(Condition.class));
    }

    @Test
    public void testCount_SumsAcrossChunksAndDeduplicates() {
        final IdAnnotatedUncheckedCrudDao dao = newDao();
        final List<Long> ids = new ArrayList<>();
        for (long i = 0; i <= JdbcUtil.DEFAULT_BATCH_SIZE; i++) {
            ids.add(i);
        }
        ids.add(0L); // duplicate: must not create a third chunk or be counted twice

        Mockito.doAnswer(inv -> ((Condition) inv.getArgument(0)).parameters().size()).when(dao).count(ArgumentMatchers.any(Condition.class));

        assertEquals(JdbcUtil.DEFAULT_BATCH_SIZE + 1, dao.count(ids));
        verify(dao, Mockito.times(2)).count(ArgumentMatchers.any(Condition.class));
    }

    @Test
    public void testCount_EmptyIds_ReturnsZeroWithoutQuerying() {
        final IdAnnotatedUncheckedCrudDao dao = newDao();

        assertEquals(0, dao.count(List.of()));
        verify(dao, never()).count(ArgumentMatchers.any(Condition.class));
    }
}
