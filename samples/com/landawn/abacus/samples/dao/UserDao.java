package com.landawn.abacus.samples.dao;

import java.sql.SQLException;
import java.util.List;

import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.samples.dao.handler.UserDaoHandlerA;
import com.landawn.abacus.samples.entity.User;
import com.landawn.abacus.util.JdbcUtil;
import com.landawn.abacus.util.JdbcUtil.Dao.Handler;
import com.landawn.abacus.util.JdbcUtil.Dao.Handler.Filter;
import com.landawn.abacus.util.JdbcUtil.Dao.PerfLog;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Propagation;
import com.landawn.abacus.util.SQLBuilder;
import com.landawn.abacus.util.stream.Stream;

@PerfLog(minExecutionTimeForSql = 101, minExecutionTimeForOperation = 100)
@Handler(type = UserDaoHandlerA.class)
@Handler(qualifier = "handler1", filter = @Filter(qualifier = "notForDataSource"))
public interface UserDao extends JdbcUtil.CrudDao<User, Long, SQLBuilder.PSC, UserDao> {
    @NamedInsert("INSERT INTO user (id, first_name, last_name, email) VALUES (:id, :firstName, :lastName, :email)")
    void insertWithId(User user) throws SQLException;

    @NamedUpdate("UPDATE user SET first_name = :firstName, last_name = :lastName WHERE id = :id")
    int updateFirstAndLastName(@Bind("firstName") String newFirstName, @Bind("lastName") String newLastName, @Bind("id") long id) throws SQLException;

    @NamedSelect("SELECT first_name, last_name FROM user WHERE id = :id")
    User getFirstAndLastNameBy(@Bind("id") long id) throws SQLException;

    @NamedSelect("SELECT id, first_name, last_name, email FROM user")
    Stream<User> allUsers() throws SQLException;

    @NamedInsert(sql = "INSERT INTO user (id, first_name, last_name, email) VALUES (:id, :firstName, :lastName, :email)", isBatch = true)
    List<Long> batchInsertWithId(List<User> users) throws SQLException;

    @NamedInsert(sql = "INSERT INTO user (first_name, last_name, email) VALUES (:firstName, :lastName, :email)", isBatch = true, batchSize = 123)
    List<Long> batchInsertWithoutId(List<User> users) throws SQLException;

    @NamedUpdate(sql = "UPDATE user SET first_name = :firstName, last_name = :lastName WHERE id = :id", isBatch = true)
    int batchUpdate(List<User> users) throws SQLException;

    @NamedDelete(sql = "DELETE FROM user where id = :id", isBatch = true)
    int batchDelete(List<User> users) throws SQLException;

    @NamedDelete(sql = "DELETE FROM user where id = :id", isBatch = true, batchSize = 10000)
    int batchDeleteByIds(List<Long> userIds) throws SQLException;

    default int[] batchDeleteByIds2(List<Long> userIds) throws SQLException {
        return prepareNamedQuery("DELETE FROM user where id = :id").addBatchParameters(userIds).batchUpdate();
    }

    @Sqls("SELECT * FROM user where id >= :id")
    default List<User> listUserByAnnoSql(long id, String... sqls) {
        try {
            return prepareNamedQuery(sqls[0]).setLong(1, id).list(User.class);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Sqls({ "update user set first_name = ? where id = -1", "SELECT * FROM user where id >= :id" })
    @Transactional
    default List<User> listUserByAnnoSql2(String firstName, long id, String... sqls) {
        try {
            prepareQuery(sqls[0]).setString(1, firstName).update();
            return prepareNamedQuery(sqls[1]).setLong(1, id).list(User.class);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Sqls("DELETE from user where id = ?")
    @Transactional(propagation = Propagation.SUPPORTS)
    default boolean delete_propagation_SUPPORTS(long id, String... sqls) {
        N.sleep(1001);

        try {
            return prepareQuery(sqls[0]).setLong(1, id).update() > 0;
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Sqls("DELETE from user where id = ?")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Handler(qualifier = "handler2")
    default boolean delete_propagation_REQUIRES_NEW(long id, String... sqls) {
        try {
            return prepareQuery(sqls[0]).setLong(1, id).update() > 0;
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

}