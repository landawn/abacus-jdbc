package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.util.ImmutableList;

/**
 * Regression tests pinning NamedQuery contracts verified during code review:
 * constructor statement cleanup, close-on-unknown-parameter, repeated-name fan-out,
 * negative stream length rejection, and tolerant Map-based binding.
 */
public class NamedQueryReviewFixTest extends TestBase {

    @Test
    void constructorClosesStatementWhenParameterMetadataIsInconsistent() throws Exception {
        final PreparedStatement stmt = mock(PreparedStatement.class);
        final ParsedSql parsedSql = mock(ParsedSql.class);
        when(parsedSql.namedParameters()).thenReturn(ImmutableList.of("a", "b"));
        when(parsedSql.parameterCount()).thenReturn(1);
        when(parsedSql.originalSql()).thenReturn("SELECT * FROM t WHERE x = :a");

        assertThrows(IllegalArgumentException.class, () -> new NamedQuery(stmt, parsedSql));
        verify(stmt).close();
    }

    @Test
    void unknownParameterNameClosesQueryOnLinearLookupPath() throws Exception {
        final PreparedStatement stmt = mock(PreparedStatement.class);
        final NamedQuery query = new NamedQuery(stmt, ParsedSql.parse("SELECT * FROM t WHERE x = :a AND y = :b"));

        assertThrows(IllegalArgumentException.class, () -> query.setString("nope", "v"));
        assertTrue(query.isClosed);
        verify(stmt).close();
    }

    @Test
    void unknownParameterNameClosesQueryOnMapLookupPath() throws Exception {
        final PreparedStatement stmt = mock(PreparedStatement.class);
        final NamedQuery query = new NamedQuery(stmt, ParsedSql.parse("SELECT * FROM t WHERE a = :a AND b = :b AND c = :c AND d = :d AND e = :e"));

        assertThrows(IllegalArgumentException.class, () -> query.setInt("nope", 1));
        assertTrue(query.isClosed);
        verify(stmt).close();
    }

    @Test
    void repeatedParameterNameBindsEveryOccurrence() throws Exception {
        final PreparedStatement stmt = mock(PreparedStatement.class);
        final NamedQuery query = new NamedQuery(stmt, ParsedSql.parse("SELECT * FROM t WHERE x = :v OR y = :v"));

        query.setInt("v", 42);

        verify(stmt).setInt(1, 42);
        verify(stmt).setInt(2, 42);
        assertFalse(query.isClosed);
    }

    @Test
    void negativeStreamLengthIsRejectedWithIllegalArgumentException() throws Exception {
        final PreparedStatement stmt = mock(PreparedStatement.class);
        final NamedQuery query = new NamedQuery(stmt, ParsedSql.parse("SELECT * FROM t WHERE x = :v"));

        assertThrows(IllegalArgumentException.class, () -> query.setBinaryStream("v", new ByteArrayInputStream(new byte[] { 1 }), -1));
        assertTrue(query.isClosed);
    }

    @Test
    void setParametersFromMapIgnoresKeysThatAreNotParameters() throws Exception {
        final PreparedStatement stmt = mock(PreparedStatement.class);
        final NamedQuery query = new NamedQuery(stmt, ParsedSql.parse("SELECT * FROM t WHERE x = :a AND y = :b"));

        final Map<String, Object> params = new HashMap<>();
        params.put("a", "alpha");
        params.put("unknownKey", "ignored");

        query.setParameters(params);

        verify(stmt).setString(1, "alpha");
        verifyNoMoreInteractions(stmt);
        assertFalse(query.isClosed);
    }

    @Test
    void setNullByNameUsesMappedPositionsAndRequestedSqlType() throws Exception {
        final PreparedStatement stmt = mock(PreparedStatement.class);
        final NamedQuery query = new NamedQuery(stmt, ParsedSql.parse("SELECT * FROM t WHERE x = :v OR y = :v"));

        query.setNull("v", Types.INTEGER);

        verify(stmt).setNull(1, Types.INTEGER);
        verify(stmt).setNull(2, Types.INTEGER);
    }

    @Test
    void selectiveBeanBindingRejectsListedNameThatIsNotASqlParameter() throws Exception {
        final PreparedStatement stmt = mock(PreparedStatement.class);
        final NamedQuery query = new NamedQuery(stmt, ParsedSql.parse("SELECT * FROM t WHERE x = :name"));

        final Bean bean = new Bean();
        bean.setName("n");

        assertThrows(IllegalArgumentException.class, () -> query.setParameters(bean, List.of("age")));
        assertTrue(query.isClosed);
        verify(stmt).close();
    }

    @Test
    void emptyBatchParametersAreANoOp() throws Exception {
        final PreparedStatement stmt = mock(PreparedStatement.class);
        final NamedQuery query = new NamedQuery(stmt, ParsedSql.parse("INSERT INTO t (x) VALUES (:v)"));

        query.addBatchParameters(List.of());

        verify(stmt, never()).addBatch();
        assertFalse(query.isClosed);
    }

    public static class Bean {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }
    }
}
