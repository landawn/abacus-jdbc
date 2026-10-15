package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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

import com.landawn.abacus.type.Type;
import com.landawn.abacus.type.TypeFactory;
import com.landawn.abacus.util.ImmutableList;

class NamedQueryObjectBindingTest extends NamedQueryTestSupport {

    @Test
    public void testSetURL() throws SQLException, MalformedURLException {
        String paramName = "param1";
        URL value = new URL("http://example.com");

        NamedQuery result = namedQuery.setURL(paramName, value);

        verify(mockPreparedStatement).setURL(1, value);
        assertSame(namedQuery, result);
    }

    // --- setURL all paths ---

    @Test
    public void testSetURL_LoopBranch_ParamNotFound() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setURL("unknown", new URL("http://example.com")));
    }

    @Test
    public void testSetURL_MapBranch_NotFound() throws Exception {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setURL("unknown", new URL("http://example.com")));
    }

    @Test
    public void testSetURL_MapBranch_OneOccurrence() throws Exception {
        URL url = new URL("http://example.com");
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setURL("a", url);
        verify(mockPreparedStatement).setURL(1, url);
    }

    @Test
    public void testSetURL_MapBranch_TwoOccurrences() throws Exception {
        URL url = new URL("http://example.com");
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setURL("p", url);
        verify(mockPreparedStatement, times(2)).setURL(anyInt(), eq(url));
    }

    @Test
    public void testSetURL_MapBranch_ThreeOccurrences() throws Exception {
        URL url = new URL("http://example.com");
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setURL("p", url);
        verify(mockPreparedStatement, times(3)).setURL(anyInt(), eq(url));
    }

    @Test
    public void testSetURL_MapBranch_FourOccurrences() throws Exception {
        URL url = new URL("http://example.com");
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setURL("p", url);
        verify(mockPreparedStatement, times(4)).setURL(anyInt(), eq(url));
    }

    @Test
    public void testSetSQLXML() throws SQLException {
        String paramName = "param1";
        java.sql.SQLXML value = mock(java.sql.SQLXML.class);

        NamedQuery result = namedQuery.setSQLXML(paramName, value);

        verify(mockPreparedStatement).setSQLXML(1, value);
        assertSame(namedQuery, result);
    }

    // --- setSQLXML all paths ---

    @Test
    public void testSetSQLXML_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setSQLXML("unknown", mock(SQLXML.class)));
    }

    @Test
    public void testSetSQLXML_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setSQLXML("unknown", mock(SQLXML.class)));
    }

    @Test
    public void testSetSQLXML_MapBranch_OneOccurrence() throws SQLException {
        SQLXML xml = mock(SQLXML.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setSQLXML("b", xml);
        verify(mockPreparedStatement).setSQLXML(2, xml);
    }

    @Test
    public void testSetSQLXML_MapBranch_TwoOccurrences() throws SQLException {
        SQLXML xml = mock(SQLXML.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setSQLXML("p", xml);
        verify(mockPreparedStatement, times(2)).setSQLXML(anyInt(), eq(xml));
    }

    @Test
    public void testSetSQLXML_MapBranch_ThreeOccurrences() throws SQLException {
        SQLXML xml = mock(SQLXML.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setSQLXML("p", xml);
        verify(mockPreparedStatement, times(3)).setSQLXML(anyInt(), eq(xml));
    }

    @Test
    public void testSetSQLXML_MapBranch_FourOccurrences() throws SQLException {
        SQLXML xml = mock(SQLXML.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setSQLXML("p", xml);
        verify(mockPreparedStatement, times(4)).setSQLXML(anyInt(), eq(xml));
    }

    @Test
    public void testSetRowId() throws SQLException {
        String paramName = "param1";
        java.sql.RowId value = mock(java.sql.RowId.class);

        NamedQuery result = namedQuery.setRowId(paramName, value);

        verify(mockPreparedStatement).setRowId(1, value);
        assertSame(namedQuery, result);
    }

    // --- setRowId all paths ---

    @Test
    public void testSetRowId_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setRowId("unknown", mock(RowId.class)));
    }

    @Test
    public void testSetRowId_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setRowId("unknown", mock(RowId.class)));
    }

    @Test
    public void testSetRowId_MapBranch_OneOccurrence() throws SQLException {
        RowId rowId = mock(RowId.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setRowId("c", rowId);
        verify(mockPreparedStatement).setRowId(3, rowId);
    }

    @Test
    public void testSetRowId_MapBranch_TwoOccurrences() throws SQLException {
        RowId rowId = mock(RowId.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setRowId("p", rowId);
        verify(mockPreparedStatement, times(2)).setRowId(anyInt(), eq(rowId));
    }

    @Test
    public void testSetRowId_MapBranch_ThreeOccurrences() throws SQLException {
        RowId rowId = mock(RowId.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setRowId("p", rowId);
        verify(mockPreparedStatement, times(3)).setRowId(anyInt(), eq(rowId));
    }

    @Test
    public void testSetRowId_MapBranch_FourOccurrences() throws SQLException {
        RowId rowId = mock(RowId.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setRowId("p", rowId);
        verify(mockPreparedStatement, times(4)).setRowId(anyInt(), eq(rowId));
    }

    @Test
    public void testSetRef() throws SQLException {
        String paramName = "param1";
        java.sql.Ref value = mock(java.sql.Ref.class);

        NamedQuery result = namedQuery.setRef(paramName, value);

        verify(mockPreparedStatement).setRef(1, value);
        assertSame(namedQuery, result);
    }

    // --- setRef all paths ---

    @Test
    public void testSetRef_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setRef("unknown", mock(Ref.class)));
    }

    @Test
    public void testSetRef_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setRef("unknown", mock(Ref.class)));
    }

    @Test
    public void testSetRef_MapBranch_OneOccurrence() throws SQLException {
        Ref ref = mock(Ref.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setRef("d", ref);
        verify(mockPreparedStatement).setRef(4, ref);
    }

    @Test
    public void testSetRef_MapBranch_TwoOccurrences() throws SQLException {
        Ref ref = mock(Ref.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setRef("p", ref);
        verify(mockPreparedStatement, times(2)).setRef(anyInt(), eq(ref));
    }

    @Test
    public void testSetRef_MapBranch_ThreeOccurrences() throws SQLException {
        Ref ref = mock(Ref.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setRef("p", ref);
        verify(mockPreparedStatement, times(3)).setRef(anyInt(), eq(ref));
    }

    @Test
    public void testSetRef_MapBranch_FourOccurrences() throws SQLException {
        Ref ref = mock(Ref.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setRef("p", ref);
        verify(mockPreparedStatement, times(4)).setRef(anyInt(), eq(ref));
    }

    @Test
    public void testSetArray() throws SQLException {
        String paramName = "param1";
        java.sql.Array value = mock(java.sql.Array.class);

        NamedQuery result = namedQuery.setArray(paramName, value);

        verify(mockPreparedStatement).setArray(1, value);
        assertSame(namedQuery, result);
    }

    // --- setArray all paths ---

    @Test
    public void testSetArray_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setArray("unknown", mock(Array.class)));
    }

    @Test
    public void testSetArray_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setArray("unknown", mock(Array.class)));
    }

    @Test
    public void testSetArray_MapBranch_OneOccurrence() throws SQLException {
        Array arr = mock(Array.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setArray("e", arr);
        verify(mockPreparedStatement).setArray(5, arr);
    }

    @Test
    public void testSetArray_MapBranch_TwoOccurrences() throws SQLException {
        Array arr = mock(Array.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setArray("p", arr);
        verify(mockPreparedStatement, times(2)).setArray(anyInt(), eq(arr));
    }

    @Test
    public void testSetArray_MapBranch_ThreeOccurrences() throws SQLException {
        Array arr = mock(Array.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setArray("p", arr);
        verify(mockPreparedStatement, times(3)).setArray(anyInt(), eq(arr));
    }

    @Test
    public void testSetArray_MapBranch_FourOccurrences() throws SQLException {
        Array arr = mock(Array.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setArray("p", arr);
        verify(mockPreparedStatement, times(4)).setArray(anyInt(), eq(arr));
    }

    @Test
    public void testSetObject() throws SQLException {
        String paramName = "param1";
        Object value = "test object";

        NamedQuery result = namedQuery.setObject(paramName, value);

        verify(mockPreparedStatement).setString(1, "test object");
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetObjectWithSqlType() throws SQLException {
        String paramName = "param1";
        Object value = "test";
        int sqlType = Types.VARCHAR;

        NamedQuery result = namedQuery.setObject(paramName, value, sqlType);

        verify(mockPreparedStatement).setObject(1, value, sqlType);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetObjectWithSqlTypeAndScale() throws SQLException {
        String paramName = "param1";
        Object value = new BigDecimal("123.45");
        int sqlType = Types.DECIMAL;
        int scale = 2;

        NamedQuery result = namedQuery.setObject(paramName, value, sqlType, scale);

        verify(mockPreparedStatement).setObject(1, value, sqlType, scale);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetObjectWithSQLType() throws SQLException {
        String paramName = "param1";
        Object value = "test";
        SQLType sqlType = mock(SQLType.class);

        NamedQuery result = namedQuery.setObject(paramName, value, sqlType);

        verify(mockPreparedStatement).setObject(1, value, sqlType);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetObjectWithSQLTypeAndScale() throws SQLException {
        String paramName = "param1";
        Object value = new BigDecimal("123.45");
        SQLType sqlType = mock(SQLType.class);
        int scale = 2;

        NamedQuery result = namedQuery.setObject(paramName, value, sqlType, scale);

        verify(mockPreparedStatement).setObject(1, value, sqlType, scale);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetObjectWithType() throws SQLException {
        String paramName = "param1";
        String value = "test";
        Type<String> type = TypeFactory.getType(String.class);

        NamedQuery result = namedQuery.setObject(paramName, value, type);

        // Type would handle the actual setting
        assertSame(namedQuery, result);
    }

    // --- setObject(Object) all paths ---

    @Test
    public void testSetObject_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setObject("unknown", (Object) null));
    }

    @Test
    public void testSetObject_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setObject("unknown", (Object) null));
    }

    @Test
    public void testSetObject_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setObject("a", (Object) null); // null goes through stmt.setObject directly
        verify(mockPreparedStatement).setObject(1, (Object) null);
    }

    @Test
    public void testSetObject_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setObject("p", (Object) null);
        verify(mockPreparedStatement, times(2)).setObject(anyInt(), isNull());
    }

    @Test
    public void testSetObject_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setObject("p", (Object) null);
        verify(mockPreparedStatement, times(3)).setObject(anyInt(), isNull());
    }

    @Test
    public void testSetObject_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setObject("p", (Object) null);
        verify(mockPreparedStatement, times(4)).setObject(anyInt(), isNull());
    }

    // --- setObject(Object, int) all paths ---

    @Test
    public void testSetObjectWithSqlType_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setObject("unknown", "value", Types.VARCHAR));
    }

    @Test
    public void testSetObjectWithSqlType_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setObject("unknown", "value", Types.VARCHAR));
    }

    @Test
    public void testSetObjectWithSqlType_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setObject("b", "val", Types.VARCHAR);
        verify(mockPreparedStatement).setObject(2, "val", Types.VARCHAR);
    }

    @Test
    public void testSetObjectWithSqlType_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setObject("p", "val", Types.VARCHAR);
        verify(mockPreparedStatement, times(2)).setObject(anyInt(), eq("val"), eq(Types.VARCHAR));
    }

    @Test
    public void testSetObjectWithSqlType_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setObject("p", "val", Types.VARCHAR);
        verify(mockPreparedStatement, times(3)).setObject(anyInt(), eq("val"), eq(Types.VARCHAR));
    }

    @Test
    public void testSetObjectWithSqlType_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setObject("p", "val", Types.VARCHAR);
        verify(mockPreparedStatement, times(4)).setObject(anyInt(), eq("val"), eq(Types.VARCHAR));
    }

    // --- setObject(Object, int, int) all paths ---

    @Test
    public void testSetObjectWithSqlTypeScale_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setObject("unknown", "value", Types.DECIMAL, 2));
    }

    @Test
    public void testSetObjectWithSqlTypeScale_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setObject("unknown", "value", Types.DECIMAL, 2));
    }

    @Test
    public void testSetObjectWithSqlTypeScale_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setObject("c", "9.99", Types.DECIMAL, 2);
        verify(mockPreparedStatement).setObject(3, "9.99", Types.DECIMAL, 2);
    }

    @Test
    public void testSetObjectWithSqlTypeScale_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setObject("p", "9.99", Types.DECIMAL, 2);
        verify(mockPreparedStatement, times(2)).setObject(anyInt(), eq("9.99"), eq(Types.DECIMAL), eq(2));
    }

    @Test
    public void testSetObjectWithSqlTypeScale_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setObject("p", "9.99", Types.DECIMAL, 2);
        verify(mockPreparedStatement, times(3)).setObject(anyInt(), eq("9.99"), eq(Types.DECIMAL), eq(2));
    }

    @Test
    public void testSetObjectWithSqlTypeScale_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setObject("p", "9.99", Types.DECIMAL, 2);
        verify(mockPreparedStatement, times(4)).setObject(anyInt(), eq("9.99"), eq(Types.DECIMAL), eq(2));
    }

    // --- setObject(Object, SQLType) all paths ---

    @Test
    public void testSetObjectWithJDBCType_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setObject("unknown", "value", JDBCType.VARCHAR));
    }

    @Test
    public void testSetObjectWithJDBCType_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setObject("unknown", "value", JDBCType.VARCHAR));
    }

    @Test
    public void testSetObjectWithJDBCType_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setObject("d", "val", JDBCType.VARCHAR);
        verify(mockPreparedStatement).setObject(4, "val", JDBCType.VARCHAR);
    }

    @Test
    public void testSetObjectWithJDBCType_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setObject("p", "val", JDBCType.VARCHAR);
        verify(mockPreparedStatement, times(2)).setObject(anyInt(), eq("val"), eq((SQLType) JDBCType.VARCHAR));
    }

    @Test
    public void testSetObjectWithJDBCType_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setObject("p", "val", JDBCType.VARCHAR);
        verify(mockPreparedStatement, times(3)).setObject(anyInt(), eq("val"), eq((SQLType) JDBCType.VARCHAR));
    }

    @Test
    public void testSetObjectWithJDBCType_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setObject("p", "val", JDBCType.VARCHAR);
        verify(mockPreparedStatement, times(4)).setObject(anyInt(), eq("val"), eq((SQLType) JDBCType.VARCHAR));
    }

    // --- setObject(Object, SQLType, int) all paths ---

    @Test
    public void testSetObjectWithJDBCTypeScale_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setObject("unknown", "val", JDBCType.DECIMAL, 2));
    }

    @Test
    public void testSetObjectWithJDBCTypeScale_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setObject("unknown", "val", JDBCType.DECIMAL, 2));
    }

    @Test
    public void testSetObjectWithJDBCTypeScale_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setObject("e", "9.99", JDBCType.DECIMAL, 2);
        verify(mockPreparedStatement).setObject(5, "9.99", JDBCType.DECIMAL, 2);
    }

    @Test
    public void testSetObjectWithJDBCTypeScale_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setObject("p", "9.99", JDBCType.DECIMAL, 2);
        verify(mockPreparedStatement, times(2)).setObject(anyInt(), eq("9.99"), eq((SQLType) JDBCType.DECIMAL), eq(2));
    }

    @Test
    public void testSetObjectWithJDBCTypeScale_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setObject("p", "9.99", JDBCType.DECIMAL, 2);
        verify(mockPreparedStatement, times(3)).setObject(anyInt(), eq("9.99"), eq((SQLType) JDBCType.DECIMAL), eq(2));
    }

    @Test
    public void testSetObjectWithJDBCTypeScale_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setObject("p", "9.99", JDBCType.DECIMAL, 2);
        verify(mockPreparedStatement, times(4)).setObject(anyInt(), eq("9.99"), eq((SQLType) JDBCType.DECIMAL), eq(2));
    }

    // --- setObject(String, Object, Type): all uncovered paths (L3811-3836) ---

    @Test
    public void testSetObjectWithType_LoopBranch_ParamNotFound() throws SQLException {
        final Type<String> type = TypeFactory.getType(String.class);
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setObject("unknown", "val", type));
    }

    @Test
    public void testSetObjectWithType_MapBranch_NotFound() throws SQLException {
        final Type<String> type = TypeFactory.getType(String.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setObject("unknown", "val", type));
    }

    @Test
    public void testSetObjectWithType_MapBranch_OneOccurrence() throws SQLException {
        final Type<String> type = TypeFactory.getType(String.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setObject("a", "hello", type);
        verify(mockPreparedStatement).setString(1, "hello");
    }

    @Test
    public void testSetObjectWithType_MapBranch_TwoOccurrences() throws SQLException {
        final Type<String> type = TypeFactory.getType(String.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setObject("p", "hi", type);
        verify(mockPreparedStatement, times(2)).setString(anyInt(), eq("hi"));
    }

    @Test
    public void testSetObjectWithType_MapBranch_ThreeOccurrences() throws SQLException {
        final Type<String> type = TypeFactory.getType(String.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setObject("p", "hi", type);
        verify(mockPreparedStatement, times(3)).setString(anyInt(), eq("hi"));
    }

    @Test
    public void testSetObjectWithType_MapBranch_FourOccurrences() throws SQLException {
        final Type<String> type = TypeFactory.getType(String.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setObject("p", "hi", type);
        verify(mockPreparedStatement, times(4)).setString(anyInt(), eq("hi"));
    }

    @Test
    public void testSetObjectNull() throws SQLException {
        String paramName = "param1";
        Object value = null;

        NamedQuery result = namedQuery.setObject(paramName, value);

        verify(mockPreparedStatement).setObject(1, null);
        assertSame(namedQuery, result);
    }
}
