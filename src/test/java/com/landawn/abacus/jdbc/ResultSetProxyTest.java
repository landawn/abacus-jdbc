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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ResultSetProxyTest extends ResultSetProxyTestSupport {

    @Test
    void streamingCloseReadsToEndOnceAndLeavesDelegateOpen() throws SQLException {
        final ResultSet delegate = mock(ResultSet.class);
        when(delegate.next()).thenReturn(true, true, false);
        final ResultSetProxy proxy = ResultSetProxy.wrap(delegate, true);

        assertFalse(proxy.isClosed());
        proxy.close();
        proxy.close();

        // Connector/J fails to close a streamed result while the next one streams; the statement releases it.
        verify(delegate, Mockito.times(3)).next();
        verify(delegate, Mockito.never()).close();
        assertTrue(proxy.isClosed());
    }

    @Test
    void streamingClosePropagatesReadFailureWithoutClosingDelegate() throws SQLException {
        final ResultSet delegate = mock(ResultSet.class);
        final SQLException readFailure = new SQLException("read failed");
        when(delegate.next()).thenThrow(readFailure);
        final ResultSetProxy proxy = ResultSetProxy.wrap(delegate, true);

        assertSame(readFailure, assertThrows(SQLException.class, proxy::close));
        proxy.close();

        verify(delegate).next();
        verify(delegate, Mockito.never()).close();
        assertTrue(proxy.isClosed());
    }

    @Test
    void streamingCloseDoesNotReadAnAlreadyClosedDelegate() throws SQLException {
        final ResultSet delegate = mock(ResultSet.class);
        when(delegate.isClosed()).thenReturn(true);

        ResultSetProxy.wrap(delegate, true).close();

        verify(delegate, Mockito.never()).next();
        verify(delegate, Mockito.never()).close();
    }

    @Test
    void testWrap_UnwrapsImplementedInterfaceToProxy() throws SQLException {
        ResultSet delegate = Mockito.mock(ResultSet.class);

        ResultSetProxy proxy = ResultSetProxy.wrap(delegate);

        assertSame(proxy, proxy.unwrap(ResultSet.class));
    }

    @Test
    void testDelegatesRowAccessMethods() throws SQLException {
        ResultSet delegate = Mockito.mock(ResultSet.class);
        when(delegate.next()).thenReturn(true);
        when(delegate.getString(1)).thenReturn("value");

        ResultSetProxy proxy = ResultSetProxy.wrap(delegate);

        assertTrue(proxy.next());
        assertEquals("value", proxy.getString(1));
        verify(delegate).next();
        verify(delegate).getString(1);
    }

    @Test
    void testCloseAndWrapperChecksProxyFirst() throws SQLException {
        ResultSet delegate = Mockito.mock(ResultSet.class);

        ResultSetProxy proxy = ResultSetProxy.wrap(delegate);

        assertTrue(proxy.isWrapperFor(ResultSet.class));
        proxy.close();

        verify(delegate).close();
    }

    @Test
    void testWrapperChecksDelegateForVendorInterface() throws SQLException {
        final ResultSet delegate = Mockito.mock(ResultSet.class);
        final VendorResultSet vendorResultSet = Mockito.mock(VendorResultSet.class);
        when(delegate.isWrapperFor(VendorResultSet.class)).thenReturn(true);
        when(delegate.unwrap(VendorResultSet.class)).thenReturn(vendorResultSet);

        final ResultSetProxy proxy = ResultSetProxy.wrap(delegate);

        assertTrue(proxy.isWrapperFor(VendorResultSet.class));
        assertSame(vendorResultSet, proxy.unwrap(VendorResultSet.class));
        verify(delegate).isWrapperFor(VendorResultSet.class);
        verify(delegate).unwrap(VendorResultSet.class);
    }

    @Test
    void testGetMetaData() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(delegate.getMetaData()).thenReturn(meta);
        assertSame(meta, proxy.getMetaData());
    }

    @Test
    void testGetStatement() throws SQLException {
        Statement stmt = Mockito.mock(Statement.class);
        when(delegate.getStatement()).thenReturn(stmt);
        assertSame(stmt, proxy.getStatement());
    }

    @Test
    void testIsClosed() throws SQLException {
        when(delegate.isClosed()).thenReturn(false);
        assertFalse(proxy.isClosed());
    }

    @Test
    void testGetWarnings() throws SQLException {
        assertNull(proxy.getWarnings());
    }

    @Test
    void testClearWarnings() throws SQLException {
        proxy.clearWarnings();
        verify(delegate).clearWarnings();
    }

    @Test
    void proxyConstructorRejectsNullWhileTheFactoryStillAcceptsIt() {
        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> new ResultSetProxy(null));

        assertTrue(failure.getMessage().contains("delegate"));
        assertNull(ResultSetProxy.wrap(null));
    }

    private interface VendorResultSet extends ResultSet {
        // Test-only vendor extension.
    }
}
