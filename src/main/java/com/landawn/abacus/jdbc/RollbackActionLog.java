/*
 * Copyright (c) 2026, Haiyang Li.
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

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.landawn.abacus.parser.ParserUtil.BeanInfo;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.util.N;

/**
 * The rollback actions registered with one transaction: an undo log of in-memory changes tied to the transaction's work,
 * such as the generated IDs that inserts write back onto their entities.
 *
 * <p>The actions run newest first. An action typically restores a snapshot taken just before a change, so when the same
 * object is changed several times within the transaction, undoing the snapshots in reverse leaves the oldest one, the state
 * before the transaction, in place. Not thread-safe: a transaction registers and runs its actions on its own thread.</p>
 *
 * <p>A transaction that rolls back to a savepoint (Spring's {@code PROPAGATION_NESTED}) undoes only the work since that
 * savepoint: {@link #mark(Object)} records the savepoint's position in the log, and {@link #runNewestFirstSince(Object, Consumer)}
 * runs the actions registered after it, leaving the earlier ones for the transaction's outcome. A participating
 * transaction rolled back while such a savepoint is open undoes only the work since the newest open savepoint right away
 * (see {@link #runNewestFirstSinceNewestMark(Object, Consumer)}): the enclosing transaction may still commit its own work.</p>
 */
final class RollbackActionLog {

    /** The size at which obsolete ID snapshots are first purged; afterwards, twice the size that the last purge left. */
    static final int MIN_PURGE_THRESHOLD = 256;

    private final ArrayList<Entry> entries = new ArrayList<>();

    private int purgeThreshold = MIN_PURGE_THRESHOLD;

    /** The number of savepoint marks among {@link #entries}. */
    private int marks;

    /**
     * Appends an action without a tag.
     *
     * @param action the action to run if the transaction rolls back; must not be {@code null}
     */
    void add(final Runnable action) {
        add(null, action);
    }

    /**
     * Appends an action tagged with what it depends on (e.g. the data source of the work it undoes), so that the actions of
     * one tag can be run before the others (see {@link #runNewestFirst(Object, Consumer)}).
     *
     * @param tag the tag, compared by identity; {@code null} for none
     * @param action the action to run if the transaction rolls back; must not be {@code null}
     */
    void add(final Object tag, final Runnable action) {
        purgeIfNeeded();

        entries.add(new Entry(tag, action));
    }

    /**
     * Drops the obsolete entries once the log has reached the purge threshold.
     */
    private void purgeIfNeeded() {
        if (entries.size() >= purgeThreshold) {
            // A snapshot of entities that nothing references any more has nothing left to restore. Dropping such snapshots keeps
            // a long transaction that inserts many short-lived entities from retaining one entry per insert; the threshold
            // doubles with the entries that remain, so the purges cost amortized constant time per entry.
            // WHY the marks go too: a savepoint is released without notice (Spring reports no release), so a transaction that
            // loops over nested scopes kept one mark per scope for its whole life. A mark whose savepoint object has been
            // garbage collected can't be rolled back to any more (see SavepointMark) and is dropped with the snapshots.
            entries.removeIf(RollbackActionLog::isObsolete);
            marks = countMarks();
            purgeThreshold = Math.max(MIN_PURGE_THRESHOLD, entries.size() * 2);
        }
    }

    /**
     * Records the position of a savepoint created now: {@link #runNewestFirstSince(Object, Consumer)} runs the actions added
     * after this point. A mark takes no part in {@link #size()}. It holds the savepoint weakly and is purged (with the
     * obsolete ID snapshots, see {@link #add(Object, Runnable)}) once the savepoint object has been garbage collected, which
     * no rollback can name any more; a savepoint still referenced (held by an open nested transaction, or by the application
     * for a later rollback to it) keeps its mark.
     *
     * @param savepoint the savepoint, compared by identity; must not be {@code null}
     * @throws IllegalArgumentException if {@code savepoint} is {@code null}
     */
    void mark(final Object savepoint) {
        N.checkArgNotNull(savepoint, cs.savepoint);

        // Purged here too: a transaction that loops over nested scopes without inserting anything only adds marks, which grew
        // without limit, as the purge ran only when an action was added.
        purgeIfNeeded();

        entries.add(new Entry(null, new SavepointMark(new WeakReference<>(savepoint))));
        marks++;
    }

    private int countMarks() {
        int count = 0;

        for (final Entry entry : entries) {
            if (entry.action() instanceof SavepointMark) {
                count++;
            }
        }

        return count;
    }

