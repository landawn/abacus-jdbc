/*
 * Copyright (c) 2021, Haiyang Li.
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

import com.landawn.abacus.annotation.Beta;

/**
 * Deprecated enum mirroring common database {@code ON DELETE} actions.
 *
 * <p>This type is retained only for backward compatibility. Model delete actions in the
 * database schema rather than in application-side metadata.</p>
 *
 * @deprecated Define foreign-key delete actions in the database schema instead.
 */
@Beta
@Deprecated
public enum OnDeleteAction {
    /**
     * No action is taken when the referenced row is deleted; the database engine defers
     * the referential-integrity check until the end of the statement (or transaction in
     * some databases). The delete operation will fail if child rows still reference the
     * parent at that point.
     *
     * <p>This is similar to—but distinct from—SQL {@code RESTRICT}, which checks the
     * constraint immediately rather than at statement end. It is the most conservative
     * approach, preventing accidental data loss by requiring explicit deletion of child
     * records first.</p>
     *
     * <p><b>Database Behavior:</b> DELETE operation on the parent table will fail with a
     * foreign key constraint violation if any child records still exist when the check
     * is performed.</p>
     */
    NO_ACTION(0),

    /**
     * When the referenced row is deleted, the foreign key columns in child rows are set to NULL.
     *
     * <p>This action requires that the foreign key columns be nullable. It's useful when you want
     * to maintain child records even after the parent is deleted, with the foreign key indicating
     * that the parent no longer exists.</p>
     *
     * <p><b>Database Behavior:</b> All child records will have their foreign key column set to NULL
     * when the parent record is deleted.</p>
     *
     * <p><b>Important:</b> The foreign key column must be defined as nullable (without NOT NULL constraint)
     * for this action to work.</p>
     */
    SET_NULL(1),

    /**
     * When the referenced row is deleted, all child rows that reference it are also deleted.
     *
     * <p>This action cascades the delete operation through the relationship hierarchy. Use with
     * caution as it can result in the deletion of large amounts of data, especially with deep
     * relationship chains.</p>
     *
     * <p><b>Database Behavior:</b> All child records referencing the deleted parent will be
     * automatically deleted. If those child records are parents to other records with CASCADE
     * actions, the deletion will continue cascading through the entire hierarchy.</p>
     *
     * <p><b>Warning:</b> Can lead to unexpected data loss if not carefully designed. Always review
     * the full impact of cascading deletes in your data model.</p>
     */
    CASCADE(2);

    /**
     * The integer representation of this delete action.
     */
    private final int intValue;

    /**
     * Constructs an {@code OnDeleteAction} with the specified integer value.
     *
     * @param intValue the integer representation for this delete action ({@code 0} for {@link #NO_ACTION},
     *         {@code 1} for {@link #SET_NULL}, {@code 2} for {@link #CASCADE})
     */
    OnDeleteAction(final int intValue) {
        this.intValue = intValue;
    }

    /**
     * Returns the raw integer value associated with this {@code OnDeleteAction}.
     * This value can be used for persistence, serialization, or comparison purposes.
     *
     * <p>The integer values are:</p>
     * <ul>
     *   <li>{@link #NO_ACTION}: 0</li>
     *   <li>{@link #SET_NULL}: 1</li>
     *   <li>{@link #CASCADE}: 2</li>
     * </ul>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Example: Storing the action value
     * OnDeleteAction action = OnDeleteAction.CASCADE;
     * int actionValue = action.intValue();
     * System.out.println("Action code: " + actionValue);   // Prints: Action code: 2
     *
     * // Example: Using value for conditional logic
     * if (action.intValue() == 0) {
     *     System.out.println("No cascading delete will occur");
     * }
     * }</pre>
     *
     * <p>This method is useful when writing/reading this enum to and from numeric stores.</p>
     *
     * @return the integer value representing this delete action:
     *         {@code NO_ACTION=0}, {@code SET_NULL=1}, or {@code CASCADE=2}
     * @deprecated This enum is deprecated. Use database-level foreign key constraints instead.
     */
    @Deprecated
    public int intValue() {
        return intValue;
    }

    /**
     * Returns the {@code OnDeleteAction} enum constant that corresponds to the given integer value.
     *
     * <p>This static factory method converts a raw integer code (as returned by {@link #intValue()})
     * back into its type-safe {@code OnDeleteAction} enum representation. It mirrors the
     * {@code valueOf(int)} factories on {@link IsolationLevel} and {@link FetchDirection}.</p>
     *
     * <p>The accepted values are:</p>
     * <ul>
     *   <li>{@code 0}: {@link #NO_ACTION}</li>
     *   <li>{@code 1}: {@link #SET_NULL}</li>
     *   <li>{@code 2}: {@link #CASCADE}</li>
     * </ul>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Example: parsing from a numeric store
     * int code = 2;
     * OnDeleteAction action = OnDeleteAction.valueOf(code);
     * System.out.println("Parsed action: " + action);   // Parsed action: CASCADE
     * }</pre>
     *
     * @param intValue the integer code to convert ({@code 0} for {@link #NO_ACTION},
     *        {@code 1} for {@link #SET_NULL}, {@code 2} for {@link #CASCADE})
     * @return the matching {@code OnDeleteAction} constant
     * @throws IllegalArgumentException if {@code intValue} does not match any known delete action
     * @deprecated This enum is deprecated. Use database-level foreign key constraints instead.
     */
    @Deprecated
    public static OnDeleteAction valueOf(final int intValue) {
        switch (intValue) {
            case 0:
                return NO_ACTION;

            case 1:
                return SET_NULL;

            case 2:
                return CASCADE;

            default:
                throw new IllegalArgumentException("Invalid OnDeleteAction value: " + intValue);
        }
    }
}
