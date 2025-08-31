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
 * <p>Example database usage (for reference only):
 * <pre>{@code
 * CREATE TABLE orders (
 *     id INT PRIMARY KEY,
 *     customer_id INT,
 *     FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE CASCADE
 * );
 * }</pre>
 * 
 * @deprecated Foreign key actions should be defined and implemented at the database server level,
 *             not in application code. Use proper database schema definitions instead.
 * @since 1.0
 */
@Beta
@Deprecated
public enum OnDeleteAction {
    /**
     * No action is taken when the referenced row is deleted.
     * The delete operation will fail if there are still child rows referencing the parent.
     * This is equivalent to the SQL RESTRICT action.
     */
    NO_ACTION(0),
    /**
     * When the referenced row is deleted, the foreign key columns in child rows are set to NULL.
     * This action requires that the foreign key columns be nullable.
     */
    SET_NULL(1),
    /**
     * When the referenced row is deleted, all child rows that reference it are also deleted.
     * This action cascades the delete operation through the relationship hierarchy.
     * Use with caution as it can result in the deletion of large amounts of data.
     */
    CASCADE(2);

    /**
     * The integer representation of this delete action.
     */
    private final int intValue;

    OnDeleteAction(final int intValue) {
        this.intValue = intValue;
    }

    /**
     * Returns the integer value representing this delete action.
     * 
     * @return The integer value of this action
     */
    public int value() {
        return intValue;
    }

    /**
     * Returns the OnDeleteAction enum constant for the specified string name.
     * The lookup is case-insensitive.
     * 
     * @param name The string representation of the delete action ("noAction", "setNull", or "cascade")
     * @return The corresponding OnDeleteAction enum constant
     * @throws IllegalArgumentException if the name does not match any known action
     */
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
