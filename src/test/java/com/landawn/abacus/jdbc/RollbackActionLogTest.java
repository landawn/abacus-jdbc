package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.BeanInfo;

class RollbackActionLogTest extends TestBase {

    private static final List<String> KEY_ID_PROPS = Arrays.asList("orderId", "lineNum");
    private static final BeanInfo KEY_INFO = ParserUtil.getBeanInfo(Key.class);

    @Test
    void runsActionsNewestFirstAndAtMostOnce() {
        final RollbackActionLog log = new RollbackActionLog();
        final List<Integer> ran = new ArrayList<>();

        log.add(() -> ran.add(1));
        log.add(() -> ran.add(2));
        log.add(() -> ran.add(3));
        log.runNewestFirst(e -> fail(e));

        assertEquals(List.of(3, 2, 1), ran);
        assertEquals(0, log.size());

        log.runNewestFirst(e -> fail(e));
        assertEquals(List.of(3, 2, 1), ran);
    }

    @Test
    void failingActionDoesNotStopTheOthers() {
        final RollbackActionLog log = new RollbackActionLog();
        final List<String> ran = new ArrayList<>();
        final List<Throwable> failures = new ArrayList<>();
        final IllegalStateException failure = new IllegalStateException("restore failed");

        log.add(() -> ran.add("first"));
        log.add(() -> {
            throw failure;
        });
        log.add(() -> ran.add("last"));
        log.runNewestFirst(failures::add);

        assertEquals(List.of("last", "first"), ran);
        assertEquals(List.of(failure), failures);
    }

    // Every changed part of a composite ID is put back, and an unchanged ID is not written (so immutable entities don't fail).
    @Test
    void idSnapshotRestoresOnlyChangedIdParts() {
        final Key changed = key(10L, 0);
        final Key unchanged = key(20L, 2);

        final Runnable restorer = RollbackActionLog.captureIdValues(List.of(changed, unchanged), KEY_ID_PROPS, KEY_INFO);
        changed.setOrderId(11L);
        changed.setLineNum(7);
        restorer.run();

        assertEquals(10L, changed.getOrderId());
        assertEquals(0, changed.getLineNum());
        assertEquals(20L, unchanged.getOrderId());
        assertEquals(2, unchanged.getLineNum());

        final BeanInfo recordInfo = ParserUtil.getBeanInfo(KeyRecord.class);
        final KeyRecord immutable = new KeyRecord(30L, 3);
        assertThrows(RuntimeException.class, () -> recordInfo.getPropInfo("orderId").setPropValue(immutable, 30L));
        assertDoesNotThrow(() -> RollbackActionLog.captureIdValues(List.of(immutable), KEY_ID_PROPS, recordInfo).run());
    }

    // Once the values an insert wrote are recorded, a restore leaves an entity alone whose ID has changed since (the application
    // set another one); a later record replaces the earlier one, and an entity collected meanwhile needs nothing.
    @Test
    void idSnapshotLeavesAnIdChangedSinceTheRecordedWrite() {
        final Key changed = key(null, 0);
        final Key kept = key(null, 0);
        final RollbackActionLog.IdSnapshot snapshot = RollbackActionLog.captureIdValues(List.of(changed, kept), KEY_ID_PROPS, KEY_INFO);

        changed.setOrderId(1L);
        kept.setOrderId(2L);
        snapshot.recordWrittenValues();
        kept.setOrderId(3L); // a second write by the same insert (e.g. the database's keys after generated ones)
        snapshot.recordWrittenValues();
        changed.setOrderId(99L); // set by the application since

        snapshot.run();

        assertEquals(99L, changed.getOrderId(), "the ID the application set is kept");
        assertNull(kept.getOrderId(), "the written ID is put back");

        // Without a recorded write (an ID setter that failed part-way), every captured value is put back.
        final Key partial = key(null, 0);
        final RollbackActionLog.IdSnapshot unrecorded = RollbackActionLog.captureIdValues(List.of(partial), KEY_ID_PROPS, KEY_INFO);
        partial.setOrderId(5L);
        unrecorded.run();
        assertNull(partial.getOrderId());
    }

