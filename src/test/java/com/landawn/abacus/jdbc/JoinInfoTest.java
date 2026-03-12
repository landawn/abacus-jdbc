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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

@Tag("2025")
public class JoinInfoTest extends TestBase {

    // TODO: The remaining JoinInfo SQL-plan builders depend on large internal metadata graphs. Add focused fixture-based
    // tests when stable join-metadata builders are available so the placement stays in JoinInfoTest.

    // Test static sql builder function map

    @Test
    public void testSqlBuilderFuncMapInitialized() {
        assertNotNull(JoinInfo.sqlBuilderFuncMap);
        assertFalse(JoinInfo.sqlBuilderFuncMap.isEmpty());
    }
}
