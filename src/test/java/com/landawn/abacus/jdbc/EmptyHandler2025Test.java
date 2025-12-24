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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.jdbc.dao.Dao;

/**
 * Comprehensive unit tests for EmptyHandler class.
 * Tests the constructor and that it properly implements Jdbc.Handler interface.
 */
@Tag("2025")
@SuppressWarnings("rawtypes")
public class EmptyHandler2025Test extends TestBase {

    // Test constructor
    @Test
    public void testConstructor() {
        EmptyHandler handler = new EmptyHandler();
        assertNotNull(handler);
    }

    // Test that it implements Jdbc.Handler
    @Test
    public void testImplementsHandler() {
        EmptyHandler handler = new EmptyHandler();
        assertTrue(handler instanceof Jdbc.Handler);
    }

    // Test type compatibility with Jdbc.Handler<Dao>
    @Test
    public void testHandlerTypeCompatibility() {
        Jdbc.Handler<Dao> handler = new EmptyHandler();
        assertNotNull(handler);
    }

    // Test multiple instances
    @Test
    public void testMultipleInstances() {
        EmptyHandler handler1 = new EmptyHandler();
        EmptyHandler handler2 = new EmptyHandler();
        assertNotNull(handler1);
        assertNotNull(handler2);
    }

    // Test getClass()
    @Test
    public void testGetClass() {
        EmptyHandler handler = new EmptyHandler();
        assertNotNull(handler.getClass());
        assertTrue(handler.getClass().getName().contains("EmptyHandler"));
    }

    // Test toString() inherited from Object
    @Test
    public void testToString() {
        EmptyHandler handler = new EmptyHandler();
        String str = handler.toString();
        assertNotNull(str);
    }

    // Test hashCode() inherited from Object
    @Test
    public void testHashCode() {
        EmptyHandler handler = new EmptyHandler();
        // hashCode should return a consistent value
        int hashCode1 = handler.hashCode();
        int hashCode2 = handler.hashCode();
        assertTrue(hashCode1 == hashCode2);
    }
}
