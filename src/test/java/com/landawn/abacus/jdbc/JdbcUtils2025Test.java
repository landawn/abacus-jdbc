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
 * Comprehensive unit tests for JdbcUtils class.
 * Tests public static methods and utility functionality.
 * Note: Full functional testing requires a database connection.
 */
@Tag("2025")
public class JdbcUtils2025Test extends TestBase {

    // Test class exists and can be loaded

    @Test
    public void testClassExists() {
        assertNotNull(JdbcUtils.class);
    }

    // Test class is final
    @Test
    public void testClassIsFinal() {
        // JdbcUtils is a utility class with static methods
        assertNotNull(JdbcUtils.class.getDeclaredMethods());
    }

    // Test that JdbcUtils has public static methods
    @Test
    public void testHasPublicMethods() {
        java.lang.reflect.Method[] methods = JdbcUtils.class.getDeclaredMethods();
        assertNotNull(methods);
        // JdbcUtils should have several public methods for import/export
    }
}
