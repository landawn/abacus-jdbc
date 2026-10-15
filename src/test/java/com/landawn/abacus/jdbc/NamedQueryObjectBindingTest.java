package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.net.URL;
import java.sql.Array;
import java.sql.JDBCType;
import java.sql.Ref;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.SQLXML;
import java.sql.Types;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.landawn.abacus.type.Type;
import com.landawn.abacus.type.TypeFactory;

class NamedQueryObjectBindingTest extends NamedQueryBindingTestSupport {

    @Test
    void testSetURL() throws SQLException, MalformedURLException {
        String paramName = "param1";
        URL value = new URL("http://example.com");

        NamedQuery result = namedQuery.setURL(paramName, value);

        verify(mockPreparedStatement).setURL(1, value);
        assertSame(namedQuery, result);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setURL_matchesNamedParameterPositions(final ParameterLayout layout) throws SQLException, MalformedURLException {
        final URL url = new URL("http://example.com");
        assertNamedBinding(layout, (query, parameter) -> query.setURL(parameter, url), (statement, index) -> statement.setURL(index, url));
    }

    @Test
    void testSetSQLXML() throws SQLException {
        String paramName = "param1";
        java.sql.SQLXML value = mock(java.sql.SQLXML.class);

        NamedQuery result = namedQuery.setSQLXML(paramName, value);

        verify(mockPreparedStatement).setSQLXML(1, value);
        assertSame(namedQuery, result);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setSQLXML_matchesNamedParameterPositions(final ParameterLayout layout) throws SQLException {
        final SQLXML xml = mock(SQLXML.class);
        assertNamedBinding(layout, (query, parameter) -> query.setSQLXML(parameter, xml), (statement, index) -> statement.setSQLXML(index, xml));
    }

    @Test
    void testSetRowId() throws SQLException {
        String paramName = "param1";
        java.sql.RowId value = mock(java.sql.RowId.class);

        NamedQuery result = namedQuery.setRowId(paramName, value);

        verify(mockPreparedStatement).setRowId(1, value);
        assertSame(namedQuery, result);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setRowId_matchesNamedParameterPositions(final ParameterLayout layout) throws SQLException {
        final RowId rowId = mock(RowId.class);
        assertNamedBinding(layout, (query, parameter) -> query.setRowId(parameter, rowId), (statement, index) -> statement.setRowId(index, rowId));
    }

    @Test
    void testSetRef() throws SQLException {
        String paramName = "param1";
        java.sql.Ref value = mock(java.sql.Ref.class);

        NamedQuery result = namedQuery.setRef(paramName, value);

        verify(mockPreparedStatement).setRef(1, value);
        assertSame(namedQuery, result);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setRef_matchesNamedParameterPositions(final ParameterLayout layout) throws SQLException {
        final Ref ref = mock(Ref.class);
        assertNamedBinding(layout, (query, parameter) -> query.setRef(parameter, ref), (statement, index) -> statement.setRef(index, ref));
    }

    @Test
    void testSetArray() throws SQLException {
        String paramName = "param1";
        java.sql.Array value = mock(java.sql.Array.class);

        NamedQuery result = namedQuery.setArray(paramName, value);

        verify(mockPreparedStatement).setArray(1, value);
        assertSame(namedQuery, result);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setArray_matchesNamedParameterPositions(final ParameterLayout layout) throws SQLException {
        final Array arr = mock(Array.class);
        assertNamedBinding(layout, (query, parameter) -> query.setArray(parameter, arr), (statement, index) -> statement.setArray(index, arr));
    }

    @Test
    void testSetObject() throws SQLException {
        String paramName = "param1";
        Object value = "test object";

        NamedQuery result = namedQuery.setObject(paramName, value);

        verify(mockPreparedStatement).setString(1, "test object");
        assertSame(namedQuery, result);
    }

    @Test
    void testSetObjectWithSqlType() throws SQLException {
        String paramName = "param1";
        Object value = "test";
        int sqlType = Types.VARCHAR;

        NamedQuery result = namedQuery.setObject(paramName, value, sqlType);

        verify(mockPreparedStatement).setObject(1, value, sqlType);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetObjectWithSqlTypeAndScale() throws SQLException {
        String paramName = "param1";
        Object value = new BigDecimal("123.45");
        int sqlType = Types.DECIMAL;
        int scale = 2;

        NamedQuery result = namedQuery.setObject(paramName, value, sqlType, scale);

        verify(mockPreparedStatement).setObject(1, value, sqlType, scale);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetObjectWithSQLType() throws SQLException {
        String paramName = "param1";
        Object value = "test";
        SQLType sqlType = mock(SQLType.class);

        NamedQuery result = namedQuery.setObject(paramName, value, sqlType);

        verify(mockPreparedStatement).setObject(1, value, sqlType);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetObjectWithSQLTypeAndScale() throws SQLException {
        String paramName = "param1";
        Object value = new BigDecimal("123.45");
        SQLType sqlType = mock(SQLType.class);
        int scale = 2;

        NamedQuery result = namedQuery.setObject(paramName, value, sqlType, scale);

        verify(mockPreparedStatement).setObject(1, value, sqlType, scale);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetObjectWithType() throws SQLException {
        String paramName = "param1";
        String value = "test";
        Type<String> type = TypeFactory.getType(String.class);

        NamedQuery result = namedQuery.setObject(paramName, value, type);

        // Type would handle the actual setting
        assertSame(namedQuery, result);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setObject_matchesNamedParameterPositions(final ParameterLayout layout) throws SQLException {
        assertNamedBinding(layout, (query, parameter) -> query.setObject(parameter, (Object) null),
                (statement, index) -> statement.setObject(index, (Object) null));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setObjectWithSqlType_matchesNamedParameterPositions(final ParameterLayout layout) throws SQLException {
        assertNamedBinding(layout, (query, parameter) -> query.setObject(parameter, "val", Types.VARCHAR),
                (statement, index) -> statement.setObject(index, "val", Types.VARCHAR));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setObjectWithSqlTypeScale_matchesNamedParameterPositions(final ParameterLayout layout) throws SQLException {
        assertNamedBinding(layout, (query, parameter) -> query.setObject(parameter, "9.99", Types.DECIMAL, 2),
                (statement, index) -> statement.setObject(index, "9.99", Types.DECIMAL, 2));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setObjectWithJDBCType_matchesNamedParameterPositions(final ParameterLayout layout) throws SQLException {
        assertNamedBinding(layout, (query, parameter) -> query.setObject(parameter, "val", JDBCType.VARCHAR),
                (statement, index) -> statement.setObject(index, "val", JDBCType.VARCHAR));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setObjectWithJDBCTypeScale_matchesNamedParameterPositions(final ParameterLayout layout) throws SQLException {
        assertNamedBinding(layout, (query, parameter) -> query.setObject(parameter, "9.99", JDBCType.DECIMAL, 2),
                (statement, index) -> statement.setObject(index, "9.99", JDBCType.DECIMAL, 2));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setObjectWithType_matchesNamedParameterPositions(final ParameterLayout layout) throws SQLException {
        final Type<String> type = TypeFactory.getType(String.class);
        assertNamedBinding(layout, (query, parameter) -> query.setObject(parameter, "hello", type), (statement, index) -> statement.setString(index, "hello"));
    }

    @Test
    void testSetObjectNull() throws SQLException {
        String paramName = "param1";
        Object value = null;

        NamedQuery result = namedQuery.setObject(paramName, value);

        verify(mockPreparedStatement).setObject(1, null);
        assertSame(namedQuery, result);
    }
}
