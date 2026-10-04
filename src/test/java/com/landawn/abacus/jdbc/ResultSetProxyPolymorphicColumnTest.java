package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("2025")
class ResultSetProxyPolymorphicColumnTest {

    @Test
    void aDateInTheFirstRowDoesNotForceLaterJavaObjectsThroughGetDate() throws Exception {
        final Date date = Date.valueOf("2026-01-02");
        final Timestamp timestamp = Timestamp.valueOf("2026-01-03 12:34:56.123456789");

        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:polymorphic_column"); Statement statement = connection.createStatement()) {
            statement.execute("create table items (id integer, payload java_object)");
            try (PreparedStatement insert = connection.prepareStatement("insert into items values (?, ?)")) {
                int id = 0;
                for (Object value : new Object[] { date, timestamp, "text", null }) {
                    insert.setInt(1, ++id);
                    insert.setObject(2, value, Types.JAVA_OBJECT);
                    insert.executeUpdate();
                }
            }

            for (boolean byLabel : new boolean[] { false, true }) {
                try (ResultSet result = ResultSetProxy.wrap(statement.executeQuery("select payload from items order by id"))) {
                    assertEquals(Object.class.getName(), result.getMetaData().getColumnClassName(1));
                    assertTrue(result.next());
                    assertEquals(date, getValue(result, byLabel));
                    assertTrue(result.next());
                    final Object actualTimestamp = getValue(result, byLabel);
                    assertInstanceOf(Timestamp.class, actualTimestamp);
                    assertEquals(timestamp, actualTimestamp);
                    assertTrue(result.next());
                    assertEquals("text", getValue(result, byLabel));
                    assertTrue(result.next());
                    assertNull(getValue(result, byLabel));
                }
            }
        }
    }

    private static Object getValue(final ResultSet result, final boolean byLabel) throws Exception {
        return byLabel ? result.getObject("payload") : result.getObject(1);
    }
}