    // Two snapshots of one entity (inserted twice in a transaction), run newest first, put back the ID it had before each insert.
    @Test
    void idSnapshotsOfOneEntityRunNewestFirst() {
        final Key entity = key(null, 0);
        final RollbackActionLog log = new RollbackActionLog();

        final RollbackActionLog.IdSnapshot first = RollbackActionLog.captureIdValues(List.of(entity), KEY_ID_PROPS, KEY_INFO);
        log.add(first);
        entity.setOrderId(1L);
        first.recordWrittenValues();

        final RollbackActionLog.IdSnapshot second = RollbackActionLog.captureIdValues(List.of(entity), KEY_ID_PROPS, KEY_INFO);
        log.add(second);
        entity.setOrderId(2L);
        second.recordWrittenValues();

        log.runNewestFirst(e -> fail(e));
        assertNull(entity.getOrderId());
    }

    // A null entity failed with a NullPointerException while its ID values were read; it is an invalid argument.
    @Test
    void captureIdValuesRejectsANullEntity() {
        assertThrows(IllegalArgumentException.class, () -> RollbackActionLog.captureIdValues(Arrays.asList(key(1L, 1), null), KEY_ID_PROPS, KEY_INFO));
    }

    // The snapshots of an entity changed twice overlap: run oldest first, the second one put back the ID written by the first
    // (rolled-back) insert. Run newest first, the value from before both changes wins.
    @Test
    void overlappingIdSnapshotsRestoreTheValueFromBeforeTheFirstChange() {
        final RollbackActionLog log = new RollbackActionLog();
        final Key key = new Key();

        log.add(RollbackActionLog.captureIdValues(List.of(key), KEY_ID_PROPS, KEY_INFO));
        key.setOrderId(1L);
        key.setLineNum(1);
        log.add(RollbackActionLog.captureIdValues(List.of(key, key), KEY_ID_PROPS, KEY_INFO));
        key.setOrderId(2L);
        key.setLineNum(2);
        log.runNewestFirst(e -> fail(e));

        assertNull(key.getOrderId());
        assertEquals(0, key.getLineNum());
    }

    // Snapshots hold their entities weakly: once nothing else references them there is nothing to restore, and a long
    // transaction that inserts many short-lived entities drops their snapshots instead of keeping one per insert.
    @Test
    void snapshotsOfCollectedEntitiesArePurged() throws InterruptedException {
        final RollbackActionLog log = new RollbackActionLog();
        final Key live = new Key();
        log.add(RollbackActionLog.captureIdValues(List.of(live), KEY_ID_PROPS, KEY_INFO));
        final List<WeakReference<Key>> collected = addSnapshotsOfUnreferencedKeys(log, RollbackActionLog.MIN_PURGE_THRESHOLD - 1);
        assertEquals(RollbackActionLog.MIN_PURGE_THRESHOLD, log.size());

        for (int attempt = 0; attempt < 100 && collected.stream().anyMatch(ref -> ref.get() != null); attempt++) {
            System.gc();
            Thread.sleep(10);
        }

        assertTrue(collected.stream().allMatch(ref -> ref.get() == null), "the unreferenced keys were not collected");

        live.setOrderId(5L);
        log.add(() -> {
        });

        assertEquals(2, log.size(), "only the live entity's snapshot and the new action remain");
        log.runNewestFirst(e -> fail(e));
        assertNull(live.getOrderId());
    }

