/*
 * Copyright (c) 2025, Haiyang Li.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

@Tag("2025")
public class TransactionTest extends TestBase {

    private static final class StubTransaction implements Transaction {
        private final String id;
        private final IsolationLevel isolationLevel;
        private Status status;

        private boolean commitCalled;
        private boolean rollbackCalled;
        private boolean rollbackIfNotCommittedCalled;

        StubTransaction(final String id, final IsolationLevel isolationLevel, final Status status) {
            this.id = id;
            this.isolationLevel = isolationLevel;
            this.status = status;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public IsolationLevel isolationLevel() {
            return isolationLevel;
        }

        @Override
        public Status status() {
            return status;
        }

        @Override
        public boolean isActive() {
            return status == Status.ACTIVE;
        }

        @Override
        public void commit() {
            commitCalled = true;
            status = Status.COMMITTED;
        }

        @Override
        public void rollback() {
            rollbackCalled = true;
            status = Status.ROLLED_BACK;
        }

        @Override
        public void rollbackIfNotCommitted() {
            rollbackIfNotCommittedCalled = true;
            if (status != Status.COMMITTED) {
                status = Status.ROLLED_BACK;
            }
        }
    }

    @Test
    public void testId() {
        final StubTransaction transaction = new StubTransaction("txn-1", IsolationLevel.READ_COMMITTED, Transaction.Status.ACTIVE);

        assertEquals("txn-1", transaction.id());
    }

    @Test
    public void testIsolationLevel() {
        final StubTransaction transaction = new StubTransaction("txn-1", IsolationLevel.SERIALIZABLE, Transaction.Status.ACTIVE);

        assertEquals(IsolationLevel.SERIALIZABLE, transaction.isolationLevel());
    }

    @Test
    public void testStatus() {
        final StubTransaction transaction = new StubTransaction("txn-1", IsolationLevel.READ_COMMITTED, Transaction.Status.MARKED_ROLLBACK);

        assertEquals(Transaction.Status.MARKED_ROLLBACK, transaction.status());
    }

    @Test
    public void testIsActive() {
        assertTrue(new StubTransaction("txn-1", IsolationLevel.READ_COMMITTED, Transaction.Status.ACTIVE).isActive());
    }

    @Test
    public void testIsActive_InactiveStatus() {
        assertFalse(new StubTransaction("txn-1", IsolationLevel.READ_COMMITTED, Transaction.Status.COMMITTED).isActive());
    }

    @Test
    public void testCommit() {
        final StubTransaction transaction = new StubTransaction("txn-1", IsolationLevel.READ_COMMITTED, Transaction.Status.ACTIVE);

        transaction.commit();

        assertTrue(transaction.commitCalled);
        assertEquals(Transaction.Status.COMMITTED, transaction.status());
    }

    @Test
    public void testRollback() {
        final StubTransaction transaction = new StubTransaction("txn-1", IsolationLevel.READ_COMMITTED, Transaction.Status.ACTIVE);

        transaction.rollback();

        assertTrue(transaction.rollbackCalled);
        assertEquals(Transaction.Status.ROLLED_BACK, transaction.status());
    }

    @Test
    public void testRollbackIfNotCommitted() {
        final StubTransaction transaction = new StubTransaction("txn-1", IsolationLevel.READ_COMMITTED, Transaction.Status.ACTIVE);

        transaction.rollbackIfNotCommitted();

        assertTrue(transaction.rollbackIfNotCommittedCalled);
        assertEquals(Transaction.Status.ROLLED_BACK, transaction.status());
    }

    @Test
    public void testRollbackIfNotCommitted_CommittedTransaction() {
        final StubTransaction transaction = new StubTransaction("txn-1", IsolationLevel.READ_COMMITTED, Transaction.Status.COMMITTED);

        transaction.rollbackIfNotCommitted();

        assertTrue(transaction.rollbackIfNotCommittedCalled);
        assertEquals(Transaction.Status.COMMITTED, transaction.status());
    }

    // Tests for Transaction.Status enum

    @Test
    public void testStatusValues() {
        final Transaction.Status[] values = Transaction.Status.values();
        assertNotNull(values);
        assertEquals(6, values.length);
    }

    @Test
    public void testStatusValueOfString() {
        assertEquals(Transaction.Status.ACTIVE, Transaction.Status.valueOf("ACTIVE"));
        assertEquals(Transaction.Status.MARKED_ROLLBACK, Transaction.Status.valueOf("MARKED_ROLLBACK"));
        assertEquals(Transaction.Status.COMMITTED, Transaction.Status.valueOf("COMMITTED"));
        assertEquals(Transaction.Status.FAILED_COMMIT, Transaction.Status.valueOf("FAILED_COMMIT"));
        assertEquals(Transaction.Status.ROLLED_BACK, Transaction.Status.valueOf("ROLLED_BACK"));
        assertEquals(Transaction.Status.FAILED_ROLLBACK, Transaction.Status.valueOf("FAILED_ROLLBACK"));
    }

    @Test
    public void testStatusValueOfStringInvalid() {
        assertThrows(IllegalArgumentException.class, () -> Transaction.Status.valueOf("INVALID"));
        assertThrows(IllegalArgumentException.class, () -> Transaction.Status.valueOf("active"));
        assertThrows(IllegalArgumentException.class, () -> Transaction.Status.valueOf(""));
    }

    @Test
    public void testStatusName() {
        assertEquals("ACTIVE", Transaction.Status.ACTIVE.name());
        assertEquals("MARKED_ROLLBACK", Transaction.Status.MARKED_ROLLBACK.name());
        assertEquals("COMMITTED", Transaction.Status.COMMITTED.name());
        assertEquals("FAILED_COMMIT", Transaction.Status.FAILED_COMMIT.name());
        assertEquals("ROLLED_BACK", Transaction.Status.ROLLED_BACK.name());
        assertEquals("FAILED_ROLLBACK", Transaction.Status.FAILED_ROLLBACK.name());
    }

    @Test
    public void testStatusOrdinal() {
        assertEquals(0, Transaction.Status.ACTIVE.ordinal());
        assertEquals(1, Transaction.Status.MARKED_ROLLBACK.ordinal());
        assertEquals(2, Transaction.Status.COMMITTED.ordinal());
        assertEquals(3, Transaction.Status.FAILED_COMMIT.ordinal());
        assertEquals(4, Transaction.Status.ROLLED_BACK.ordinal());
        assertEquals(5, Transaction.Status.FAILED_ROLLBACK.ordinal());
    }

    @Test
    public void testStatusToString() {
        assertEquals("ACTIVE", Transaction.Status.ACTIVE.toString());
        assertEquals("COMMITTED", Transaction.Status.COMMITTED.toString());
        assertEquals("ROLLED_BACK", Transaction.Status.ROLLED_BACK.toString());
    }

    @Test
    public void testStatusCompareTo() {
        assertTrue(Transaction.Status.ACTIVE.compareTo(Transaction.Status.COMMITTED) < 0);
        assertTrue(Transaction.Status.FAILED_ROLLBACK.compareTo(Transaction.Status.ACTIVE) > 0);
        assertEquals(0, Transaction.Status.COMMITTED.compareTo(Transaction.Status.COMMITTED));
    }

    @Test
    public void testStatusHashCode() {
        assertEquals(Transaction.Status.ACTIVE.hashCode(), Transaction.Status.valueOf("ACTIVE").hashCode());
    }

    @Test
    public void testStatusDeclaringClass() {
        assertEquals(Transaction.Status.class, Transaction.Status.ACTIVE.getDeclaringClass());
    }

    @Test
    public void testStatusRoundTripName() {
        for (final Transaction.Status status : Transaction.Status.values()) {
            assertEquals(status, Transaction.Status.valueOf(status.name()));
        }
    }

    // Tests for Transaction.Action enum

    @Test
    public void testActionValues() {
        final Transaction.Action[] values = Transaction.Action.values();
        assertNotNull(values);
        assertEquals(2, values.length);
    }

    @Test
    public void testActionValueOfString() {
        assertEquals(Transaction.Action.COMMIT, Transaction.Action.valueOf("COMMIT"));
        assertEquals(Transaction.Action.ROLLBACK, Transaction.Action.valueOf("ROLLBACK"));
    }

    @Test
    public void testActionValueOfStringInvalid() {
        assertThrows(IllegalArgumentException.class, () -> Transaction.Action.valueOf("INVALID"));
        assertThrows(IllegalArgumentException.class, () -> Transaction.Action.valueOf("commit"));
        assertThrows(IllegalArgumentException.class, () -> Transaction.Action.valueOf(""));
    }

    @Test
    public void testActionName() {
        assertEquals("COMMIT", Transaction.Action.COMMIT.name());
        assertEquals("ROLLBACK", Transaction.Action.ROLLBACK.name());
    }

    @Test
    public void testActionOrdinal() {
        assertEquals(0, Transaction.Action.COMMIT.ordinal());
        assertEquals(1, Transaction.Action.ROLLBACK.ordinal());
    }

    @Test
    public void testActionToString() {
        assertEquals("COMMIT", Transaction.Action.COMMIT.toString());
        assertEquals("ROLLBACK", Transaction.Action.ROLLBACK.toString());
    }

    @Test
    public void testActionCompareTo() {
        assertTrue(Transaction.Action.COMMIT.compareTo(Transaction.Action.ROLLBACK) < 0);
        assertTrue(Transaction.Action.ROLLBACK.compareTo(Transaction.Action.COMMIT) > 0);
        assertEquals(0, Transaction.Action.COMMIT.compareTo(Transaction.Action.COMMIT));
    }

    @Test
    public void testActionHashCode() {
        assertEquals(Transaction.Action.COMMIT.hashCode(), Transaction.Action.valueOf("COMMIT").hashCode());
    }

    @Test
    public void testActionDeclaringClass() {
        assertEquals(Transaction.Action.class, Transaction.Action.COMMIT.getDeclaringClass());
    }

    @Test
    public void testActionRoundTripName() {
        for (final Transaction.Action action : Transaction.Action.values()) {
            assertEquals(action, Transaction.Action.valueOf(action.name()));
        }
    }
}
