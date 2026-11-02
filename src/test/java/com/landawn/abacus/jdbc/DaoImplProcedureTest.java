package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.landawn.abacus.jdbc.annotation.Bind;
import com.landawn.abacus.jdbc.annotation.OutParameter;
import com.landawn.abacus.jdbc.annotation.Query;
import com.landawn.abacus.jdbc.dao.Dao;
import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.Tuple.Tuple2;

class DaoImplProcedureTest extends JdbcUtilTestSupport {

    private ProcedureDao dao;

    @BeforeEach
    void createProcedureDao() throws SQLException {
        when(mockCallableStatement.getConnection()).thenReturn(mockConnection);
        when(mockCallableStatement.getUpdateCount()).thenReturn(-1);
        when(mockCallableStatement.getInt(1)).thenReturn(11);
        when(mockCallableStatement.getString("message")).thenReturn("done");
        dao = JdbcUtil.createDao(ProcedureDao.class, mockDataSource);
        clearInvocations(mockConnection, mockCallableStatement);
    }

    @Test
    void executesProcedureAndReadsIndexedAndNamedOutputs() throws SQLException {
        final Jdbc.OutParamResult outputs = dao.outputs();

        assertEquals(11, (Integer) outputs.getOutParamValue(1));
        assertEquals("done", outputs.getOutParamValue("message"));
        verify(mockCallableStatement).registerOutParameter(1, Types.INTEGER);
        verify(mockCallableStatement).registerOutParameter("message", Types.VARCHAR);
        verify(mockCallableStatement).setFetchSize(17);
        verify(mockCallableStatement).setQueryTimeout(9);
        verify(mockCallableStatement).execute();
        verify(mockCallableStatement).close();
        verify(mockConnection).close();
    }

    // Regression: a zero-argument procedure whose named markers are all OUT parameters (registered by name through
    // @OutParameter) failed DAO creation with "Missing bindings", although the same markers were accepted as soon as
    // the procedure had one or more IN parameters.
    @Test
    void zeroArgNamedProcedureWithOnlyOutParametersIsAccepted() throws SQLException {
        final NamedOutputsProcedureDao namedOutputsDao = JdbcUtil.createDao(NamedOutputsProcedureDao.class, mockDataSource);
        clearInvocations(mockConnection, mockCallableStatement);

        final Jdbc.OutParamResult outputs = namedOutputsDao.outputs();

        assertEquals("done", outputs.getOutParamValue("message"));
        verify(mockConnection).prepareCall("{call sample(:message)}");
        verify(mockCallableStatement).registerOutParameter("message", Types.VARCHAR);
        verify(mockCallableStatement).execute();
        verify(mockCallableStatement).close();
    }

    interface NamedOutputsProcedureDao extends Dao<DaoImplQueryContractIntegrationTest.ReviewRow, NamedOutputsProcedureDao> {
        @Query(value = "{call sample(:message)}", procedure = true, op = QueryOperation.executeAndGetOutParameters)
        @OutParameter(name = "message", sqlType = Types.VARCHAR)
        Jdbc.OutParamResult outputs() throws SQLException;
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void queryAllMapsDatasetsAndOptionallyReturnsOutputs(final boolean withOutputs) throws SQLException {
        when(mockCallableStatement.execute()).thenReturn(true);
        when(mockCallableStatement.getResultSet()).thenReturn(mockResultSet);
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("value");
        when(mockResultSetMetaData.getColumnType(1)).thenReturn(Types.INTEGER);
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(1)).thenReturn(7);
        final List<Dataset> results;

        if (withOutputs) {
            final Tuple2<List<Dataset>, Jdbc.OutParamResult> result = dao.datasetsWithOutputs();
            results = result._1;
            assertEquals(11, (Integer) result._2.getOutParamValue(1));
            assertEquals("done", result._2.getOutParamValue("message"));
        } else {
            results = dao.datasets();
        }

        assertEquals(1, results.size());
        assertEquals(List.of("value"), results.get(0).columnNames());
        assertEquals(List.of(7), results.get(0).getColumn(0));
        verify(mockResultSet).close();
        verify(mockCallableStatement).close();
        verify(mockConnection).close();
    }