    // A rollback to a savepoint undoes the work since it: the actions added after its mark run (newest first) and go, those
    // added before stay for the transaction's outcome, and so do the marks of other savepoints.
    @Test
    void runNewestFirstSinceRunsOnlyTheActionsAddedAfterTheSavepointMark() {
        final RollbackActionLog log = new RollbackActionLog();
        final List<String> ran = new ArrayList<>();
        final Object outerSavepoint = new Object();
        final Object savepoint = new Object();
        final Object laterSavepoint = new Object();

        log.add(() -> ran.add("before"));
        log.mark(outerSavepoint);
        log.add(() -> ran.add("outer"));
        log.mark(savepoint);
        log.add("ds", () -> ran.add("first"));
        log.mark(laterSavepoint); // a savepoint created, and released or rolled back, inside the nested transaction
        log.add(() -> ran.add("second"));
        assertEquals(4, log.size(), "marks are not actions");

        log.runNewestFirstSince(savepoint, e -> fail(e));

        assertEquals(List.of("second", "first"), ran);
        assertEquals(2, log.size());

        // The mark stays (a programmatic savepoint stays usable after a rollback to it): a second rollback to the savepoint runs
        // only what was added since, never the actions before the mark (the mark used to go with the actions, and the second
        // rollback then ran everything).
        ran.clear();
        log.runNewestFirstSince(savepoint, e -> fail(e));
        assertTrue(ran.isEmpty());
        log.add(() -> ran.add("third"));
        log.runNewestFirstSince(savepoint, e -> fail(e));
        assertEquals(List.of("third"), ran);
        assertEquals(2, log.size());

        ran.clear();
        log.runNewestFirstSince(outerSavepoint, e -> fail(e));
        assertEquals(List.of("outer"), ran);
        assertEquals(1, log.size());

        // A savepoint the log has no mark for (created before the log existed): every action belongs to work since it.
        ran.clear();
        log.runNewestFirstSince(new Object(), e -> fail(e));
        assertEquals(List.of("before"), ran);
        assertEquals(0, log.size());

        assertThrows(IllegalArgumentException.class, () -> log.mark(null));
    }

    // The tagged run and the purge leave the savepoint marks in place, so a later rollback to the savepoint still finds them.
    @Test
    void savepointMarksSurviveTaggedRunsAndPurges() throws InterruptedException {
        final RollbackActionLog log = new RollbackActionLog();
        final List<String> ran = new ArrayList<>();
        final Object savepoint = new Object();
        final Key live = new Key();

        log.add(() -> ran.add("before"));
        log.mark(savepoint);
        log.add("ds", () -> ran.add("tagged"));
        log.add(RollbackActionLog.captureIdValues(List.of(live), KEY_ID_PROPS, KEY_INFO));

        log.runNewestFirst("ds", e -> fail(e));
        assertEquals(List.of("tagged"), ran);

        // Fills the log up to the purge threshold (the mark counts as an entry there); the next add purges.
        final List<WeakReference<Key>> collected = addSnapshotsOfUnreferencedKeys(log, RollbackActionLog.MIN_PURGE_THRESHOLD - 3);

        for (int attempt = 0; attempt < 100 && collected.stream().anyMatch(ref -> ref.get() != null); attempt++) {
            System.gc();
            Thread.sleep(10);
        }

        assertTrue(collected.stream().allMatch(ref -> ref.get() == null), "the unreferenced keys were not collected");
        live.setOrderId(7L);
        log.add(() -> ran.add("after purge")); // triggers the purge
        assertEquals(3, log.size(), "'before', the live snapshot and the new action");

        ran.clear();
        log.runNewestFirstSince(savepoint, e -> fail(e));
        assertEquals(List.of("after purge"), ran);
        assertNull(live.getOrderId(), "the snapshot since the savepoint was restored");
        assertEquals(1, log.size(), "'before' stays");

        ran.clear();
        log.runNewestFirst(e -> fail(e));
        assertEquals(List.of("before"), ran);
    }

