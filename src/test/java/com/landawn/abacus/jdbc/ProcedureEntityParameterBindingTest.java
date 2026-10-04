package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.jdbc.DaoReviewRegressionTest.ReviewRow;
import com.landawn.abacus.jdbc.DaoReviewRegressionTest.ScoreRecord;
import com.landawn.abacus.jdbc.annotation.Bind;
import com.landawn.abacus.jdbc.annotation.Query;
import com.landawn.abacus.jdbc.dao.CrudDao;
import com.landawn.abacus.util.EntityId;

/**
 * Only a Map is exempt from the "entity parameter requires named SQL" rule for positional procedure SQL. A bean, record or
 * EntityId still cannot supply positional procedure parameters, even when annotated with {@code @Bind} or bound through
 * {@code collectionAsSingleParameter}, as documented on {@link Query#procedure()}.
 */
@Tag("2025")
@SuppressWarnings({ "rawtypes", "unchecked" })
public class ProcedureEntityParameterBindingTest extends TestBase {
    interface PositionalBoundBeanDao extends CrudDao<ReviewRow, Long, PositionalBoundBeanDao> {
        @Query(value = "{call update_score(?)}", procedure = true, op = QueryOperation.update)
        int updateScore(@Bind("score") ReviewRow row) throws SQLException;
    }

    interface PositionalBoundRecordDao extends CrudDao<ReviewRow, Long, PositionalBoundRecordDao> {
        @Query(value = "{call update_score(?)}", procedure = true, op = QueryOperation.update)
        int updateScore(@Bind("score") ScoreRecord score) throws SQLException;
    }

    interface PositionalBoundEntityIdDao extends CrudDao<ReviewRow, Long, PositionalBoundEntityIdDao> {
        @Query(value = "{call update_score(?)}", procedure = true, op = QueryOperation.update)
        int updateScore(@Bind("score") EntityId score) throws SQLException;
    }

    interface PositionalSingleValueBeanDao extends CrudDao<ReviewRow, Long, PositionalSingleValueBeanDao> {
        @Query(value = "{call update_score(?)}", procedure = true, op = QueryOperation.update, collectionAsSingleParameter = true)
        int updateScore(ReviewRow row) throws SQLException;
    }

    interface PositionalBoundMapDao extends CrudDao<ReviewRow, Long, PositionalBoundMapDao> {
        @Query(value = "{call update_score(?)}", procedure = true, op = QueryOperation.update)
        int updateScore(@Bind("score") Map<String, Object> score) throws SQLException;
    }

    interface NamedBoundBeanDao extends CrudDao<ReviewRow, Long, NamedBoundBeanDao> {
        @Query(value = "{call update_score(:score)}", procedure = true, op = QueryOperation.update)
        int updateScore(@Bind("score") ReviewRow row) throws SQLException;
    }

    private DataSource dataSource;
    private CallableStatement statement;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        statement = mock(CallableStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("H2");
        when(metadata.getDatabaseProductVersion()).thenReturn("2");
        when(connection.prepareCall(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);
    }

    @Test
    void positionalProcedureRejectsBoundEntityParameters() {
        for (final Class<?> daoClass : new Class<?>[] { PositionalBoundBeanDao.class, PositionalBoundRecordDao.class, PositionalBoundEntityIdDao.class,
                PositionalSingleValueBeanDao.class }) {
            final UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                    () -> JdbcUtil.createDao((Class) daoClass, dataSource), daoClass.getSimpleName());
            // The procedure-specific message must not tell the caller to use named SQL or that a Map is unsupported.
            assertTrue(e.getMessage().contains("positional procedure call"), e.getMessage());
            assertTrue(e.getMessage().contains("pass a Map"), e.getMessage());
        }
    }

    @Test
    void positionalProcedureStillAcceptsABoundMap() throws SQLException {
        final PositionalBoundMapDao dao = JdbcUtil.createDao(PositionalBoundMapDao.class, dataSource);
        assertEquals(1, dao.updateScore(Map.of("score", 42)));
        verify(statement).executeUpdate();
    }

    @Test
    void namedProcedureBindsABoundBeanAsOneValue() throws SQLException {
        final NamedBoundBeanDao dao = JdbcUtil.createDao(NamedBoundBeanDao.class, dataSource);
        final ReviewRow row = new ReviewRow();
        row.setScore(7);
        assertEquals(1, dao.updateScore(row));
        // The whole object is bound once, under the @Bind name, through the driver's named callable setter.
        assertTrue(mockingDetails(statement).getInvocations()
                .stream()
                .anyMatch(call -> call.getMethod().getName().startsWith("set") && call.getArguments().length == 2 && "score".equals(call.getArgument(0))),
                mockingDetails(statement).getInvocations().toString());
        verify(statement).executeUpdate();
    }
}
