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
    
    /**
     * 
     *
     * @param user 
     */
    @Insert("INSERT INTO user1 (id, first_name, last_name, email) VALUES (:id, :firstName, :lastName, :email)")
    void insertWithId(User user);

    /**
     * 
     *
     * @param newFirstName 
     * @param newLastName 
     * @param id 
     * @return 
     */
    @Update("UPDATE user1 SET first_name = :firstName, last_name = :lastName WHERE id = :id")
    int updateFirstAndLastName(@Bind("firstName") String newFirstName, @Bind("lastName") String newLastName, @Bind("id") long id);

    /**
     * 
     *
     * @param id 
     * @return 
     */
    @SqlLogEnabled
    @Select("SELECT first_name, last_name FROM user1 WHERE id = :id")
    User getFirstAndLastNameBy(@Bind("id") long id);

    /**
     * 
     *
     * @return 
     */
    @SqlLogEnabled(false)
    @Select("SELECT id, first_name, last_name, email FROM user1")
    Stream<User> allUsers();

    /**
     * 
     *
     * @param users 
     * @return 
     */
    @Insert(sql = "INSERT INTO user1 (id, first_name, last_name, email) VALUES (:id, :firstName, :lastName, :email)", isBatch = true)
    List<Long> batchInsertWithId(List<User> users);

    /**
     * 
     *
     * @param users 
     * @return 
     */
    @Insert(sql = "INSERT INTO user1 (first_name, last_name, email) VALUES (:firstName, :lastName, :email)", isBatch = true, batchSize = 123)
    List<Long> batchInsertWithoutId(List<User> users);

    /**
     * 
     *
     * @param users 
     * @return 
     */
    @Update(sql = "UPDATE user1 SET first_name = :firstName, last_name = :lastName WHERE id = :id", isBatch = true)
    int batchUpdate(List<User> users);

    /**
     * 
     *
     * @param users 
     * @return 
     */
    @Delete(sql = "DELETE FROM user1 where id = :id", isBatch = true)
    int batchDelete(List<User> users);

    /**
     * 
     *
     * @param userIds 
     * @return 
     */
    @Delete(sql = "DELETE FROM user1 where id = :id", isBatch = true, batchSize = 10000)
    int batchDeleteByIds(List<Long> userIds);

    /**
     * 
     *
     * @param userIds 
     * @return 
     */
    @Delete(sql = "DELETE FROM user1 where id = ?", isBatch = true, batchSize = 10000)
    int batchDeleteByIds_1(List<Long> userIds);

    /**
     * 
     *
     * @param userIds 
     * @return 
     * @throws SQLException 
     */
    default int[] batchDeleteByIds_2(List<Long> userIds) throws SQLException {
        return prepareNamedQuery("DELETE FROM user1 where id = :id").addBatchParameters(userIds, long.class).batchUpdate();
    }

    /**
     * 
     *
     * @param id 
     * @param sqls 
     * @return 
     */
    @Sqls({ "SELECT * FROM user1 where id >= :id", "SELECT * FROM user1 where id >= :id" })
    default List<User> listUserByAnnoSql(long id, String... sqls) {
        try {
            return prepareNamedQuery(sqls[0]).setLong(1, id).list(User.class);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     * 
     *
     * @param firstName 
     * @param id 
     * @param sqls 
     * @return 
     */
    @Transactional
    @Sqls({ "update user1 set first_name = ? where id = -1", "SELECT * FROM user1 where id >= :id" })
    default List<User> listUserByAnnoSql2(String firstName, long id, String... sqls) {
        try {
            prepareQuery(sqls[0]).setString(1, firstName).update();
            return prepareNamedQuery(sqls[1]).setLong(1, id).list(User.class);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     * 
     *
     * @param id 
     * @param sqls 
     * @return 
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    @Sqls("DELETE from user1 where id = ?")
    default boolean delete_propagation_SUPPORTS(long id, String... sqls) {
        N.sleep(1001);

        try {
            return prepareQuery(sqls[0]).setLong(1, id).update() > 0;
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     * 
     *
     * @param id 
     * @param sqls 
     * @return 
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Handler(qualifier = "handler2")
    @Sqls("DELETE from user1 where id = ?")
    default boolean delete_propagation_REQUIRES_NEW(long id, String... sqls) {
        try {
            return prepareQuery(sqls[0]).setLong(1, id).update() > 0;
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     * 
     *
     * @param tableName 
     * @param id 
     * @return 
     */
    @Delete(sql = "DELETE FROM {tableName} where id = :id")
    int deleteByIdWithDefine(@Define("tableName") String tableName, @Bind("id") long id);

    /**
     * 
     *
     * @param tableName 
     * @param userIds 
     * @return 
     */
    @Delete(sql = "DELETE FROM {tableName} where id = :id", isBatch = true, batchSize = 10000)
    int deleteByIdsWithDefine(@Define("tableName") String tableName, List<Long> userIds);

    /**
     * 
     *
     * @param tableName 
     * @param orderByFields 
     * @param id 
     * @return 
     */
    @Select(sql = "SELECT * FROM {tableName} where id = :id ORDER BY {{orderBy}}")
    User selectByIdWithDefine(@Define("tableName") String tableName, @DefineList("{{orderBy}}") List<String> orderByFields, @Bind("id") long id);

    /**
     * 
     *
     * @param tableName 
     * @param orderByFields 
     * @param id 
     * @return 
     */
    @Select(sql = "SELECT * FROM {tableName} where id = :id ORDER BY {{orderBy}}")
    User selectByIdWithDefine2(@Define("tableName") String tableName, @DefineList("{{orderBy}}") String[] orderByFields, @Bind("id") long id);

    /**
     * 
     *
     * @param tableName 
     * @param orderBy 
     * @param id 
     * @return 
     */
    @Select(sql = "SELECT * FROM {tableName} where id >= ? ORDER BY {whatever -> orderBy{{P}}")
    List<User> selectByIdWithDefine_2(@Define("tableName") String tableName, @Define("{whatever -> orderBy{{P}}") String orderBy, long id);

    /**
     * 
     *
     * @param tableName 
     * @param id 
     * @param orderBy 
     * @param count 
     * @param firstName 
     * @return 
     */
    @Select(sql = "SELECT * FROM {tableName} where id >= ? AND first_name != ? ORDER BY {whatever -> orderBy{{P}} LIMIT {count}")
    List<User> selectByIdWithDefine_3(@Define("tableName") String tableName, long id, @Define("{whatever -> orderBy{{P}}") String orderBy,
            @Define("{count}") long count, String firstName);

    /**
     * 
     *
     * @param tableName 
     * @param id 
     * @param orderBy 
     * @param count 
     * @param firstName 
     * @return 
     */
    @Select(sql = "SELECT * FROM {tableName} where id >= :id AND first_name != :firstName ORDER BY {whatever -> orderBy{{P}} LIMIT {count}")
    List<User> selectByIdWithDefine_4(@Define("tableName") String tableName, @Bind("id") long id, @Define("{whatever -> orderBy{{P}}") String orderBy,
            @Define("{count}") long count, @Bind("firstName") String firstName);

    /**
     * 
     *
     * @param tableName 
     * @param orderBy 
     * @param id 
     * @return 
     */
    @Select(sql = "SELECT * FROM {tableName} where id = :id ORDER BY {{orderBy}}")
    boolean exists(@Define("tableName") String tableName, @Define("{{orderBy}}") String orderBy, @Bind("id") long id);

    /**
     * 
     *
     * @param tableName 
     * @param orderBy 
     * @param id 
     * @return 
     * @throws SQLException 
     */
    @Select(sql = "SELECT * FROM {tableName} where id = :id ORDER BY {{orderBy}}", op = OP.exists)
    boolean isThere(@Define("tableName") String tableName, @Define("{{orderBy}}") String orderBy, @Bind("id") long id) throws SQLException;

}