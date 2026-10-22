package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.RowDataset;

@org.junit.jupiter.api.Tag("2025")
class DataTransferPerformanceTest {

    @Test
    void unfilteredProjectionReadsOnlySelectedColumnsForBuilderAndLegacyOverload() throws Exception {
        final int rows = 100;
        final int columnCount = 500;
        final AtomicInteger reads = new AtomicInteger();
        final List<String> names = new ArrayList<>();
        final List<List<Object>> columns = new ArrayList<>();
        for (int column = 0; column < columnCount; column++) {
            names.add("c" + column);
            columns.add(new AbstractList<>() {
                @Override
                public Object get(final int row) {
                    reads.incrementAndGet();
                    return row;
                }

                @Override
                public int size() {
                    return rows;
                }
            });
        }
        final Dataset dataset = RowDataset.fromOwnedColumns(names, columns);
        reads.set(0);
        final PreparedStatement builderStatement = mock(PreparedStatement.class);
        assertEquals(rows, DataTransferUtil.importFrom(dataset).columns(List.of("c499")).batchSize(30).to(builderStatement));
        assertEquals(rows, reads.get(), "Unselected Dataset values must not be visited");
        verify(builderStatement, times(rows)).addBatch();
        verify(builderStatement, times(4)).executeBatch();
        verify(builderStatement, never()).close();

        reads.set(0);
        final PreparedStatement legacyStatement = mock(PreparedStatement.class);
        assertEquals(rows, DataTransferUtil.importData(dataset, List.of("c499"), legacyStatement));
        assertEquals(rows, reads.get());
        verify(legacyStatement, never()).close();
    }

    @Test
    void projectionPreservesDuplicatesOrderingAndFullRowCallbacks() throws Exception {
        final Dataset dataset = Dataset.rows(List.of("id", "label", "rank"), new Object[][] { { 1, "first", 10 }, { 2, "second", 20 } });
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:transfer_projection_" + System.nanoTime());
             Statement schema = connection.createStatement()) {
            schema.execute("CREATE TABLE copied_rows (first_value INT, second_value INT, third_value INT)");
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO copied_rows VALUES (?, ?, ?)")) {
                assertEquals(2, DataTransferUtil.importFrom(dataset).columns(List.of("rank", "id", "rank")).to(statement));
                assertFalse(statement.isClosed());
            }
            try (ResultSet result = schema.executeQuery("SELECT * FROM copied_rows ORDER BY second_value")) {
                assertTrue(result.next());
                assertArrayEquals(new int[] { 10, 1, 10 }, new int[] { result.getInt(1), result.getInt(2), result.getInt(3) });
                assertTrue(result.next());
                assertArrayEquals(new int[] { 20, 2, 20 }, new int[] { result.getInt(1), result.getInt(2), result.getInt(3) });
                assertFalse(result.next());
            }
        }

        final AtomicInteger filtered = new AtomicInteger();
        final PreparedStatement filteredStatement = mock(PreparedStatement.class);
        assertEquals(1, DataTransferUtil.importFrom(dataset).columns(List.of("id")).filter(row -> {
            assertEquals(3, row.length);
            filtered.incrementAndGet();
            return "second".equals(row[1]);
        }).to(filteredStatement));
        assertEquals(2, filtered.get());
        verify(filteredStatement).setInt(1, 2);

        final AtomicInteger customRows = new AtomicInteger();
        assertEquals(2, DataTransferUtil.importFrom(dataset).parameterSetter((query, row) -> {
            assertEquals(3, row.length);
            assertTrue(row[1] instanceof String);
            customRows.incrementAndGet();
        }).to(mock(PreparedStatement.class)));
        assertEquals(2, customRows.get());
    }

    @Test
    void tableCopyBorrowsEachConnectionOnceForMetadataAndRows() throws Exception {
        for (final boolean selectedColumns : List.of(false, true)) {
            final JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:copy_source_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
            final JdbcDataSource target = new JdbcDataSource();
            target.setURL("jdbc:h2:mem:copy_target_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
            try (Connection connection = source.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE source_rows (id INT, note VARCHAR(20))");
                statement.execute("INSERT INTO source_rows VALUES (1, 'one'), (2, 'two')");
            }
            try (Connection connection = target.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE target_rows (note VARCHAR(20), id INT)");
            }

            final JdbcDataSource countingSource = spy(source);
            final JdbcDataSource countingTarget = spy(target);
            final DataTransferUtil.CopyTableFromDataSource copy = DataTransferUtil.copyTable(countingSource, "source_rows");
            if (selectedColumns) {
                copy.columns(List.of("id", "note"));
            }
            assertEquals(2, copy.to(countingTarget, "target_rows"));
            verify(countingSource).getConnection();
            verify(countingTarget).getConnection();

            try (Connection connection = target.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT id, note FROM target_rows ORDER BY id")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
                assertEquals("one", rows.getString(2));
                assertTrue(rows.next());
                assertEquals(2, rows.getInt(1));
                assertEquals("two", rows.getString(2));
                assertFalse(rows.next());
            }
        }
    }
}
