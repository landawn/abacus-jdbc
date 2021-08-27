package com.landawn.abacus.samples.dao;

import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import com.landawn.abacus.condition.ConditionFactory.CF;
import com.landawn.abacus.dao.CrudDao;
import com.landawn.abacus.dao.JoinEntityHelper;
import com.landawn.abacus.dao.OP;
import com.landawn.abacus.dao.annotation.Bind;
import com.landawn.abacus.dao.annotation.BindList;
import com.landawn.abacus.dao.annotation.Config;
import com.landawn.abacus.dao.annotation.Define;
import com.landawn.abacus.dao.annotation.Delete;
import com.landawn.abacus.dao.annotation.Handler;
import com.landawn.abacus.dao.annotation.NamedDelete;
import com.landawn.abacus.dao.annotation.NamedInsert;
import com.landawn.abacus.dao.annotation.NamedSelect;
import com.landawn.abacus.dao.annotation.NamedUpdate;
import com.landawn.abacus.dao.annotation.NonDBOperation;
import com.landawn.abacus.dao.annotation.PerfLog;
import com.landawn.abacus.dao.annotation.Select;
import com.landawn.abacus.dao.annotation.SqlField;
import com.landawn.abacus.dao.annotation.SqlLogEnabled;
import com.landawn.abacus.dao.annotation.SqlMapper;
import com.landawn.abacus.dao.annotation.Sqls;
import com.landawn.abacus.dao.annotation.Transactional;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.samples.dao.handler.UserDaoHandlerA;
import com.landawn.abacus.samples.entity.User;
import com.landawn.abacus.util.ExceptionalStream;
import com.landawn.abacus.util.ImmutableList;
import com.landawn.abacus.util.JdbcUtil;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Propagation;
import com.landawn.abacus.util.SQLBuilder;
import com.landawn.abacus.util.SQLBuilder.PSC;
import com.landawn.abacus.util.Tuple.Tuple3;
import com.landawn.abacus.util.u.Optional;
import com.landawn.abacus.util.stream.Stream;

@PerfLog(minExecutionTimeForSql = 101, minExecutionTimeForOperation = 100)
@Handler(type = UserDaoHandlerA.class)
@Handler(qualifier = "handler1", filter = ".*")
@Handler(qualifier = "handler2", filter = ".*", isForInvokeFromOutsideOfDaoOnly = true)
@Config(addLimitForSingleQuery = true, callGenerateIdForInsertIfIdNotSet = false)
@SqlMapper("./samples/userSqlMapper.xml")
public interface UserDao extends CrudDao<User, Long, SQLBuilder.PSC, UserDao>, JoinEntityHelper<User, SQLBuilder.PSC, UserDao> {

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

    @NamedInsert(sql = "INSERT INTO user (id, first_name, last_name, prop1, email) VALUES (:id, :firstName, :lastName, :nickName, :email)", isBatch = true)
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

    @Select(id = "sql_listToSet")
    Set<User> listToSet(int id) throws SQLException;

    @Select("select * from user where id > ?")
    Queue<User> listToCollection(int id) throws SQLException;

    @Select(value = "select first_name from user where id >= ?", fetchSize = 100)
    ExceptionalStream<String, SQLException> streamOne(long id);

    @Select(value = "select first_name from user where id >= ?", fetchSize = 100)
    Stream<String> streamOne_2(long id);

    @Select(value = "select first_name from user where id >= ?", fetchSize = 100)
    Optional<String> getOne(long id) throws SQLException;

    //    @Select(value = "select first_name from user where id >= ?", fetchSize = 100)
    //    java.util.Optional<String> getOne_2(long id);

    @Select("select * from user where id > ?")
    @Handler(qualifier = "innerHandler_1")
    Queue<User> testInnerHandler(int id) throws SQLException;

    //    @Select("select * from user where id > ?")
    //    Queue<List<Object>> list(int id, RowFilter rowFilter, RowMapper<List<Object>> rowMapper) throws SQLException;

    @Select("select * from user where id in ({ids})")
    Queue<User> listByIds(@BindList("ids") int[] ids) throws SQLException;

    @Select("select * from user where first_name != ? and id in ({ids}) and last_name != ?")
    Queue<User> listByIds_01(String firstNameToExclude, @BindList("ids") long[] ids, String LastNameToExclude) throws SQLException;

    @Select("select * from user where id in ({ids}) and last_name != ?")
    Queue<User> listByIds_02(@BindList("ids") Object[] ids, String LastNameToExclude) throws SQLException;

    @Select("select * from user where id in ({ids}) and last_name not in ({lastNames})")
    Queue<User> listByIds_03(@BindList("ids") Collection<Long> ids, @BindList("lastNames") Collection<String> lastNamesToExclude) throws SQLException;

    static final class SqlTable {

        @SqlField
        static final String sql_listToSet = PSC.selectFrom(User.class).where(CF.gt("id")).sql();
    }

    static final class Handlers {
        static final JdbcUtil.Handler<UserDao> innerHandler_1 = new JdbcUtil.Handler<UserDao>() {
            @Override
            public void beforeInvoke(final UserDao targetDao, final Object[] args, final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
                N.println("innerHandler_1.beforeInvoke: method: " + methodSignature);
            }

            @Override
            public void afterInvoke(final Object result, final UserDao targetDao, final Object[] args,
                    Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
                N.println("innerHandler_1.afterInvoke: method: result" + result);
            }
        };
    }
}