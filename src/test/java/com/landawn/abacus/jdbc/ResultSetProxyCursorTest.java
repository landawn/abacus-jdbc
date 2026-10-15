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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;

class ResultSetProxyCursorTest extends ResultSetProxyTestSupport {

    @Test
    void testAbsolute() throws SQLException {
        when(delegate.absolute(5)).thenReturn(true);
        assertTrue(proxy.absolute(5));
    }

    @Test
    void testAfterLast() throws SQLException {
        proxy.afterLast();
        verify(delegate).afterLast();
    }

    @Test
    void testBeforeFirst() throws SQLException {
        proxy.beforeFirst();
        verify(delegate).beforeFirst();
    }

    @Test
    void testFirst() throws SQLException {
        when(delegate.first()).thenReturn(true);
        assertTrue(proxy.first());
    }

    @Test
    void testGetConcurrency() throws SQLException {
        when(delegate.getConcurrency()).thenReturn(ResultSet.CONCUR_READ_ONLY);
        assertEquals(ResultSet.CONCUR_READ_ONLY, proxy.getConcurrency());
    }

    @Test
    void testGetCursorName() throws SQLException {
        when(delegate.getCursorName()).thenReturn("cursor1");
        assertEquals("cursor1", proxy.getCursorName());
    }

    @Test
    void testGetFetchDirection() throws SQLException {
        when(delegate.getFetchDirection()).thenReturn(ResultSet.FETCH_FORWARD);
        assertEquals(ResultSet.FETCH_FORWARD, proxy.getFetchDirection());
    }

    @Test
    void testGetFetchSize() throws SQLException {
        when(delegate.getFetchSize()).thenReturn(100);
        assertEquals(100, proxy.getFetchSize());
    }

    @Test
    void testGetHoldability() throws SQLException {
        when(delegate.getHoldability()).thenReturn(ResultSet.CLOSE_CURSORS_AT_COMMIT);
        assertEquals(ResultSet.CLOSE_CURSORS_AT_COMMIT, proxy.getHoldability());
    }

    @Test
    void testGetRow() throws SQLException {
        when(delegate.getRow()).thenReturn(5);
        assertEquals(5, proxy.getRow());
    }

    @Test
    void testGetType() throws SQLException {
        when(delegate.getType()).thenReturn(ResultSet.TYPE_FORWARD_ONLY);
        assertEquals(ResultSet.TYPE_FORWARD_ONLY, proxy.getType());
    }

    @Test
    void testIsAfterLast() throws SQLException {
        when(delegate.isAfterLast()).thenReturn(false);
        assertFalse(proxy.isAfterLast());
    }

    @Test
    void testIsBeforeFirst() throws SQLException {
        when(delegate.isBeforeFirst()).thenReturn(true);
        assertTrue(proxy.isBeforeFirst());
    }

    @Test
    void testIsFirst() throws SQLException {
        when(delegate.isFirst()).thenReturn(true);
        assertTrue(proxy.isFirst());
    }

    @Test
    void testIsLast() throws SQLException {
        when(delegate.isLast()).thenReturn(false);
        assertFalse(proxy.isLast());
    }

    @Test
    void testLast() throws SQLException {
        when(delegate.last()).thenReturn(true);
        assertTrue(proxy.last());
    }

    @Test
    void testPrevious() throws SQLException {
        when(delegate.previous()).thenReturn(true);
        assertTrue(proxy.previous());
    }

    @Test
    void testRelative() throws SQLException {
        when(delegate.relative(2)).thenReturn(true);
        assertTrue(proxy.relative(2));
    }

    @Test
    void testSetFetchDirection() throws SQLException {
        proxy.setFetchDirection(ResultSet.FETCH_FORWARD);
        verify(delegate).setFetchDirection(ResultSet.FETCH_FORWARD);
    }

    @Test
    void testSetFetchSize() throws SQLException {
        proxy.setFetchSize(50);
        verify(delegate).setFetchSize(50);
    }
}