    // runNewestFirstWhere skips the savepoint marks explicitly. The production filters (a data source key, a rollback-only
    // transaction) never match a mark's null tag, so a filter that does must still leave the marks in place and uncounted: a
    // later rollback to the savepoint then finds its mark and runs only the actions since it, not those before it, whose rows
    // survive (without the guard the mark would be run as a no-op and dropped, and size() would count a mark that is gone).
    @Test
    void savepointMarksSurviveARunWhoseFilterMatchesTheirNullTag() {
        final RollbackActionLog log = new RollbackActionLog();
        final List<String> ran = new ArrayList<>();
        final Object savepoint = new Object();

        log.add("ds", () -> ran.add("before"));
        log.mark(savepoint);
        log.add(() -> ran.add("untagged"));
        log.add("ds", () -> ran.add("tagged"));
        assertEquals(3, log.size());

        log.runNewestFirstWhere(tag -> tag == null, e -> fail(e));
        assertEquals(List.of("untagged"), ran, "the mark is not an action to run");
        assertEquals(2, log.size(), "'before', 'tagged' and the mark stay");

        ran.clear();
        log.runNewestFirstSince(savepoint, e -> fail(e));
        assertEquals(List.of("tagged"), ran, "the mark stayed: only the action since the savepoint runs");
        assertEquals(1, log.size(), "'before' stays");

        // A filter matching every tag: only the mark stays, still counted as a mark and not as an action.
        ran.clear();
        log.add(() -> ran.add("since"));
        log.runNewestFirstWhere(tag -> true, e -> fail(e));
        assertEquals(List.of("since", "before"), ran);
        assertEquals(0, log.size());

        ran.clear();
        log.add(() -> ran.add("after"));
        log.runNewestFirstSince(savepoint, e -> fail(e));
        assertEquals(List.of("after"), ran);
        assertEquals(0, log.size());
    }

    // A participating transaction rolled back inside an open savepoint scope (PROPAGATION_NESTED) dooms that scope only: the
    // tagged actions since the newest mark run right away, those before it (and the other tags, and every mark) stay for the
    // transaction's outcome or a later rollback to their savepoint. Running every tagged action put back the IDs of the
    // enclosing transaction's inserts although it could still commit their rows.
    @Test
    void runNewestFirstSinceNewestMarkRunsOnlyTheTaggedActionsSinceTheNewestMark() {
        final RollbackActionLog log = new RollbackActionLog();
        final List<String> ran = new ArrayList<>();
        final Object outerSavepoint = new Object();
        final Object savepoint = new Object();

        log.add("ds", () -> ran.add("before"));
        log.add("other", () -> ran.add("other before"));
        log.mark(outerSavepoint);
        log.add("ds", () -> ran.add("outer"));
        log.mark(savepoint);
        log.add("ds", () -> ran.add("first"));
        log.add("other", () -> ran.add("other since"));
        log.add("ds", () -> ran.add("second"));
        assertEquals(6, log.size());

        log.runNewestFirstSinceNewestMark("ds", e -> fail(e));
        assertEquals(List.of("second", "first"), ran);
        assertEquals(4, log.size(), "'before', 'other before', 'outer' and 'other since' stay");
        assertEquals(2, log.markCount(), "the marks stay");

        // The rollback to the savepoint that follows still finds its mark, and only the other tag's action since it is left.
        ran.clear();
        log.runNewestFirstSince(savepoint, e -> fail(e));
        assertEquals(List.of("other since"), ran);
        assertEquals(3, log.size());

        // Nothing since the newest mark: nothing runs, nothing is dropped.
        ran.clear();
        log.runNewestFirstSinceNewestMark("ds", e -> fail(e));
        assertTrue(ran.isEmpty());
        assertEquals(3, log.size());

        // A rollback to the outer savepoint takes the inner mark with the actions since it: the outer mark is the newest then.
        log.add("ds", () -> ran.add("third"));
        log.runNewestFirstSince(outerSavepoint, e -> fail(e));
        assertEquals(List.of("third", "outer"), ran);
        assertEquals(1, log.markCount());

        ran.clear();
        log.add("ds", () -> ran.add("fourth"));
        log.add("other", () -> ran.add("other fourth"));
        log.runNewestFirstSinceNewestMark("ds", e -> fail(e));
        assertEquals(List.of("fourth"), ran);
        assertEquals(3, log.size(), "'before', 'other before' and 'other fourth' stay");

        // Without a mark, every action with the tag runs, as runNewestFirst(tag) does.
        final RollbackActionLog unmarked = new RollbackActionLog();
        ran.clear();
        unmarked.add("ds", () -> ran.add("a"));
        unmarked.add("other", () -> ran.add("b"));
        unmarked.add("ds", () -> ran.add("c"));
        unmarked.runNewestFirstSinceNewestMark("ds", e -> fail(e));
        assertEquals(List.of("c", "a"), ran);
        assertEquals(1, unmarked.size());
    }

