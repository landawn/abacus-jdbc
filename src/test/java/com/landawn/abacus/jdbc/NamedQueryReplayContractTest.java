package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.EntityId;

@Tag("2025")
class NamedQueryReplayContractTest {
    @Test
    void entityIdReplayWorksThroughBothSettersAndBatches() throws Exception {
        for (int mode = 0; mode < 4; mode++) {
            try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:entity_id_replay"); Statement stmt = conn.createStatement()) {
                stmt.execute("create table t(a varbinary, b varbinary, c clob, d clob)");
                EntityId id = EntityId.of("p", new ByteArrayInputStream(new byte[] {1, 2}), "r", new StringReader("a\u03b2"));
                try (NamedQuery query = JdbcUtil.prepareNamedQuery(conn, "insert into t values(:p, :p, :r, :r)")) {
                    if (mode == 0) query.setParameters(id);
                    else if (mode == 1) query.setParameters((Object) id);
                    else if (mode == 2) query.addBatchParameters(List.of(id));
                    else query.addBatchParameters(List.of(id).iterator());
                    if (mode < 2) query.update(); else query.batchUpdate();
                }
                try (ResultSet rows = stmt.executeQuery("select * from t")) {
                    assertTrue(rows.next());
                    assertArrayEquals(new byte[] {1, 2}, rows.getBytes(1));
                    assertArrayEquals(new byte[] {1, 2}, rows.getBytes(2));
                    assertEquals("a\u03b2", rows.getString(3));
                    assertEquals("a\u03b2", rows.getString(4));
                }
            }
        }
    }

    @Test
    void annotatedBeanReplaysBinaryAndClobAsciiProperties() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:annotated_replay"); Statement stmt = conn.createStatement()) {
                stmt.execute("create table t(a varbinary, b varbinary, c clob, d clob)");
                AnnotatedBean bean = new AnnotatedBean(new ByteArrayInputStream(new byte[] {1, 2}), new ByteArrayInputStream(new byte[] {65, 66}));
                try (NamedQuery query = JdbcUtil.prepareNamedQuery(conn, "insert into t values(:p, :p, :text, :text)")) {
                    if (mode == 0) query.setParameters(bean);
                    else if (mode == 1) query.setParameters(bean, List.of("p", "text"));
                    else query.addBatchParameters(List.of(bean));
                    if (mode < 2) query.update(); else query.batchUpdate();
                }
                try (ResultSet rows = stmt.executeQuery("select * from t")) {
                    assertTrue(rows.next());
                    assertArrayEquals(new byte[] {1, 2}, rows.getBytes(1));
                    assertArrayEquals(new byte[] {1, 2}, rows.getBytes(2));
                    assertEquals("AB", rows.getString(3));
                    assertEquals("AB", rows.getString(4));
                }
            }
        }
    }

    @Test
    void everyObjectBindingFamilyRejectsClosedQueryBeforeReadingRepeatedStreams() throws Exception {
        for (int mode = 0; mode < 9; mode++) {
            PreparedStatement stmt = mock(PreparedStatement.class);
            NamedQuery query = new NamedQuery(stmt, ParsedSql.parse("select :p, :p"));
            query.close();
            ByteArrayInputStream input = new ByteArrayInputStream(new byte[] {1, 2, 3});
            final int variant = mode;
            assertThrows(IllegalStateException.class, () -> {
                switch (variant) {
                    case 0 -> query.setObject("p", input);
                    case 1 -> query.setObject("p", input, Type.of("BinaryStream"));
                    case 2 -> query.setObject("p", input, Types.BINARY);
                    case 3 -> query.setObject("p", input, Types.BINARY, 2);
                    case 4 -> query.setParameters(Map.of("p", input));
                    case 5 -> query.setParameters((Object) EntityId.of("p", input));
                    case 6 -> query.setParameters(EntityId.of("p", input));
                    case 7 -> query.setParameters(new AnnotatedBean(input, null));
                    default -> query.setParameters(new AnnotatedBean(input, null), List.of("p"));
                }
            });
            assertEquals(3, input.available());
        }
    }

    @Test
    void singleAndNullStreamBindingsStillReportDriverClosedStatementFailures() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:closed_stream_contract")) {
            for (String sql : List.of("select :p", "select :p, :p")) {
                NamedQuery query = JdbcUtil.prepareNamedQuery(conn, sql);
                query.close();
                assertThrows(SQLException.class, () -> query.setBinaryStream("p", null));
                assertThrows(SQLException.class, () -> query.setCharacterStream("p", (Reader) null));
                if (sql.equals("select :p")) {
                    ByteArrayInputStream input = new ByteArrayInputStream(new byte[] {1});
                    assertThrows(SQLException.class, () -> query.setBinaryStream("p", input));
                    assertEquals(1, input.available());
                }
            }
        }
    }

    @Test
    void negativeReaderLengthPreservesValidationAndDoesNotRead() throws Exception {
        NamedQuery query = new NamedQuery(mock(PreparedStatement.class), ParsedSql.parse("select :p, :p"));
        StringReader input = new StringReader("abc");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> query.setCharacterStream("p", input, -1));
        assertTrue(ex.getMessage().contains("-1"));
        assertEquals('a', input.read());
    }

    public static class AnnotatedBean {
        @com.landawn.abacus.annotation.Type("BinaryStream")
        private InputStream p;
        @com.landawn.abacus.annotation.Type("ClobAsciiStream")
        private InputStream text;
        public AnnotatedBean() { }
        AnnotatedBean(InputStream p, InputStream text) { this.p = p; this.text = text; }
        public InputStream getP() { return p; }
        public void setP(InputStream p) { this.p = p; }
        public InputStream getText() { return text; }
        public void setText(InputStream text) { this.text = text; }
    }
}
