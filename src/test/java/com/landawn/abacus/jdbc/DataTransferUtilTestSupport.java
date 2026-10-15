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

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.RowDataset;

abstract class DataTransferUtilTestSupport extends TestBase {

    // TODO: The remaining DataTransferUtil importData overload matrix shares the same batching core. Add focused coverage for
    // still-uncovered delegating overloads when a reusable file/stream fixture set is available.

    @Mock
    protected DataSource mockDataSource;

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

    protected Dataset mockDataset;

    private AutoCloseable mocks;

    @BeforeEach
    public void setUp() throws SQLException {
        mocks = MockitoAnnotations.openMocks(this);
        when(mockDataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.getMetaData()).thenReturn(mockDatabaseMetaData);
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("MySQL");
        when(mockDatabaseMetaData.getIdentifierQuoteString()).thenReturn("`");
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn("8");
        when(mockConnection.prepareStatement(anyString())).thenReturn(mockPreparedStatement);
        when(mockConnection.prepareStatement(anyString(), anyInt(), anyInt())).thenReturn(mockPreparedStatement);
        when(mockPreparedStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.getMetaData()).thenReturn(mockResultSetMetaData);
        mockDataset = Mockito.mock(RowDataset.class);
    }

    @AfterEach
    void closeMocks() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }
}
