package com.landawn.abacus.samples.dao;

import java.sql.SQLException;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import com.landawn.abacus.condition.ConditionFactory.CF;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.samples.dao.handler.UserDaoHandlerA;
import com.landawn.abacus.samples.entity.User;
import com.landawn.abacus.util.JdbcUtil;
import com.landawn.abacus.util.JdbcUtil.Dao;
import com.landawn.abacus.util.JdbcUtil.Dao.Cache;
import com.landawn.abacus.util.JdbcUtil.Dao.CacheResult;
import com.landawn.abacus.util.JdbcUtil.Dao.Handler;
import com.landawn.abacus.util.JdbcUtil.Dao.PerfLog;
import com.landawn.abacus.util.JdbcUtil.Dao.RefreshCache;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Propagation;
import com.landawn.abacus.util.SQLBuilder;
import com.landawn.abacus.util.SQLBuilder.PSC;
import com.landawn.abacus.util.stream.Stream;

@PerfLog(minExecutionTimeForSql = 101, minExecutionTimeForOperation = 100)
@Handler(type = UserDaoHandlerA.class)
@Handler(qualifier = "handler1", filter = ".*")
@CacheResult(transfer = "none")
@Cache(capacity = 1000, evictDelay = 6000)
@RefreshCache
@Dao.Config(addLimitForSingleQuery = true)
public interface UserDao extends JdbcUtil.CrudDao<User, Long, SQLBuilder.PSC, UserDao>, JdbcUtil.JoinEntityHelper<User, SQLBuilder.PSC, UserDao> {

    @NonDBOperation
    @Override
    default Long generateId() {
        return System.currentTimeMillis();
    }

    @NamedInsert("INSERT INTO user (id, first_name, last_name, email) VALUES (:id, :firstName, :lastName, :email)")
    void insertWithId(User user) throws SQLException;

    @NamedUpdate("UPDATE user SET first_name = :firstName, last_name = :lastName WHERE id = :id")
    int updateFirstAndLastName(@Bind("firstName") String newFirstName, @Bind("lastName") String newLastName, @Bind("id") long id) throws SQLException;

    @SqlLogEnabled
    @NamedSelect("SELECT first_name, last_name FROM user WHERE id = :id")
    User getFirstAndLastNameBy(@Bind("id") long id) throws SQLException;

    @SqlLogEnabled(false)
    @NamedSelect("SELECT id, first_name, last_name, email FROM user")
    Stream<User> allUsers();

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

    @Delete(sql = "DELETE FROM user where id = ?", isBatch = true, batchSize = 10000)
    int batchDeleteByIds_1(List<Long> userIds) throws SQLException;

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

    @NamedDelete(sql = "DELETE FROM {tableName} where id = :id")
    int deleteByIdWithDefine(@Define("tableName") String tableName, @Bind("id") long id) throws SQLException;

    @NamedDelete(sql = "DELETE FROM {tableName} where id = :id", isBatch = true, batchSize = 10000)
    int deleteByIdsWithDefine(@Define("tableName") String tableName, List<Long> userIds) throws SQLException;

    @NamedSelect(sql = "SELECT * FROM {tableName} where id = :id ORDER BY {{orderBy}}")
    User selectByIdWithDefine(@Define("tableName") String tableName, @Define("{{orderBy}}") String orderBy, @Bind("id") long id) throws SQLException;

    @Select(sql = "SELECT * FROM {tableName} where id >= ? ORDER BY {whatever -> orderBy{{P}}")
    List<User> selectByIdWithDefine_2(@Define("tableName") String tableName, @Define("{whatever -> orderBy{{P}}") String orderBy, long id) throws SQLException;

    @Select(sql = "SELECT * FROM {tableName} where id >= ? AND first_name != ? ORDER BY {whatever -> orderBy{{P}} LIMIT {count}")
    List<User> selectByIdWithDefine_3(@Define("tableName") String tableName, long id, @Define("{whatever -> orderBy{{P}}") String orderBy,
            @Define("{count}") long count, String firstName) throws SQLException;

    @NamedSelect(sql = "SELECT * FROM {tableName} where id >= :id AND first_name != :firstName ORDER BY {whatever -> orderBy{{P}} LIMIT {count}")
    List<User> selectByIdWithDefine_4(@Define("tableName") String tableName, @Bind("id") long id, @Define("{whatever -> orderBy{{P}}") String orderBy,
            @Define("{count}") long count, @Bind("firstName") String firstName) throws SQLException;

    @NamedSelect(sql = "SELECT * FROM {tableName} where id = :id ORDER BY {{orderBy}}")
    boolean exists(@Define("tableName") String tableName, @Define("{{orderBy}}") String orderBy, @Bind("id") long id) throws SQLException;

    @NamedSelect(sql = "SELECT * FROM {tableName} where id = :id ORDER BY {{orderBy}}", op = OP.exists)
    boolean isThere(@Define("tableName") String tableName, @Define("{{orderBy}}") String orderBy, @Bind("id") long id) throws SQLException;

    @SqlField
    String sql_listToSet = PSC.selectFrom(User.class).where(CF.gt("id")).sql();

    @Select(id = "sql_listToSet")
    Set<User> listToSet(int id) throws SQLException;

    @Select("select * from user where id > ?")
    Queue<User> listToCollection(int id) throws SQLException;

    //    @Select("select * from user where id > ?")
    //    Queue<List<Object>> list(int id, RowFilter rowFilter, RowMapper<List<Object>> rowMapper) throws SQLException;
}