    // Spring reports no savepoint release, so a transaction looping over nested scopes kept one mark per scope for its whole
    // life. A mark holds its savepoint weakly: once the savepoint object is collected (nothing can roll back to it any more)
    // the mark is purged with the obsolete snapshots, is no longer counted, and no longer confines a participating rollback
    // to the work since it; a mark whose savepoint is still referenced stays.
    @Test
    void marksOfCollectedSavepointsArePurged() throws InterruptedException {
        final RollbackActionLog log = new RollbackActionLog();
        final List<String> ran = new ArrayList<>();
        final Object live = new Object();

        log.add("ds", () -> ran.add("before"));
        log.mark(live);
        log.add("ds", () -> ran.add("since live"));
        final WeakReference<Object> released = markAnUnreferencedSavepoint(log);
        log.add("ds", () -> ran.add("since released"));
        assertEquals(2, log.markCount());

        // Fills the log up to the purge threshold (the marks count as entries there); the next add purges.
        final List<WeakReference<Key>> collected = addSnapshotsOfUnreferencedKeys(log, RollbackActionLog.MIN_PURGE_THRESHOLD - 5);

        for (int attempt = 0; attempt < 100 && (released.get() != null || collected.stream().anyMatch(ref -> ref.get() != null)); attempt++) {
            System.gc();
            Thread.sleep(10);
        }

        assertNull(released.get(), "the unreferenced savepoint was not collected");
        assertTrue(collected.stream().allMatch(ref -> ref.get() == null), "the unreferenced keys were not collected");

        log.add("ds", () -> ran.add("after purge")); // triggers the purge
        assertEquals(1, log.markCount(), "the mark of the collected savepoint is purged, the live one stays");
        assertEquals(4, log.size(), "the purged mark is no longer counted");

        // The newest mark that counts is the live one: a participating rollback undoes everything since it.
        log.runNewestFirstSinceNewestMark("ds", e -> fail(e));
        assertEquals(List.of("after purge", "since released", "since live"), ran);
        assertEquals(1, log.size(), "'before' stays");

        ran.clear();
        log.runNewestFirstSince(live, e -> fail(e));
        assertTrue(ran.isEmpty(), "the live mark is still found: nothing since it");
        log.runNewestFirst(e -> fail(e));
        assertEquals(List.of("before"), ran);
    }

    // A mark whose savepoint was collected matches no rollback, not even one naming null (it used to be compared by identity).
    @Test
    void aCollectedMarkIsSkippedByAParticipatingRollbackEvenBeforeThePurge() throws InterruptedException {
        final RollbackActionLog log = new RollbackActionLog();
        final List<String> ran = new ArrayList<>();

        log.add("ds", () -> ran.add("before"));
        final WeakReference<Object> released = markAnUnreferencedSavepoint(log);
        log.add("ds", () -> ran.add("since released"));

        for (int attempt = 0; attempt < 100 && released.get() != null; attempt++) {
            System.gc();
            Thread.sleep(10);
        }

        assertNull(released.get(), "the unreferenced savepoint was not collected");
        assertEquals(1, log.markCount(), "not purged yet (below the threshold)");

        log.runNewestFirstSinceNewestMark("ds", e -> fail(e));
        assertEquals(List.of("since released", "before"), ran, "no live mark: every action with the tag runs");
        assertEquals(0, log.size());
    }

