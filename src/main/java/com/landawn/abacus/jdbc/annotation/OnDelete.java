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
package com.landawn.abacus.jdbc.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.landawn.abacus.jdbc.OnDeleteAction;

/**
 * Deprecated metadata placeholder for delete-cascade behavior.
 *
 * <p>This annotation is not implemented by the framework. Define {@code ON DELETE} behavior
 * in the database schema instead of relying on application-side metadata.</p>
 *
 * @deprecated This annotation is not implemented. Define {@code ON DELETE} behavior in the database schema instead.
 * @see OnDeleteAction
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.METHOD })
@Deprecated
public @interface OnDelete {

    /**
     * Specifies the action to take when a referenced entity is deleted.
     *
     * <p><strong>Note:</strong> This functionality should be implemented at the database level
     * using foreign key constraints rather than in the application layer.</p>
     *
     * <p>Available actions correspond to standard SQL ON DELETE behaviors:</p>
     * <ul>
     *   <li>{@link OnDeleteAction#NO_ACTION} - Default, no automatic action</li>
     *   <li>{@link OnDeleteAction#CASCADE} - Delete dependent records</li>
     *   <li>{@link OnDeleteAction#SET_NULL} - Set foreign key to NULL</li>
     * </ul>
     *
     * @return the delete action, defaults to {@link OnDeleteAction#NO_ACTION}
     * @deprecated Define ON DELETE actions in database foreign key constraints
     */
    @Deprecated
    OnDeleteAction action() default OnDeleteAction.NO_ACTION;
}
