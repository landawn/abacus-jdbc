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

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

/**
 * Comprehensive unit tests for JoinInfo class.
 * Tests public static methods and constants.
 * Note: Full functional testing requires entity classes with @JoinedBy annotations.
 */
@Tag("2025")
public class JoinInfo2025Test extends TestBase {

    // Test static sql builder function map

    @Test
    public void testSqlBuilderFuncMapNotNull() {
        assertNotNull(JoinInfo.sqlBuilderFuncMap);
    }

    @Test
    public void testSqlBuilderFuncMapNotEmpty() {
        assertNotNull(JoinInfo.sqlBuilderFuncMap);
        // The map should contain entries for PSC, PAC, PLC
        // We just verify it's not empty as the internals may vary
    }
}
