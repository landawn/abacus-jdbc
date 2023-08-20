package com.landawn.abacus.samples.dao;

import java.sql.SQLException;
import java.util.List;

import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.OP;
import com.landawn.abacus.jdbc.Propagation;
import com.landawn.abacus.jdbc.annotation.Bind;
import com.landawn.abacus.jdbc.annotation.Define;
import com.landawn.abacus.jdbc.annotation.DefineList;
import com.landawn.abacus.jdbc.annotation.Delete;
import com.landawn.abacus.jdbc.annotation.Handler;
import com.landawn.abacus.jdbc.annotation.Insert;
import com.landawn.abacus.jdbc.annotation.PerfLog;
import com.landawn.abacus.jdbc.annotation.Select;
import com.landawn.abacus.jdbc.annotation.SqlLogEnabled;
import com.landawn.abacus.jdbc.annotation.Sqls;
import com.landawn.abacus.jdbc.annotation.Transactional;
import com.landawn.abacus.jdbc.annotation.Update;
import com.landawn.abacus.jdbc.dao.UncheckedCrudDao;
import com.landawn.abacus.jdbc.dao.UncheckedJoinEntityHelper;
import com.landawn.abacus.samples.entity.User;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.SQLBuilder;
import com.landawn.abacus.util.stream.Stream;

@PerfLog(minExecutionTimeForSql = 101, minExecutionTimeForOperation = 100)
public interface UncheckedUserDao
        extends UncheckedCrudDao<User, Long, SQLBuilder.PSC, UncheckedUserDao>, UncheckedJoinEntityHelper<User, SQLBuilder.PSC, UncheckedUserDao> {
    @Insert("INSERT INTO user (id, first_name, last_name, email) VALUES (:id, :firstName, :lastName, :email)")
    void insertWithId(User user);

    @Update("UPDATE user SET first_name = :firstName, last_name = :lastName WHERE id = :id")
    int updateFirstAndLastName(@Bind("firstName") String newFirstName, @Bind("lastName") String newLastName, @Bind("id") long id);

    @SqlLogEnabled
    @Select("SELECT first_name, last_name FROM user WHERE id = :id")
    User getFirstAndLastNameBy(@Bind("id") long id);

    @SqlLogEnabled(false)
    @Select("SELECT id, first_name, last_name, email FROM user")
    Stream<User> allUsers();

    @Insert(sql = "INSERT INTO user (id, first_name, last_name, email) VALUES (:id, :firstName, :lastName, :email)", isBatch = true)
    List<Long> batchInsertWithId(List<User> users);

    @Insert(sql = "INSERT INTO user (first_name, last_name, email) VALUES (:firstName, :lastName, :email)", isBatch = true, batchSize = 123)
    List<Long> batchInsertWithoutId(List<User> users);

    @Update(sql = "UPDATE user SET first_name = :firstName, last_name = :lastName WHERE id = :id", isBatch = true)
    int batchUpdate(List<User> users);

    @Delete(sql = "DELETE FROM user where id = :id", isBatch = true)
    int batchDelete(List<User> users);

    @Delete(sql = "DELETE FROM user where id = :id", isBatch = true, batchSize = 10000)
    int batchDeleteByIds(List<Long> userIds);

    @Delete(sql = "DELETE FROM user where id = ?", isBatch = true, batchSize = 10000)
    int batchDeleteByIds_1(List<Long> userIds);

    default int[] batchDeleteByIds_2(List<Long> userIds) throws SQLException {
        return prepareNamedQuery("DELETE FROM user where id = :id").addBatchParameters(userIds, long.class).batchUpdate();
    }

    @Sqls({ "SELECT * FROM user where id >= :id", "SELECT * FROM user where id >= :id" })
    default List<User> listUserByAnnoSql(long id, String... sqls) {
        try {
            return prepareNamedQuery(sqls[0]).setLong(1, id).list(User.class);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Transactional
    @Sqls({ "update user set first_name = ? where id = -1", "SELECT * FROM user where id >= :id" })
    default List<User> listUserByAnnoSql2(String firstName, long id, String... sqls) {
        try {
            prepareQuery(sqls[0]).setString(1, firstName).update();
            return prepareNamedQuery(sqls[1]).setLong(1, id).list(User.class);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Transactional(propagation = Propagation.SUPPORTS)
    @Sqls("DELETE from user where id = ?")
    default boolean delete_propagation_SUPPORTS(long id, String... sqls) {
        N.sleep(1001);

        try {
            return prepareQuery(sqls[0]).setLong(1, id).update() > 0;
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Handler(qualifier = "handler2")
    @Sqls("DELETE from user where id = ?")
    default boolean delete_propagation_REQUIRES_NEW(long id, String... sqls) {
        try {
            return prepareQuery(sqls[0]).setLong(1, id).update() > 0;
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Delete(sql = "DELETE FROM {tableName} where id = :id")
    int deleteByIdWithDefine(@Define("tableName") String tableName, @Bind("id") long id);

    @Delete(sql = "DELETE FROM {tableName} where id = :id", isBatch = true, batchSize = 10000)
    int deleteByIdsWithDefine(@Define("tableName") String tableName, List<Long> userIds);

    @Select(sql = "SELECT * FROM {tableName} where id = :id ORDER BY {{orderBy}}")
    User selectByIdWithDefine(@Define("tableName") String tableName, @DefineList("{{orderBy}}") List<String> orderByFields, @Bind("id") long id);

    @Select(sql = "SELECT * FROM {tableName} where id >= ? ORDER BY {whatever -> orderBy{{P}}")
    List<User> selectByIdWithDefine_2(@Define("tableName") String tableName, @Define("{whatever -> orderBy{{P}}") String orderBy, long id);

    @Select(sql = "SELECT * FROM {tableName} where id >= ? AND first_name != ? ORDER BY {whatever -> orderBy{{P}} LIMIT {count}")
    List<User> selectByIdWithDefine_3(@Define("tableName") String tableName, long id, @Define("{whatever -> orderBy{{P}}") String orderBy,
            @Define("{count}") long count, String firstName);

    @Select(sql = "SELECT * FROM {tableName} where id >= :id AND first_name != :firstName ORDER BY {whatever -> orderBy{{P}} LIMIT {count}")
    List<User> selectByIdWithDefine_4(@Define("tableName") String tableName, @Bind("id") long id, @Define("{whatever -> orderBy{{P}}") String orderBy,
            @Define("{count}") long count, @Bind("firstName") String firstName);

    @Select(sql = "SELECT * FROM {tableName} where id = :id ORDER BY {{orderBy}}")
    boolean exists(@Define("tableName") String tableName, @Define("{{orderBy}}") String orderBy, @Bind("id") long id);

    @Select(sql = "SELECT * FROM {tableName} where id = :id ORDER BY {{orderBy}}", op = OP.exists)
    boolean isThere(@Define("tableName") String tableName, @Define("{{orderBy}}") String orderBy, @Bind("id") long id) throws SQLException;

}