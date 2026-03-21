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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;

@Tag("2025")
public class ResultSetProxyTest extends TestBase {

    @Test
    public void testWrapNull() {
        assertNull(ResultSetProxy.wrap(null));
    }

    @Test
    public void testWrap_ReturnsProxy() throws SQLException {
        ResultSet delegate = Mockito.mock(ResultSet.class);
        when(delegate.unwrap(ResultSet.class)).thenReturn(delegate);

        ResultSetProxy proxy = ResultSetProxy.wrap(delegate);

        assertSame(delegate, proxy.unwrap(ResultSet.class));
    }

    @Test
    public void testDelegatesRowAccessMethods() throws SQLException {
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
    public void testCloseAndWrapperChecksDelegate() throws SQLException {
        ResultSet delegate = Mockito.mock(ResultSet.class);
        when(delegate.isWrapperFor(ResultSet.class)).thenReturn(true);

        ResultSetProxy proxy = ResultSetProxy.wrap(delegate);

        assertTrue(proxy.isWrapperFor(ResultSet.class));
        proxy.close();

        verify(delegate).close();
        verify(delegate).isWrapperFor(ResultSet.class);
    }
}
