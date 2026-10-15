package com.landawn.abacus.jdbc;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.landawn.abacus.TestBase;

abstract class PreparedQueryTestSupport extends TestBase {

    @Mock
    protected PreparedStatement mockStmt;

    @Mock
    protected Connection mockConnection;

    @Mock
    protected DatabaseMetaData mockDatabaseMetaData;

    @Mock
    protected PreparedStatement mockPreparedStatement;

    @Mock
    protected ResultSet mockResultSet;

    @Mock
    protected ResultSetMetaData mockResultSetMetaData;

    protected PreparedQuery query;

    protected AutoCloseable mocks;

    @BeforeEach
    public void setUp() throws SQLException {
        mocks = MockitoAnnotations.openMocks(this);
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("MySQL");
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn("8");
        when(mockConnection.prepareStatement(anyString())).thenReturn(mockPreparedStatement, mockPreparedStatement, mockPreparedStatement);
        when(mockConnection.prepareStatement(anyString(), anyInt(), anyInt())).thenReturn(mockPreparedStatement, mockPreparedStatement, mockPreparedStatement);
        when(mockStmt.executeQuery()).thenReturn(mockResultSet, mockResultSet, mockResultSet);
        when(mockStmt.getResultSet()).thenReturn(mockResultSet, mockResultSet, mockResultSet);
        when(mockResultSet.getMetaData()).thenReturn(mockResultSetMetaData, mockResultSetMetaData, mockResultSetMetaData);
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1, 1, 1, 1, 1, 1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("name", "name", "name", "name", "name");

        query = new PreparedQuery(mockStmt);
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (query != null) {
            query.close();
        }
        if (mocks != null) {
            mocks.close();
        }
    }

    // Helper test entity class
    public static class TestEntity {
        private Long id;
        private String name;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
