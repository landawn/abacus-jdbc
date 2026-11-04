package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.BeanInfo;
import com.landawn.abacus.parser.ParserUtil.PropInfo;

class RollbackActionLogIdRestoreTest extends TestBase {

    @Test
    void run_failingSetter_stillRestoresOtherIdPartsAndEntities() {
        final Entity first = new Entity();
        final Entity second = new Entity();
        final RollbackActionLog.IdSnapshot snapshot = snapshot(first, second);
        first.storedId = 1L;
        first.storedPart = 2;
        second.storedId = 3L;
        second.storedPart = 4;
        snapshot.recordWrittenValues();
        first.setterFailure = new IllegalStateException("setter failed");

        final RuntimeException failure = assertThrows(RuntimeException.class, snapshot::run);

        assertSame(first.setterFailure, rootCause(failure));
        assertEquals(1L, first.storedId);
        assertEquals(0, first.storedPart);
        assertNull(second.storedId);
        assertEquals(0, second.storedPart);
    }

    @Test
    void run_failingWrittenValueCheck_stillRestoresLaterEntities() {
        final Entity first = new Entity();
        final Entity second = new Entity();
        final RollbackActionLog.IdSnapshot snapshot = snapshot(first, second);
        first.storedId = 1L;
        second.storedId = 2L;
        snapshot.recordWrittenValues();
        first.getterFailure = new IllegalStateException("getter failed");

        final RuntimeException failure = assertThrows(RuntimeException.class, snapshot::run);

        assertSame(first.getterFailure, rootCause(failure));
        assertEquals(1L, first.storedId);
        assertNull(second.storedId);
    }

    @Test
    void run_multipleFailures_reportsSuppressedFailuresAfterRestoringRemainingEntities() {
        final Entity first = new Entity();
        final Entity second = new Entity();
        final Entity third = new Entity();
        final RollbackActionLog.IdSnapshot snapshot = snapshot(first, second, third);
        first.storedId = 1L;
        second.storedId = 2L;
        third.storedId = 3L;
        first.setterFailure = new IllegalStateException("first setter failed");
        second.setterFailure = new IllegalStateException("second setter failed");

        final RuntimeException failure = assertThrows(RuntimeException.class, snapshot::run);

        assertSame(first.setterFailure, rootCause(failure));
        assertEquals(1, failure.getSuppressed().length);
        assertSame(second.setterFailure, rootCause(failure.getSuppressed()[0]));
        assertNull(third.storedId);
    }

    // An accessor can pass a checked exception through that the ID getter or setter throws without declaring it (Kotlin,
    // through @AccessFieldByMethod; a sneaky throw): the reflectasm-backed property accessor does not wrap it, as the
    // reflective one does. It escaped the RuntimeException | Error catch at once, leaving the IDs of the later entities
    // assigned.
    @Test
    void run_undeclaredCheckedSetterFailure_stillRestoresLaterEntitiesAndIsRethrownAsIs() {
        final Holder first = new Holder();
        final Holder second = new Holder();
        final RollbackActionLog.IdSnapshot snapshot = passThroughSnapshot(first, second);
        first.id = 1L;
        second.id = 2L;
        snapshot.recordWrittenValues();
        first.setterFailure = new IOException("setter failed");

        assertSame(first.setterFailure, assertThrows(IOException.class, snapshot::run));

        assertEquals(1L, first.id);
        assertNull(second.id);
    }

    // A getter's undeclared checked exception escaped recordWrittenValues at once, leaving nothing recorded: the rollback then
    // put back the captured IDs of every entity, overwriting an ID the application had set since.
    @Test
    void recordWrittenValues_undeclaredCheckedGetterFailure_stillRecordsTheOtherEntities() {
        final Holder first = new Holder();
        final Holder second = new Holder();
        final RollbackActionLog.IdSnapshot snapshot = passThroughSnapshot(first, second);
        first.id = 1L;
        second.id = 2L;
        first.getterFailure = new IOException("getter failed");

        assertSame(first.getterFailure, assertThrows(IOException.class, snapshot::recordWrittenValues));

        first.getterFailure = null;
        second.id = 99L; // set by the application after the insert

        snapshot.run();

        assertEquals(1L, first.id); // its written values are unknown: left alone
        assertEquals(99L, second.id);
    }

    /**
     * A snapshot of the {@code id} of {@code holders} through a property accessor that passes every failure of the holder
     * through unwrapped.
     */
    private static RollbackActionLog.IdSnapshot passThroughSnapshot(final Holder... holders) {
        final PropInfo idInfo = mock(PropInfo.class);
        when(idInfo.getPropValue(any())).thenAnswer(invocation -> {
            final Holder holder = invocation.getArgument(0);

            if (holder.getterFailure != null) {
                throw holder.getterFailure;
            }

            return holder.id;
        });
        doAnswer(invocation -> {
            final Holder holder = invocation.getArgument(0);

            if (holder.setterFailure != null) {
                throw holder.setterFailure;
            }

            holder.id = invocation.getArgument(1);
            return null;
        }).when(idInfo).setPropValue(any(), any());

        final BeanInfo beanInfo = mock(BeanInfo.class);
        when(beanInfo.getPropInfo("id")).thenReturn(idInfo);

        return RollbackActionLog.captureIdValues(List.of(holders), List.of("id"), beanInfo);
    }

    private static final class Holder {
        private Object id;
        private Exception getterFailure;
        private Exception setterFailure;
    }

    private static RollbackActionLog.IdSnapshot snapshot(final Entity... entities) {
        return RollbackActionLog.captureIdValues(List.of(entities), List.of("id", "part"), ParserUtil.getBeanInfo(Entity.class));
    }

    private static Throwable rootCause(final Throwable failure) {
        Throwable result = failure;
        while (result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }

    public static final class Entity {
        private Long storedId;
        private int storedPart;
        private RuntimeException getterFailure;
        private RuntimeException setterFailure;

        public Long getId() {
            if (getterFailure != null) {
                throw getterFailure;
            }
            return storedId;
        }

        public void setId(final Long id) {
            if (setterFailure != null) {
                throw setterFailure;
            }
            this.storedId = id;
        }

        public int getPart() {
            return storedPart;
        }

        public void setPart(final int part) {
            this.storedPart = part;
        }
    }
}