    private static boolean isObsolete(final Entry entry) {
        return entry.action() instanceof final IdSnapshot snapshot ? snapshot.isObsolete()
                : entry.action() instanceof final SavepointMark mark && mark.isObsolete();
    }

    /**
     * Returns the number of registered actions (savepoint marks not counted).
     *
     * @return the number of actions that would run
     */
    int size() {
        return entries.size() - marks;
    }

    /**
     * Returns the number of savepoint marks held, those of collected savepoints included until the next purge (see
     * {@link #mark(Object)}); for tests of the purge, which {@link #size()} does not show.
     *
     * @return the number of marks among the entries
     */
    int markCount() {
        return marks;
    }

    /**
     * Runs the actions newest first and empties the log, so that each action runs at most once. A failing action does not
     * stop the remaining ones: its failure is passed to {@code failureHandler}.
     *
     * @param failureHandler receives the failure of each action that throws
     */
    void runNewestFirst(final Consumer<? super Throwable> failureHandler) {
        final Entry[] toRun = entries.toArray(new Entry[0]);
        entries.clear();
        marks = 0;

        run(toRun, failureHandler);
    }

    /**
     * Runs the actions added after the mark of {@code savepoint} (see {@link #mark(Object)}) newest first and removes them
     * from the log; the actions added before the mark stay, for the transaction's outcome, and so does the mark: a
     * programmatic savepoint stays usable after a rollback to it, and a later rollback to it must again undo only the work
     * since it (marks of savepoints created after it go with the actions). Without a mark for {@code savepoint}, every action
     * runs: the log was then started after the savepoint was created (a synchronization registered inside the nested
     * transaction), so all of its actions belong to work since the savepoint. A failing action does not stop the remaining
     * ones: its failure is passed to {@code failureHandler}.
     *
     * @param savepoint the savepoint the transaction rolls back to, compared by identity
     * @param failureHandler receives the failure of each action that throws
     */
    void runNewestFirstSince(final Object savepoint, final Consumer<? super Throwable> failureHandler) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).action() instanceof final SavepointMark mark && mark.isFor(savepoint)) {
                // WHY: the mark was removed with the actions, so a second rollback to the same (programmatic) savepoint found no
                // mark and ran every remaining action, including those of work before the savepoint, whose rows survive.
                final List<Entry> since = entries.subList(i + 1, entries.size());
                final Entry[] toRun = since.toArray(new Entry[0]);

                for (final Entry entry : toRun) {
                    if (entry.action() instanceof SavepointMark) {
                        marks--; // marks of savepoints created after it (released or rolled back with it)
                    }
                }

                since.clear();
                run(toRun, failureHandler);

                return;
            }
        }

        runNewestFirst(failureHandler);
    }

    /**
     * Runs the actions with {@code tag} (compared by identity) that were added after the newest savepoint mark (see
     * {@link #mark(Object)}) newest first and removes them from the log; the other actions stay, those with {@code tag} added
     * before the mark included, for the transaction's outcome or a later rollback to a savepoint, and so does every mark.
     * Without a mark, every action with {@code tag} runs, as with {@link #runNewestFirst(Object, Consumer)}. A mark whose
     * savepoint object has been garbage collected does not count: the transaction can't roll back to it any more, so the work
     * since it is not confined to a savepoint. A failing action does not stop the remaining ones: its failure is passed to
     * {@code failureHandler}.
     *
     * <p>For a participating transaction rolled back inside an open savepoint scope: the savepoint scope is doomed, so the
     * work since the savepoint is undone right away, while the enclosing transaction may still commit the work before the
     * savepoint (a rollback to the savepoint clears the rollback-only mark it set, except on a JPA transaction), whose actions
     * therefore wait.</p>
     *
     * @param tag the tag of the actions to run
     * @param failureHandler receives the failure of each action that throws
     */
    void runNewestFirstSinceNewestMark(final Object tag, final Consumer<? super Throwable> failureHandler) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).action() instanceof final SavepointMark mark && !mark.isObsolete()) {
                final List<Entry> since = entries.subList(i + 1, entries.size());
                final List<Entry> toRun = new ArrayList<>();
                final List<Entry> kept = new ArrayList<>(since.size());

                for (final Entry entry : since) {
                    // A savepoint mark is no action: it stays where it is (a mark since the newest live one is an obsolete one).
                    (!(entry.action() instanceof SavepointMark) && entry.tag() == tag ? toRun : kept).add(entry);
                }

                if (!toRun.isEmpty()) {
                    since.clear();
                    entries.addAll(kept);
                    run(toRun.toArray(new Entry[0]), failureHandler);
                }

                return;
            }
        }

        runNewestFirst(tag, failureHandler);
    }

    /**
     * Runs the actions with {@code tag} (compared by identity) newest first and removes them from the log; the other actions
     * stay. A failing action does not stop the remaining ones: its failure is passed to {@code failureHandler}.
     *
     * @param tag the tag of the actions to run
     * @param failureHandler receives the failure of each action that throws
     */
    void runNewestFirst(final Object tag, final Consumer<? super Throwable> failureHandler) {
        runNewestFirstWhere(it -> it == tag, failureHandler);
    }

    /**
     * Runs the actions whose tag matches {@code tagFilter} newest first and removes them from the log; the other actions stay.
     * {@code tagFilter} is tested once per action, so exactly the actions that run are removed. A failing action does not stop
     * the remaining ones: its failure is passed to {@code failureHandler}.
     *
     * @param tagFilter tests the tag ({@code null} for an action without one) of each action
     * @param failureHandler receives the failure of each action that throws
     */
    void runNewestFirstWhere(final Predicate<Object> tagFilter, final Consumer<? super Throwable> failureHandler) {
        final List<Entry> toRun = new ArrayList<>();
        final List<Entry> kept = new ArrayList<>(entries.size());

        for (final Entry entry : entries) {
            // A savepoint mark is no action: it stays where it is.
            (!(entry.action() instanceof SavepointMark) && tagFilter.test(entry.tag()) ? toRun : kept).add(entry);
        }

        if (!toRun.isEmpty()) {
            entries.clear();
            entries.addAll(kept);
            run(toRun.toArray(new Entry[0]), failureHandler);
        }
    }

    private static void run(final Entry[] toRun, final Consumer<? super Throwable> failureHandler) {
        for (int i = toRun.length - 1; i >= 0; i--) {
            try {
                toRun[i].action().run();
            } catch (final RuntimeException | Error e) {
                failureHandler.accept(e);
            }
        }
    }

    /** A registered action and its tag. */
    private record Entry(Object tag, Runnable action) {
    }

    /**
     * The position of a savepoint among the actions (see {@link #mark(Object)}); running it does nothing. The savepoint is
     * held weakly: Spring's transaction status drops it when the savepoint is released or rolled back to, and the JDBC
     * drivers do not retain their savepoint objects, so a collected savepoint is one no rollback can name any more.
     */
    private record SavepointMark(WeakReference<Object> savepointRef) implements Runnable {
        @Override
        public void run() {
            // A position, not an action.
        }

        /**
         * Returns whether this is the mark of {@code savepoint}. A collected savepoint matches nothing, not even {@code null}.
         *
         * @param savepoint the savepoint, compared by identity
         * @return {@code true} if this mark was recorded for it
         */
        boolean isFor(final Object savepoint) {
            return savepoint != null && savepointRef.get() == savepoint;
        }

        /**
         * Returns whether the savepoint has been garbage collected, so that no rollback can name it any more.
         *
         * @return {@code true} if this mark can be dropped
         */
        boolean isObsolete() {
            return savepointRef.get() == null;
        }
    }

    /**
     * Captures the current ID property values of {@code entities} and returns an action that puts back every captured value
     * that has changed since: an insert writes generated IDs onto its entities as soon as its statements run, and if the
     * transaction it runs in rolls back, those rows no longer exist. Register the action with the transaction before the
     * insert first writes an ID (a generated one before the statement runs, or the database's keys after it), and record the
     * values each write leaves ({@link IdSnapshot#recordWrittenValues()}). The entities are only weakly referenced: an entity
     * that nothing references any more needs no restore.
     *
     * @param entities the entities whose ID values are captured
     * @param idPropNameList the ID property names of the entity class
     * @param entityInfo the bean information for the entity class
     * @return the action that restores the captured ID values
     * @throws IllegalArgumentException if an element of {@code entities} is {@code null}
     */
    static IdSnapshot captureIdValues(final Collection<?> entities, final List<String> idPropNameList, final BeanInfo entityInfo) {
        return new IdSnapshot(entities, idPropNameList, entityInfo);
    }

    /**
     * The ID property values of some entities before an insert; see {@link #captureIdValues(Collection, List, BeanInfo)}.
     */
    static final class IdSnapshot implements Runnable {
        private final PropInfo[] idPropInfos;
        private final WeakReference<?>[] entityRefs;
        private final Object[][] idValues;

        /**
         * The ID values the insert wrote, per entity, once recorded (see {@link #recordWrittenValues()}); {@code null} before.
         */
        private Object[][] writtenValues;

        /**
         * Marks, in {@link #writtenValues}, an entity whose ID values could not be read when they were recorded: {@link #run()}
         * leaves it alone, as it can't tell whether the application has set another ID since.
         */
        private static final Object[] UNKNOWN = {};

        IdSnapshot(final Collection<?> entities, final List<String> idPropNameList, final BeanInfo entityInfo) {
            idPropInfos = new PropInfo[idPropNameList.size()];

            for (int j = 0; j < idPropInfos.length; j++) {
                idPropInfos[j] = entityInfo.getPropInfo(idPropNameList.get(j));
            }

            entityRefs = new WeakReference<?>[entities.size()];
            idValues = new Object[entityRefs.length][];
            int i = 0;

            for (final Object entity : entities) {
                // Rejected as an invalid argument, as the ID getters of the inserts do: reading a null element's ID values
                // failed with a NullPointerException.
                N.checkArgNotNull(entity, cs.entity);

                final Object[] values = new Object[idPropInfos.length];

                for (int j = 0; j < values.length; j++) {
                    values[j] = idPropInfos[j].getPropValue(entity);
                }

                entityRefs[i] = new WeakReference<>(entity);
                idValues[i++] = values;
            }
        }

        /**
         * Returns whether every captured entity has been garbage collected, leaving nothing to restore.
         *
         * @return {@code true} if this snapshot can be dropped
         */
        boolean isObsolete() {
            for (final WeakReference<?> entityRef : entityRefs) {
                if (entityRef.get() != null) {
                    return false;
                }
            }

            return true;
        }

        /**
         * Records the current ID values of the entities as those the insert wrote: {@link #run()} then leaves an entity alone
         * whose ID values have changed since (the application set another ID after the insert). Each entity is recorded on its
         * own: one whose ID values can't be read is recorded as unknown, and left alone by {@link #run()} as well. WHY: the
         * first getter that failed left nothing recorded, so a rollback put back the captured IDs of every entity, overwriting
         * those the application had set since.
         *
         * @throws RuntimeException the first failure to read an entity's ID values (later ones attached to it), once every
         *         entity has been recorded
         * @throws Error the first such {@code Error}, likewise
         */
        void recordWrittenValues() {
            final Object[][] written = new Object[entityRefs.length][];
            Throwable firstFailure = null;

            for (int i = 0; i < entityRefs.length; i++) {
                final Object entity = entityRefs[i].get();

                if (entity != null) {
                    final Object[] values = new Object[idPropInfos.length];

                    try {
                        for (int j = 0; j < values.length; j++) {
                            values[j] = idPropInfos[j].getPropValue(entity);
                        }

                        written[i] = values;
                    } catch (final RuntimeException | Error e) {
                        written[i] = UNKNOWN;

                        if (firstFailure == null) {
                            firstFailure = e;
                        } else if (firstFailure != e) {
                            firstFailure.addSuppressed(e);
                        }
                    }
                }
            }

            writtenValues = written;

            if (firstFailure instanceof final Error error) {
                throw error;
            } else if (firstFailure != null) {
                throw (RuntimeException) firstFailure;
            }
        }

        /**
         * Puts back every captured ID value that has changed since the capture. An unchanged value is not written, so an
         * immutable entity whose ID was not changed does not fail. Once the written values are recorded, an entity whose ID
         * values no longer equal them is left alone: the application has set another ID since, which putting back the captured
         * one overwrote; so is one whose written values could not be read (see {@link #recordWrittenValues()}).
         */
        @Override
        public void run() {
            for (int i = 0; i < entityRefs.length; i++) {
                final Object entity = entityRefs[i].get();

                if (entity == null
                        || (writtenValues != null && writtenValues[i] != null && (writtenValues[i] == UNKNOWN || !holdsValues(entity, writtenValues[i])))) {
                    continue;
                }

                final Object[] values = idValues[i];

                for (int j = 0; j < values.length; j++) {
                    if (!N.equals(idPropInfos[j].getPropValue(entity), values[j])) {
                        idPropInfos[j].setPropValue(entity, values[j]);
                    }
                }
            }
        }

        private boolean holdsValues(final Object entity, final Object[] values) {
            for (int j = 0; j < values.length; j++) {
                if (!N.equals(idPropInfos[j].getPropValue(entity), values[j])) {
                    return false;
                }
            }

            return true;
        }
    }
}
