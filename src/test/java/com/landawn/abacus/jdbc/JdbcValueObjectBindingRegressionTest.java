package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.RowId;
import java.sql.SQLXML;
import java.sql.Struct;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.landawn.abacus.query.ParsedSql;

class JdbcValueObjectBindingRegressionTest {
    enum JdbcValue {
        XML(SQLXML.class, "setSQLXML"),
        ARRAY(Array.class, "setArray"),
        REF(Ref.class, "setRef"),
        ROW_ID(RowId.class, "setRowId"),
        STRUCT(Struct.class, "setObject");

        final Class<?> type;
        final String setter;

        JdbcValue(final Class<?> type, final String setter) {
            this.type = type;
            this.setter = setter;
        }

        void verifyIndex(final PreparedStatement statement, final int index, final Object value) throws Exception {
            PreparedStatement.class.getMethod(setter, int.class, this == STRUCT ? Object.class : type).invoke(verify(statement), index, value);
        }
    }

    // Guard the public binding API, not just TypeFactory: JDBC values previously reached JSON/bean serialization here.
    @ParameterizedTest
    @EnumSource(JdbcValue.class)
    void setObjectPreservesJdbcValuesAcrossQueryWrappers(final JdbcValue kind) throws Exception {
        final Object value = mock(kind.type);
        final PreparedStatement prepared = mock(PreparedStatement.class);
        new PreparedQuery(prepared).setObject(1, value);
        kind.verifyIndex(prepared, 1, value);
        verifyNoMoreInteractions(prepared);

        final PreparedStatement named = mock(PreparedStatement.class);
        new NamedQuery(named, ParsedSql.parse("SELECT :value, :value")).setObject("value", value);
        kind.verifyIndex(named, 1, value);
        kind.verifyIndex(named, 2, value);
        verifyNoMoreInteractions(named);

        final CallableStatement callable = mock(CallableStatement.class);
        new CallableQuery(callable).setObject(1, value);
        kind.verifyIndex(callable, 1, value);
        verifyNoMoreInteractions(callable);
    }

    @Test
    void sqlXmlSetObjectStoresItsContent() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:xml_binding_" + System.nanoTime())) {
            final SQLXML xml = conn.createSQLXML();
            try {
                xml.setString("<a>1</a>");
                try (PreparedQuery query = JdbcUtil.prepareQuery(conn, "SELECT CAST(? AS CLOB)")) {
                    assertEquals("<a>1</a>", query.setObject(1, xml).queryForString().get());
                }
            } finally {
                xml.free();
            }
        }
    }
}
