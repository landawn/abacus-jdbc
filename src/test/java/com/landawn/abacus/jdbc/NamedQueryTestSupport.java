package com.landawn.abacus.jdbc;

import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.util.ImmutableList;

abstract class NamedQueryTestSupport extends TestBase {

    // TODO: The remaining NamedQuery setter overload matrix mirrors AbstractQuery parameter binding behavior. Add
    // targeted tests only when a stable statement fixture is needed beyond the representative coverage already here.

    @Mock
    protected PreparedStatement mockPreparedStatement;

    @Mock
    protected Connection mockConnection;

    @Mock
    protected ResultSet mockResultSet;

    @Mock
    protected ParsedSql mockParsedSql;

    protected NamedQuery namedQuery;

    private AutoCloseable mocks;

    @BeforeEach
    public void setUp() throws Exception {
        mocks = MockitoAnnotations.openMocks(this);
        when(mockPreparedStatement.getConnection()).thenReturn(mockConnection);
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("param1", "param2"));
        when(mockParsedSql.parameterCount()).thenReturn(2);
        when(mockParsedSql.originalSql()).thenReturn("SELECT * FROM table WHERE col1 = :param1 AND col2 = :param2");

        namedQuery = new NamedQuery(mockPreparedStatement, mockParsedSql);
    }

    // Tests for the index-by-map code path (parameterCount >= MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP = 5)
    // These exercise the else branch that uses paramNameIndexMap instead of linear scan

    protected NamedQuery createManyParamQuery() throws Exception {
        // 5 parameters triggers index-by-map path
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("a", "b", "c", "d", "e"));
        when(mockParsedSql.parameterCount()).thenReturn(5);
        when(mockParsedSql.originalSql()).thenReturn("SELECT * FROM t WHERE a=:a AND b=:b AND c=:c AND d=:d AND e=:e");
        return new NamedQuery(mockPreparedStatement, mockParsedSql);
    }

    // Helper to create a NamedQuery with paramCount >= MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP (5)
    // so the paramNameIndexMap branch is exercised.
    protected NamedQuery createMapBranchQuery(final ImmutableList<String> names) throws SQLException {
        when(mockParsedSql.namedParameters()).thenReturn(names);
        when(mockParsedSql.parameterCount()).thenReturn(names.size());
        return new NamedQuery(mockPreparedStatement, mockParsedSql);
    }

    @AfterEach
    void closeMocks() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }
}
