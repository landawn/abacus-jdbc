/*
 * Copyright (C) 2024 HaiYang Li
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.landawn.abacus.samples.dao;

import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.Jdbc;
import com.landawn.abacus.jdbc.OP;
import com.landawn.abacus.jdbc.Propagation;
import com.landawn.abacus.jdbc.annotation.Bind;
import com.landawn.abacus.jdbc.annotation.BindList;
import com.landawn.abacus.jdbc.annotation.Config;
import com.landawn.abacus.jdbc.annotation.Define;
import com.landawn.abacus.jdbc.annotation.Handler;
import com.landawn.abacus.jdbc.annotation.MappedByKey;
import com.landawn.abacus.jdbc.annotation.MergedById;
import com.landawn.abacus.jdbc.annotation.NonDBOperation;
import com.landawn.abacus.jdbc.annotation.PerfLog;
import com.landawn.abacus.jdbc.annotation.PrefixFieldMapping;
import com.landawn.abacus.jdbc.annotation.Query;
import com.landawn.abacus.jdbc.annotation.SqlField;
import com.landawn.abacus.jdbc.annotation.SqlLogEnabled;
import com.landawn.abacus.jdbc.annotation.SqlMapper;
import com.landawn.abacus.jdbc.annotation.Transactional;
import com.landawn.abacus.jdbc.dao.CrudDao;
import com.landawn.abacus.jdbc.dao.JoinEntityHelper;
import com.landawn.abacus.query.SQLBuilder;
import com.landawn.abacus.query.SQLBuilder.PSC;
import com.landawn.abacus.query.condition.ConditionFactory.CF;
import com.landawn.abacus.samples.dao.handler.UserDaoHandlerA;
import com.landawn.abacus.samples.entity.User;
import com.landawn.abacus.util.ImmutableList;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Tuple.Tuple3;
import com.landawn.abacus.util.u.Optional;
import com.landawn.abacus.util.stream.Stream;

@PerfLog(minExecutionTimeForSql = 101, minExecutionTimeForOperation = 100)
@Handler(type = UserDaoHandlerA.class)
@Handler(qualifier = "handler1", filter = ".*")
@Handler(qualifier = "handler2", filter = ".*", isForInvokeFromOutsideOfDaoOnly = true)
@Config(addLimitForSingleQuery = true, callGenerateIdForInsertIfIdNotSet = false)
@SqlMapper("./samples/userSqlMapper.xml")
// @SqlLogEnabled(true)
public interface UserDao extends CrudDao<User, Long, SQLBuilder.PSC, UserDao>, JoinEntityHelper<User, SQLBuilder.PSC, UserDao> {

    @NonDBOperation
    @Override
    default Long generateId() {
        return System.currentTimeMillis();
    }

    @Query(value = "INSERT INTO `user1` (id, first_name, last_name, email, create_time) VALUES (:id, :firstName, :lastName, :email, :sysTime)", autoSetSysTimeParam = true)
    void insertWithId(User user) throws SQLException;

    @Query("UPDATE `user1` SET first_name = :firstName, last_name = :lastName WHERE id = :id")
    int updateFirstAndLastName(@Bind("firstName") String newFirstName, @Bind("lastName") String newLastName, @Bind("id") long id) throws SQLException;

    @SqlLogEnabled
    @Query("SELECT first_name, last_name FROM `user1` WHERE id = :id")
    User getFirstAndLastNameBy(@Bind("id") long id) throws SQLException;

    @SqlLogEnabled(false)
    @Query("SELECT id, first_name, last_name, email FROM user1")
    Stream<User> allUsers();

    @Query(value = "INSERT INTO user1 (id, first_name, last_name, prop1, email) VALUES (:id, :firstName, :lastName, :nickName, :email)", isBatch = true)
    List<Long> batchInsertWithId(List<User> users) throws SQLException;

    @Query(value = "INSERT INTO user1 (first_name, last_name, email, create_time) VALUES (:firstName, :lastName, :email, :sysTime)", isBatch = true, batchSize = 123, autoSetSysTimeParam = true)
    List<Long> batchInsertWithoutId(List<User> users) throws SQLException;

    @Query(value = "UPDATE user1 SET first_name = :firstName, last_name = :lastName WHERE id = :id", isBatch = true)
    int batchUpdate(List<User> users) throws SQLException;

    @Query(value = "DELETE FROM user1 where id = :id", isBatch = true)
    int batchDelete(List<User> users) throws SQLException;

    @Query(value = "DELETE FROM user1 where id = :id", isBatch = true, batchSize = 10000)
    int batchDeleteByIds(List<Long> userIds) throws SQLException;

    @Query(value = "DELETE FROM user1 where id = ?", isBatch = true, batchSize = 10000)
    int batchDeleteByIds_1(List<Long> userIds) throws SQLException;

    default int[] batchDeleteByIds_2(final List<Long> userIds) throws SQLException {
        return prepareNamedQuery("DELETE FROM user1 where id = :id").addBatchParameters(userIds, long.class).batchUpdate();
    }

    @Query({ "SELECT * FROM user1 where id >= :id", "SELECT * FROM user1 where id >= :id" })
    default List<User> listUserByAnnoSql(final long id, final String... sqls) {
        try {
            return prepareNamedQuery(sqls[0]).setLong(1, id).list(User.class);
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Transactional
    @Query({ "update user1 set first_name = ? where id = -1", "SELECT * FROM user1 where id >= :id" })
    default List<User> listUserByAnnoSql2(final String firstName, final long id, final String... sqls) {
        try {
            prepareQuery(sqls[0]).setString(1, firstName).update();
            return prepareNamedQuery(sqls[1]).setLong(1, id).list(User.class);
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Transactional(propagation = Propagation.SUPPORTS)
    @Query("DELETE FROM user1 where id = ?")
    default boolean delete_propagation_SUPPORTS(final long id, final String... sqls) {
        N.sleep(1001);

        try {
            return prepareQuery(sqls[0]).setLong(1, id).update() > 0;
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Handler(qualifier = "handler2")
    @Query("DELETE FROM user1 where id = ?")
    default boolean delete_propagation_REQUIRES_NEW(final long id, final String... sqls) {
        try {
            return prepareQuery(sqls[0]).setLong(1, id).update() > 0;
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Query("DELETE FROM {tableName} where id = :id")
    int deleteByIdWithDefine(@Define("tableName") String tableName, @Bind("id") long id) throws SQLException;

    @Query(value = "DELETE FROM {tableName} where id = :id", isBatch = true, batchSize = 10000)
    int deleteByIdsWithDefine(@Define("tableName") String tableName, List<Long> userIds) throws SQLException;

    @Query("SELECT * FROM {tableName} where id = :id ORDER BY {{orderBy}}")
    User selectByIdWithDefine(@Define("tableName") String tableName, @Define("{{orderBy}}") String orderBy, @Bind("id") long id) throws SQLException;

    @Query("SELECT * FROM {tableName} where id >= ? ORDER BY {whatever -> orderBy{{P}}")
    List<User> selectByIdWithDefine_2(@Define("tableName") String tableName, @Define("{whatever -> orderBy{{P}}") String orderBy, long id) throws SQLException;

    @Query("SELECT * FROM {tableName} where id >= ? AND first_name != ? ORDER BY {whatever -> orderBy{{P}} LIMIT {count}")
    List<User> selectByIdWithDefine_3(@Define("tableName") String tableName, long id, @Define("{whatever -> orderBy{{P}}") String orderBy,
            @Define("{count}") long count, String firstName) throws SQLException;

    @Query("SELECT * FROM {tableName} where id >= :id AND first_name != :firstName ORDER BY {whatever -> orderBy{{P}} LIMIT {count}")
    List<User> selectByIdWithDefine_4(@Define("tableName") String tableName, @Bind("id") long id, @Define("{whatever -> orderBy{{P}}") String orderBy,
            @Define("{count}") long count, @Bind("firstName") String firstName) throws SQLException;

    @Query("SELECT * FROM {tableName} where id = :id ORDER BY {{orderBy}}")
    boolean exists(@Define("tableName") String tableName, @Define("{{orderBy}}") String orderBy, @Bind("id") long id) throws SQLException;

    @Query(value = "SELECT * FROM {tableName} where id = :id ORDER BY {{orderBy}}", op = OP.exists)
    boolean isThere(@Define("tableName") String tableName, @Define("{{orderBy}}") String orderBy, @Bind("id") long id) throws SQLException;

    @Query(id = "sql_listToSet")
    Set<User> listToSet(int id) throws SQLException;

    @Query("sql_listToSet")
    Set<User> listToSet2(int id) throws SQLException;

    @Query("select * FROM user1 where id > ?")
    Queue<User> listToCollection(int id) throws SQLException;

    @Query("select * FROM user1 where id > ?")
    @MappedByKey("id")
    Map<Long, User> selectIdBiggerThan(int id) throws SQLException;

    @Query("select user1.id as \"id\", first_name, last_name, device.id as \"devices.id\", device.manufacture as \"devices.manufacture\", device.model as \"devices.model\", device.user_id as \"devices.user_id\", address.id as \"address.id\", address.street as \"address.street\", address.city as \"address.city\" FROM user1 left join device on user1.id = device.user_id left join address on user1.id = address.user_id")
    @MergedById("id")
    Collection<User> listTomergedEntities() throws SQLException;

    @Query("select user1.id as \"id\", first_name, last_name, device.id as \"devices.id\", device.manufacture as \"devices.manufacture\", device.model as \"devices.model\", device.user_id as \"devices.user_id\", address.id as \"address.id\", address.street as \"address.street\", address.city as \"address.city\" FROM user1 left join device on user1.id = device.user_id left join address on user1.id = address.user_id")
    @MergedById("ID, firstName")
    Collection<User> listTomergedEntities_2() throws SQLException;

    @Query("select user1.id as \"id\", first_name, last_name, device.id as \"ds.id\", device.manufacture as \"ds.manufacture\", device.model as \"ds.model\", device.user_id as \"ds.user_id\", address.id as \"address.id\", address.street as \"address.street\", address.city as \"address.city\" FROM user1 left join device on user1.id = device.user_id left join address on user1.id = address.user_id")
    @MergedById("ID, firstName")
    @PrefixFieldMapping("ds = devices, as = address")
    Collection<User> listTomergedEntities_3() throws SQLException;

    @Query("select user1.id as \"id\", first_name, last_name, device.id as \"devices.id\", device.manufacture as \"devices.manufacture\", device.model as \"devices.model\", device.user_id as \"devices.user_id\", address.id as \"address.id\", address.street as \"address.street\", address.city as \"address.city\" FROM user1 left join device on user1.id = device.user_id left join address on user1.id = address.user_id")
    @MergedById("id")
    Optional<User> findOneTomergedEntities() throws SQLException;

    @Query(value = "select first_name FROM user1 where id >= ?", fetchSize = 100)
    Stream<String> streamOne(long id);

    @Query(value = "select first_name FROM user1 where id >= ?", fetchSize = 100)
    Stream<String> streamOne_2(long id);

    @Query(value = "select first_name FROM user1 where id >= ?", fetchSize = 100)
    Optional<String> getOne(long id) throws SQLException;

    //    @Query(value = "select first_name FROM user1 where id >= ?", fetchSize = 100)
    //    java.util.Optional<String> getOne_2(long id);

    @Query("select * FROM user1 where id > ?")
    @Handler(qualifier = "innerHandler_1")
    Queue<User> testInnerHandler(int id) throws SQLException;

    //    @Query("select * FROM user1 where id > ?")
    //    Queue<List<Object>> list(int id, RowFilter rowFilter, RowMapper<List<Object>> rowMapper) throws SQLException;

    @Query("select * FROM user1 where id in ({ids})")
    Queue<User> listByIds(@BindList("ids") int[] ids) throws SQLException;

    @Query("select * FROM user1 where first_name != ? and id in ({ids}) and last_name != ?")
    Queue<User> listByIds_01(String firstNameToExclude, @BindList("ids") long[] ids, String LastNameToExclude) throws SQLException;

    @Query("select * FROM user1 where id in ({ids}) and last_name != ?")
    Queue<User> listByIds_02(@BindList("ids") Object[] ids, String LastNameToExclude) throws SQLException;

    @Query("select * FROM user1 where id in ({ids}) and last_name not in ({lastNames})")
    Queue<User> listByIds_03(@BindList("ids") Collection<Long> ids, @BindList("lastNames") Collection<String> lastNamesToExclude) throws SQLException;

    @Query("select * FROM user1 where {id in (ids) and} last_name not in ({lastNames})")
    Queue<User> listByIds_04(@BindList(value = "{id in (ids) and}", prefixForNonEmpty = "id in (", suffixForNonEmpty = ") and") Collection<Long> ids,
            @BindList("lastNames") Collection<String> lastNamesToExclude) throws SQLException;

    static final class SqlTable {

        @SqlField
        static final String sql_listToSet = PSC.selectFrom(User.class).where(CF.gt("id")).sql() + ";";
    }

    static final class Handlers {
        static final Jdbc.Handler<UserDao> innerHandler_1 = new Jdbc.Handler<>() {
            @Override
            public void beforeInvoke(final UserDao targetDao, final Object[] args, final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
                N.println("innerHandler_1.beforeInvoke: method: " + methodSignature);
            }

            @Override
            public void afterInvoke(final Object result, final UserDao targetDao, final Object[] args,
                    final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
                N.println("innerHandler_1.afterInvoke: method: result" + result);
            }
        };
    }
}
