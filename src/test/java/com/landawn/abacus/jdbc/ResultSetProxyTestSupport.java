package com.landawn.abacus.jdbc;

import static org.mockito.Mockito.mock;

import java.sql.ResultSet;

import org.junit.jupiter.api.BeforeEach;

import com.landawn.abacus.TestBase;

abstract class ResultSetProxyTestSupport extends TestBase {

    protected ResultSet delegate;
    protected ResultSetProxy proxy;

    @BeforeEach
    void setUp() {
        delegate = mock(ResultSet.class);
        proxy = ResultSetProxy.wrap(delegate);
    }
}
