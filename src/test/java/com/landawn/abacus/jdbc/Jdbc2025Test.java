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

/**
 * Comprehensive unit tests for Jdbc class and its inner interfaces/classes.
 * Tests public interfaces including ParametersSetter, BiParametersSetter, Handler, etc.
 */
@Tag("2025")
@SuppressWarnings("rawtypes")
public class Jdbc2025Test extends TestBase {

    // Test ParametersSetter interface

    @Test
    public void testParametersSetterDoNothing() {
        assertNotNull(Jdbc.ParametersSetter.DO_NOTHING);
    }

    @Test
    public void testParametersSetterDoNothingIsParametersSetter() {
        assertTrue(Jdbc.ParametersSetter.DO_NOTHING instanceof Jdbc.ParametersSetter);
    }

    // Test BiParametersSetter interface

    @Test
    public void testBiParametersSetterDoNothing() {
        assertNotNull(Jdbc.BiParametersSetter.DO_NOTHING);
    }

    @Test
    public void testBiParametersSetterDoNothingIsBiParametersSetter() {
        assertTrue(Jdbc.BiParametersSetter.DO_NOTHING instanceof Jdbc.BiParametersSetter);
    }

    // Test ColumnGetter interface static fields

    @Test
    public void testColumnGetterGetBoolean() {
        assertNotNull(Jdbc.ColumnGetter.GET_BOOLEAN);
    }

    @Test
    public void testColumnGetterGetByte() {
        assertNotNull(Jdbc.ColumnGetter.GET_BYTE);
    }

    @Test
    public void testColumnGetterGetShort() {
        assertNotNull(Jdbc.ColumnGetter.GET_SHORT);
    }

    @Test
    public void testColumnGetterGetInt() {
        assertNotNull(Jdbc.ColumnGetter.GET_INT);
    }

    @Test
    public void testColumnGetterGetLong() {
        assertNotNull(Jdbc.ColumnGetter.GET_LONG);
    }

    @Test
    public void testColumnGetterGetFloat() {
        assertNotNull(Jdbc.ColumnGetter.GET_FLOAT);
    }

    @Test
    public void testColumnGetterGetDouble() {
        assertNotNull(Jdbc.ColumnGetter.GET_DOUBLE);
    }

    @Test
    public void testColumnGetterGetBigDecimal() {
        assertNotNull(Jdbc.ColumnGetter.GET_BIG_DECIMAL);
    }

    @Test
    public void testColumnGetterGetString() {
        assertNotNull(Jdbc.ColumnGetter.GET_STRING);
    }

    @Test
    public void testColumnGetterGetDate() {
        assertNotNull(Jdbc.ColumnGetter.GET_DATE);
    }

    @Test
    public void testColumnGetterGetTime() {
        assertNotNull(Jdbc.ColumnGetter.GET_TIME);
    }

    @Test
    public void testColumnGetterGetTimestamp() {
        assertNotNull(Jdbc.ColumnGetter.GET_TIMESTAMP);
    }

    @Test
    public void testColumnGetterGetBytes() {
        assertNotNull(Jdbc.ColumnGetter.GET_BYTES);
    }

    @Test
    public void testColumnGetterGetInputStream() {
        assertNotNull(Jdbc.ColumnGetter.GET_BINARY_STREAM);
    }

    @Test
    public void testColumnGetterGetReader() {
        assertNotNull(Jdbc.ColumnGetter.GET_CHARACTER_STREAM);
    }

    @Test
    public void testColumnGetterGetBlob() {
        assertNotNull(Jdbc.ColumnGetter.GET_BLOB);
    }

    @Test
    public void testColumnGetterGetClob() {
        assertNotNull(Jdbc.ColumnGetter.GET_CLOB);
    }

    @Test
    public void testColumnGetterGetObject() {
        assertNotNull(Jdbc.ColumnGetter.GET_OBJECT);
    }

    // Test COLUMN_GETTER_POOL

    @Test
    public void testColumnGetterPoolNotNull() {
        assertNotNull(Jdbc.COLUMN_GETTER_POOL);
    }

    // Test Handler interface exists

    @Test
    public void testHandlerInterfaceExists() {
        // Verify Handler interface is a proper interface
        assertTrue(Jdbc.Handler.class.isInterface());
    }

    // Test ResultExtractor interface exists

    @Test
    public void testResultExtractorInterfaceExists() {
        assertTrue(Jdbc.ResultExtractor.class.isInterface());
    }

    // Test BiResultExtractor interface exists

    @Test
    public void testBiResultExtractorInterfaceExists() {
        assertTrue(Jdbc.BiResultExtractor.class.isInterface());
    }

    // Test RowMapper interface exists

    @Test
    public void testRowMapperInterfaceExists() {
        assertTrue(Jdbc.RowMapper.class.isInterface());
    }

    // Test BiRowMapper interface exists

    @Test
    public void testBiRowMapperInterfaceExists() {
        assertTrue(Jdbc.BiRowMapper.class.isInterface());
    }

    // Test RowFilter interface exists

    @Test
    public void testRowFilterInterfaceExists() {
        assertTrue(Jdbc.RowFilter.class.isInterface());
    }

    // Test BiRowFilter interface exists

    @Test
    public void testBiRowFilterInterfaceExists() {
        assertTrue(Jdbc.BiRowFilter.class.isInterface());
    }

    // Test RowConsumer interface exists

    @Test
    public void testRowConsumerInterfaceExists() {
        assertTrue(Jdbc.RowConsumer.class.isInterface());
    }

    // Test BiRowConsumer interface exists

    @Test
    public void testBiRowConsumerInterfaceExists() {
        assertTrue(Jdbc.BiRowConsumer.class.isInterface());
    }

    // Test TriParametersSetter interface exists

    @Test
    public void testTriParametersSetterInterfaceExists() {
        assertTrue(Jdbc.TriParametersSetter.class.isInterface());
    }
}
