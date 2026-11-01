package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.parser.ParserUtil;

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
