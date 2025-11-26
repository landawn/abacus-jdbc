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
 * Represents foreign key constraint actions that can be performed when a referenced row is deleted.
 *
 * <p>This enum defines the behavior that should occur when a parent record is deleted and
 * there are child records that reference it through a foreign key constraint. These actions
 * are typically implemented at the database level as part of the foreign key definition.</p>
 *
 * <p><strong>Note:</strong> This enum is deprecated and should not be used in new code.
 * Foreign key constraints and their associated actions should be defined directly in the
 * database schema rather than being managed by application code.</p>
 *
 * <p><b>Database Schema Examples (for reference only):</b></p>
 * <pre>{@code
 * -- CASCADE: Deletes child records when parent is deleted
 * CREATE TABLE orders (
 *     id INT PRIMARY KEY,
 *     customer_id INT,
 *     FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE CASCADE
 * );
 *
 * -- SET NULL: Sets foreign key to NULL when parent is deleted
 * CREATE TABLE orders (
 *     id INT PRIMARY KEY,
 *     customer_id INT,
 *     FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE SET NULL
 * );
 *
 * -- NO ACTION/RESTRICT: Prevents deletion if child records exist
 * CREATE TABLE orders (
 *     id INT PRIMARY KEY,
 *     customer_id INT NOT NULL,
 *     FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE RESTRICT
 * );
 * }</pre>
 *
 * @deprecated Foreign key actions should be defined and implemented at the database server level,
 *             not in application code. Use proper database schema definitions instead.
 */
@Beta
@Deprecated
public enum OnDeleteAction {
    /**
     * No action is taken when the referenced row is deleted.
     * The delete operation will fail if there are still child rows referencing the parent.
     *
     * <p>This is equivalent to the SQL RESTRICT action and is the most conservative approach,
     * preventing accidental data loss by requiring explicit deletion of child records first.</p>
     *
     * <p><b>Database Behavior:</b> DELETE operation on parent table will fail with a foreign key
     * constraint violation if any child records exist.</p>
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
     * Constructs an OnDeleteAction with the specified integer value.
     *
     * @param intValue The integer representation for this delete action (0 for NO_ACTION, 1 for SET_NULL, 2 for CASCADE)
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
     * int actionValue = action.value();
     * System.out.println("Action code: " + actionValue); // Prints: Action code: 2
     *
     * // Example: Using value for conditional logic
     * if (action.value() == 0) {
     *     System.out.println("No cascading delete will occur");
     * }
     * }</pre>
     *
     * @return the integer value representing this delete action (0 for NO_ACTION, 1 for SET_NULL, 2 for CASCADE).
     * @deprecated This enum is deprecated. Use database-level foreign key constraints instead.
     */
    @Deprecated
    public int value() {
        return intValue;
    }

    /**
     * Returns the {@code OnDeleteAction} enum constant that corresponds to the given string name.
     *
     * <p>This static factory method provides a convenient way to convert a string representation
     * of a delete action (typically from configuration files, annotations, or external sources)
     * into its type-safe {@code OnDeleteAction} enum representation. The lookup is case-insensitive,
     * allowing for flexible input formats.</p>
     *
     * <p>Accepted string values (case-insensitive):</p>
     * <ul>
     *   <li>"noAction" - maps to {@link #NO_ACTION}</li>
     *   <li>"setNull" - maps to {@link #SET_NULL}</li>
     *   <li>"cascade" - maps to {@link #CASCADE}</li>
     * </ul>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Example: Parsing action from configuration
     * String configValue = "cascade";
     * OnDeleteAction action = OnDeleteAction.get(configValue);
     * System.out.println("Parsed action: " + action); // Prints: Parsed action: CASCADE
     *
     * // Example: Case-insensitive parsing
     * OnDeleteAction action1 = OnDeleteAction.get("CASCADE");
     * OnDeleteAction action2 = OnDeleteAction.get("cascade");
     * OnDeleteAction action3 = OnDeleteAction.get("Cascade");
     * // All three are equivalent: action1 == action2 == action3
     *
     * // Example: Handling invalid input with try-catch
     * try {
     *     OnDeleteAction action = OnDeleteAction.get("invalid");
     * } catch (IllegalArgumentException e) {
     *     System.err.println("Invalid action name: " + e.getMessage());
     * }
     * }</pre>
     *
     * @param name the string representation of the delete action (case-insensitive).
     *        Expected values are "noAction", "setNull", or "cascade". Cannot be null.
     * @return the corresponding {@code OnDeleteAction} enum constant.
     * @throws IllegalArgumentException if {@code name} does not match any known delete action value.
     * @deprecated This enum is deprecated. Use database-level foreign key constraints instead.
     */
    @Deprecated
    public static OnDeleteAction get(final String name) {
        if ("noAction".equalsIgnoreCase(name)) {
            return NO_ACTION;
        } else if ("setNull".equalsIgnoreCase(name)) {
            return SET_NULL;
        } else if ("cascade".equalsIgnoreCase(name)) {
            return CASCADE;
        } else {
            throw new IllegalArgumentException("Invalid OnDeleteAction value[" + name + "]. ");
        }
    }
}
