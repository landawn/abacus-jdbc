package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.EntityId;

@Tag("2025")
public class JdbcUtilRepeatedStreamTest extends TestBase {
    @Test
    void directAndBatchExecutionReplayNamedStreamsAndReaders() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            for (int kind = 0; kind < 4; kind++) {
                try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:raw_replay"); Statement stmt = conn.createStatement()) {
                    stmt.execute("create table t(a varbinary, b varbinary, c clob, d clob)");
                    Object parameters = parameters(kind);
                    String sql = "insert into t values (:p, :p, :r, :r)";
                    if (mode == 0) {
                        assertEquals(1, JdbcUtil.executeUpdate(conn, sql, parameters));
                    } else if (mode == 1) {
                        assertEquals(2, JdbcUtil.executeBatchUpdate(conn, sql, List.of(parameters, parameters(kind)), 1));
                    } else {
                        assertEquals(2, JdbcUtil.executeLargeBatchUpdate(conn, sql, List.of(parameters, parameters(kind)), 1));
                    }
                    try (ResultSet rows = stmt.executeQuery("select * from t")) {
                        int count = 0;
                        while (rows.next()) {
                            assertArrayEquals(new byte[] {1, 2, 3}, rows.getBytes(1));
                            assertArrayEquals(new byte[] {1, 2, 3}, rows.getBytes(2));
                            assertEquals("a\u03b2c", rows.getString(3));
                            assertEquals("a\u03b2c", rows.getString(4));
                            count++;
                        }
                        assertEquals(mode == 0 ? 1 : 2, count);
                    }
                }
            }
        }
    }

    private static Object parameters(int kind) {
        InputStream input = new ByteArrayInputStream(new byte[] {1, 2, 3});
        Reader reader = new StringReader("a\u03b2c");
        return switch (kind) {
            case 0 -> Map.of("p", input, "r", reader);
            case 1 -> new StreamBean(input, reader);
            case 2 -> new StreamRecord(input, reader);
            default -> EntityId.of("p", input, "r", reader);
        };
    }

    @Test
    void deferredDriverReceivesIndependentCursorsAndSingleNamesRemainLazy() throws Exception {
        PreparedStatement stmt = mock(PreparedStatement.class);
        List<InputStream> bound = new ArrayList<>();
        doAnswer(call -> { bound.add(call.getArgument(1)); return null; }).when(stmt).setBinaryStream(anyInt(), any(InputStream.class));
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[] {1, 2, 3});
        JdbcUtil.setParameters(ParsedSql.parse("select :p, :p"), stmt, new Object[] {Map.of("p", input)});
        assertEquals(2, bound.size());
        for (InputStream stream : bound) assertArrayEquals(new byte[] {1, 2, 3}, stream.readAllBytes());
        bound.clear();
        ByteArrayInputStream single = new ByteArrayInputStream(new byte[] {4});
        JdbcUtil.setParameters(ParsedSql.parse("select :p"), stmt, new Object[] {Map.of("p", single)});
        assertSame(single, bound.get(0));
        assertEquals(1, single.available());
    }

    @Test
    void validationAndClosedStatementDoNotConsumeStreams() throws Exception {
        PreparedStatement stmt = mock(PreparedStatement.class);
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[] {1, 2, 3});
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.setParameters(ParsedSql.parse("select :p, :p, :missing"), stmt,
                new Object[] {Map.of("p", input)}));
        assertEquals(3, input.available());
        when(stmt.isClosed()).thenReturn(true);
        assertThrows(SQLException.class, () -> JdbcUtil.setParameters(ParsedSql.parse("select :p, :p"), stmt, new Object[] {Map.of("p", input)}));
        assertEquals(3, input.available());
        verify(stmt, never()).setBinaryStream(anyInt(), any(InputStream.class));
    }

    @Test
    void repeatedBeanGetterIsEvaluatedOnce() throws Exception {
        CountingBean bean = new CountingBean();
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:raw_replay_getter")) {
            JdbcUtil.prepareStmt(conn, "select :p, :p", new Object[] {bean}).close();
            assertEquals(1, bean.calls);
        }
    }

    @Test
    void bufferingFailurePreservesCauseAndClosesOnlyTheStatement() throws Exception {
        IOException cause = new IOException("unreadable payload");
        InputStream input = new InputStream() {
            @Override public int read() throws IOException { throw cause; }
            @Override public void close() { fail("caller owns the stream"); }
        };
        Connection conn = mock(Connection.class);
        PreparedStatement stmt = mock(PreparedStatement.class);
        when(conn.prepareStatement("select ?, ?")).thenReturn(stmt);
        SQLException thrown = assertThrows(SQLException.class,
                () -> JdbcUtil.prepareStmt(conn, "select :p, :p", Map.of("p", input)));
        assertSame(cause, thrown.getCause());
        verify(stmt).close();
        verify(stmt, never()).setBinaryStream(anyInt(), any(InputStream.class));
        verify(conn, never()).close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void customTypesReceiveTheOriginalStreamWithoutBuffering() throws Exception {
        PreparedStatement stmt = mock(PreparedStatement.class);
        CustomInputStream input = new CustomInputStream();
        Type<CustomInputStream> custom = mock(Type.class);
        ParsedSql sql = ParsedSql.parse("select :p, :p");
        Object[] parameters = {Map.of("p", input)};
        try (MockedStatic<Type> types = mockStatic(Type.class, CALLS_REAL_METHODS)) {
            types.when(() -> Type.of(CustomInputStream.class)).thenReturn(custom);
            JdbcUtil.setParameters(sql, stmt, parameters);
            verify(custom).set(stmt, 1, input);
            verify(custom).set(stmt, 2, input);
            assertEquals(3, input.available());
        }
    }

    private static final class CustomInputStream extends ByteArrayInputStream {
        CustomInputStream() { super(new byte[] {1, 2, 3}); }
    }

    public record StreamRecord(InputStream p, Reader r) { }

    public static class StreamBean {
        @com.landawn.abacus.annotation.Type("BinaryStream")
        private InputStream p;
        @com.landawn.abacus.annotation.Type("CharacterStream")
        private Reader r;
        public StreamBean() { }
        StreamBean(InputStream p, Reader r) { this.p = p; this.r = r; }
        public InputStream getP() { return p; }
        public void setP(InputStream p) { this.p = p; }
        public Reader getR() { return r; }
        public void setR(Reader r) { this.r = r; }
    }

    public static class CountingBean {
        private int calls;
        public InputStream getP() { calls++; return new ByteArrayInputStream(new byte[] {1}); }
        public void setP(InputStream p) { }
    }
}