    // Transaction review, low-severity finding: only an added action purged the log, so a transaction that looped over nested
    // scopes without inserting anything kept the mark of every savepoint: the marks grew without limit. A mark purges too now.
    @Test
    void marksAlonePurgeTheLogToo() throws InterruptedException {
        final RollbackActionLog log = new RollbackActionLog();
        final List<WeakReference<Object>> released = new ArrayList<>();

        for (int i = 0; i < RollbackActionLog.MIN_PURGE_THRESHOLD; i++) {
            released.add(markAnUnreferencedSavepoint(log));
        }

        assertEquals(RollbackActionLog.MIN_PURGE_THRESHOLD, log.markCount());

        for (int attempt = 0; attempt < 100 && released.stream().anyMatch(ref -> ref.get() != null); attempt++) {
            System.gc();
            Thread.sleep(10);
        }

        assertTrue(released.stream().allMatch(ref -> ref.get() == null), "the unreferenced savepoints were not collected");

        final Object live = new Object();
        log.mark(live); // triggers the purge
        assertEquals(1, log.markCount(), "only the live mark stays");
        assertEquals(0, log.size());
    }

    private static WeakReference<Object> markAnUnreferencedSavepoint(final RollbackActionLog log) {
        final Object savepoint = new Object();
        log.mark(savepoint);
        return new WeakReference<>(savepoint);
    }

    private static List<WeakReference<Key>> addSnapshotsOfUnreferencedKeys(final RollbackActionLog log, final int count) {
        final List<WeakReference<Key>> refs = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            final Key key = key(null, 0);
            log.add(RollbackActionLog.captureIdValues(List.of(key), KEY_ID_PROPS, KEY_INFO));
            refs.add(new WeakReference<>(key));
        }

        return refs;
    }

    private static Key key(final Long orderId, final int lineNum) {
        final Key key = new Key();
        key.setOrderId(orderId);
        key.setLineNum(lineNum);
        return key;
    }

    public static class Key {
        private Long orderId;
        private int lineNum;

        public Long getOrderId() {
            return orderId;
        }

        public void setOrderId(final Long orderId) {
            this.orderId = orderId;
        }

        public int getLineNum() {
            return lineNum;
        }

        public void setLineNum(final int lineNum) {
            this.lineNum = lineNum;
        }
    }

    record KeyRecord(long orderId, int lineNum) {
    }

    @Test
    void runNewestFirstWhere_runsTheMatchingActionsNewestFirstAndKeepsTheOthers() {
        final RollbackActionLog log = new RollbackActionLog();
        final List<String> ran = new ArrayList<>();
        final Object a = new Object();
        final Object b = new Object();
        log.add(a, () -> ran.add("a1"));
        log.add(b, () -> ran.add("b1"));
        log.add(() -> ran.add("untagged"));
        log.add(a, () -> ran.add("a2"));

        log.runNewestFirstWhere(tag -> tag == a || tag == null, e -> {
            throw new AssertionError(e);
        });

        assertEquals(List.of("a2", "untagged", "a1"), ran);
        assertEquals(1, log.size());

        log.runNewestFirst(e -> {
            throw new AssertionError(e);
        });

        assertEquals(List.of("a2", "untagged", "a1", "b1"), ran);
    }

    // The filter was tested twice per action, once to select the actions to run and once to remove them: a filter whose answer
    // changes between calls ran one set of actions and removed another, dropping actions that never ran.
    @Test
    void runNewestFirstWhere_testsTheFilterOncePerAction_andRemovesExactlyTheActionsItRuns() {
        final RollbackActionLog log = new RollbackActionLog();
        final List<String> ran = new ArrayList<>();
        log.add("first", () -> ran.add("first"));
        log.add("second", () -> ran.add("second"));
        final List<Object> tested = new ArrayList<>();

        // Matches only the first action it is asked about.
        log.runNewestFirstWhere(tag -> {
            tested.add(tag);
            return tested.size() == 1;
        }, e -> {
            throw new AssertionError(e);
        });

        assertEquals(List.of("first", "second"), tested);
        assertEquals(List.of("first"), ran);
        assertEquals(1, log.size());

        log.runNewestFirst(e -> {
            throw new AssertionError(e);
        });

        assertEquals(List.of("first", "second"), ran);
    }
}