    @ParameterizedTest
    @EnumSource(Binding.class)
    void procedureInputsHonorBindingMode(final Binding binding) throws SQLException {
        final Jdbc.OutParamResult result = switch (binding) {
            case NAMED -> dao.namedInputs(7, "seven");
            case POSITIONAL -> dao.positionalInputs(7, "seven");
            case COLLECTION -> dao.collectionInputs(List.of(7, "seven"));
            case ARRAY -> dao.arrayInputs(new Object[] { 7, "seven" });
        };

        assertTrue(result.getOutParamValues().isEmpty());
        if (binding == Binding.NAMED) {
            verify(mockCallableStatement).setInt("left", 7);
            verify(mockCallableStatement).setString("right", "seven");
        } else {
            verify(mockCallableStatement).setInt(1, 7);
            verify(mockCallableStatement).setString(2, "seven");
        }
        verify(mockCallableStatement).execute();
        verify(mockCallableStatement).close();
        verify(mockConnection).close();
    }

    @Test
    void collectionAsSingleParameterBindsScalarProcedureInput() throws SQLException {
        final Jdbc.OutParamResult result = dao.singleInput(7);

        assertTrue(result.getOutParamValues().isEmpty());
        verify(mockCallableStatement).setInt(1, 7);
        verify(mockCallableStatement).execute();
        verify(mockCallableStatement).close();
        verify(mockConnection).close();
    }

    @Test
    void procedureFragmentParametersBindByPosition() throws SQLException {
        final Jdbc.OutParamResult result = dao.positionalWithFragment("?", 7, "seven");

        assertTrue(result.getOutParamValues().isEmpty());
        verify(mockCallableStatement).setInt(1, 7);
        verify(mockCallableStatement).setString(2, "seven");
        verify(mockCallableStatement).execute();
        verify(mockCallableStatement).close();
        verify(mockConnection).close();
    }

    @Test
    void listAllMapsScalarResultSets() throws SQLException {
        stubSingleStringResultSet();

        final List<List<String>> results = dao.allScalarLists();

        assertEquals(List.of(List.of("one")), results);
        verify(mockResultSet).close();
        verify(mockCallableStatement).close();
        verify(mockConnection).close();
    }

    @Test
    void listAllMapsScalarResultSetsAndReturnsOutputs() throws SQLException {
        stubSingleStringResultSet();

        final Tuple2<List<List<String>>, Jdbc.OutParamResult> result = dao.allScalarListsWithOutputs();

        assertEquals(List.of(List.of("one")), result._1);
        assertEquals(11, (Integer) result._2.getOutParamValue(1));
        verify(mockResultSet).close();
        verify(mockCallableStatement).close();
        verify(mockConnection).close();
    }

    @Test
    void streamAllMapsDatasets() throws SQLException {
        stubSingleStringResultSet();

        final List<Dataset> results;
        try (com.landawn.abacus.util.stream.Stream<Dataset> stream = dao.streamDatasets()) {
            results = stream.toList();
        }

        assertEquals(1, results.size());
        assertEquals(List.of("value"), results.get(0).columnNames());
        assertEquals(List.of("one"), results.get(0).getColumn(0));
        verify(mockResultSet).close();
        verify(mockCallableStatement).close();
        verify(mockConnection).close();
    }

    @Test
    void queryMapsDatasetAndReturnsOutputs() throws SQLException {
        stubSingleStringResultSet();

        final Tuple2<Dataset, Jdbc.OutParamResult> result = dao.datasetWithOutputs();

        assertEquals(List.of("value"), result._1.columnNames());
        assertEquals(List.of("one"), result._1.getColumn(0));
        assertEquals(11, (Integer) result._2.getOutParamValue(1));
        verify(mockResultSet).close();
        verify(mockCallableStatement).close();
        verify(mockConnection).close();
    }

    @Test
    void listMapsScalarsAndReturnsOutputs() throws SQLException {
        stubSingleStringResultSet();

        final Tuple2<List<String>, Jdbc.OutParamResult> result = dao.scalarsWithOutputs();

        assertEquals(List.of("one"), result._1);
        assertEquals(11, (Integer) result._2.getOutParamValue(1));
        verify(mockResultSet).close();
        verify(mockCallableStatement).close();
        verify(mockConnection).close();
    }

    private void stubSingleStringResultSet() throws SQLException {
        when(mockCallableStatement.execute()).thenReturn(true);
        when(mockCallableStatement.getResultSet()).thenReturn(mockResultSet);
        when(mockCallableStatement.getMoreResults(java.sql.Statement.KEEP_CURRENT_RESULT)).thenReturn(false);
        when(mockCallableStatement.getMoreResults()).thenReturn(false);
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("value");
        when(mockResultSetMetaData.getColumnType(1)).thenReturn(Types.VARCHAR);
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getString(1)).thenReturn("one");
        when(mockResultSet.getObject(1)).thenReturn("one");
    }

    private enum Binding {
        NAMED, POSITIONAL, COLLECTION, ARRAY
    }

    interface ProcedureDao extends Dao<DaoImplQueryContractIntegrationTest.ReviewRow, ProcedureDao> {
        @Query(value = "{call sample(?, ?)}", procedure = true, op = QueryOperation.executeAndGetOutParameters, fetchSize = 17, queryTimeoutSeconds = 9)
        @OutParameter(position = 1, sqlType = Types.INTEGER)
        @OutParameter(name = "message", sqlType = Types.VARCHAR)
        Jdbc.OutParamResult outputs() throws SQLException;

        @Query(value = "{call sample()}", procedure = true, op = QueryOperation.queryAll)
        List<Dataset> datasets() throws SQLException;

        @Query(value = "{call sample(?, ?)}", procedure = true, op = QueryOperation.queryAll)
        @OutParameter(position = 1, sqlType = Types.INTEGER)
        @OutParameter(name = "message", sqlType = Types.VARCHAR)
        Tuple2<List<Dataset>, Jdbc.OutParamResult> datasetsWithOutputs() throws SQLException;

        @Query(value = "{call sample()}", procedure = true, op = QueryOperation.listAll)
        List<List<String>> allScalarLists() throws SQLException;

        @Query(value = "{call sample()}", procedure = true, op = QueryOperation.listAll)
        @OutParameter(position = 1, sqlType = Types.INTEGER)
        Tuple2<List<List<String>>, Jdbc.OutParamResult> allScalarListsWithOutputs() throws SQLException;

        @Query(value = "{call sample()}", procedure = true, op = QueryOperation.streamAll)
        com.landawn.abacus.util.stream.Stream<Dataset> streamDatasets();

        @Query(value = "{call sample()}", procedure = true, op = QueryOperation.query)
        @OutParameter(position = 1, sqlType = Types.INTEGER)
        Tuple2<Dataset, Jdbc.OutParamResult> datasetWithOutputs() throws SQLException;

        @Query(value = "{call sample()}", procedure = true, op = QueryOperation.list)
        @OutParameter(position = 1, sqlType = Types.INTEGER)
        Tuple2<List<String>, Jdbc.OutParamResult> scalarsWithOutputs() throws SQLException;

        @Query(value = "{call bind_values(?)}", procedure = true, op = QueryOperation.executeAndGetOutParameters, collectionAsSingleParameter = true)
        Jdbc.OutParamResult singleInput(int value) throws SQLException;

        @Query(value = "{call bind_values({column}, ?)}", procedure = true, op = QueryOperation.executeAndGetOutParameters)
        Jdbc.OutParamResult positionalWithFragment(@com.landawn.abacus.jdbc.annotation.SqlFragment("column") String column, int first, String second)
                throws SQLException;

        @Query(value = "{call bind_values(?, ?)}", procedure = true, op = QueryOperation.executeAndGetOutParameters)
        Jdbc.OutParamResult namedInputs(@Bind("left") int first, @Bind("right") String second) throws SQLException;

        @Query(value = "{call bind_values(?, ?)}", procedure = true, op = QueryOperation.executeAndGetOutParameters)
        Jdbc.OutParamResult positionalInputs(int first, String second) throws SQLException;

        @Query(value = "{call bind_values(?, ?)}", procedure = true, op = QueryOperation.executeAndGetOutParameters)
        Jdbc.OutParamResult collectionInputs(List<Object> values) throws SQLException;

        @Query(value = "{call bind_values(?, ?)}", procedure = true, op = QueryOperation.executeAndGetOutParameters)
        Jdbc.OutParamResult arrayInputs(Object[] values) throws SQLException;
    }
}
