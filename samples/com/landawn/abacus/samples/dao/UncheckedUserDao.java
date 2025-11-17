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

import java.sql.SQLException;
import java.util.List;

import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.OP;
import com.landawn.abacus.jdbc.Propagation;
import com.landawn.abacus.jdbc.annotation.Bind;
import com.landawn.abacus.jdbc.annotation.SqlFragment;
import com.landawn.abacus.jdbc.annotation.SqlFragmentList;
import com.landawn.abacus.jdbc.annotation.Handler;
import com.landawn.abacus.jdbc.annotation.PerfLog;
import com.landawn.abacus.jdbc.annotation.Query;
import com.landawn.abacus.jdbc.annotation.SqlLogEnabled;
import com.landawn.abacus.jdbc.annotation.Transactional;
import com.landawn.abacus.jdbc.dao.UncheckedCrudDao;
import com.landawn.abacus.jdbc.dao.UncheckedJoinEntityHelper;
import com.landawn.abacus.query.SQLBuilder;
import com.landawn.abacus.samples.entity.User;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.stream.Stream;

@PerfLog(minExecutionTimeForSql = 101, minExecutionTimeForOperation = 100)
public interface UncheckedUserDao
        extends UncheckedCrudDao<User, Long, SQLBuilder.PSC, UncheckedUserDao>, UncheckedJoinEntityHelper<User, SQLBuilder.PSC, UncheckedUserDao> {

    @Query("INSERT INTO user1 (id, first_name, last_name, email) VALUES (:id, :firstName, :lastName, :email)")
    void insertWithId(User user);

    @Query("UPDATE user1 SET first_name = :firstName, last_name = :lastName WHERE id = :id")
    int updateFirstAndLastName(@Bind("firstName") String newFirstName, @Bind("lastName") String newLastName, @Bind("id") long id);

    @SqlLogEnabled
    @Query("SELECT first_name, last_name FROM user1 WHERE id = :id")
    User getFirstAndLastNameBy(@Bind("id") long id);

    @SqlLogEnabled(false)
    @Query("SELECT id, first_name, last_name, email FROM user1")
    Stream<User> allUsers();

    @Query(value = "INSERT INTO user1 (id, first_name, last_name, email) VALUES (:id, :firstName, :lastName, :email)", isBatch = true)
    List<Long> batchInsertWithId(List<User> users);

    @Query(value = "INSERT INTO user1 (first_name, last_name, email) VALUES (:firstName, :lastName, :email)", isBatch = true, batchSize = 123)
    List<Long> batchInsertWithoutId(List<User> users);

    @Query(value = "UPDATE user1 SET first_name = :firstName, last_name = :lastName WHERE id = :id", isBatch = true)
    int batchUpdate(List<User> users);

    @Query(value = "DELETE FROM user1 where id = :id", isBatch = true)
    int batchDelete(List<User> users);

    @Query(value = "DELETE FROM user1 where id = :id", isBatch = true, batchSize = 10000)
    int batchDeleteByIds(List<Long> userIds);

    @Query(value = "DELETE FROM user1 where id = ?", isBatch = true, batchSize = 10000)
    int batchDeleteByIds_1(List<Long> userIds);

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
    @Query("DELETE from user1 where id = ?")
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
    @Query("DELETE from user1 where id = ?")
    default boolean delete_propagation_REQUIRES_NEW(final long id, final String... sqls) {
        try {
            return prepareQuery(sqls[0]).setLong(1, id).update() > 0;
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Query("DELETE FROM {tableName} where id = :id")
    int deleteByIdWithSqlFragment(@SqlFragment("tableName") String tableName, @Bind("id") long id);

    @Query(value = "DELETE FROM {tableName} where id = :id", isBatch = true, batchSize = 10000)
    int deleteByIdsWithSqlFragment(@SqlFragment("tableName") String tableName, List<Long> userIds);

    @Query("SELECT * FROM {tableName} where id = :id ORDER BY {{orderBy}}")
    User selectByIdWithSqlFragment(@SqlFragment("tableName") String tableName, @SqlFragmentList("{{orderBy}}") List<String> orderByFields, @Bind("id") long id);

    @Query("SELECT * FROM {tableName} where id = :id ORDER BY {{orderBy}}")
    User selectByIdWithSqlFragment2(@SqlFragment("tableName") String tableName, @SqlFragmentList("{{orderBy}}") String[] orderByFields, @Bind("id") long id);

    @Query("SELECT * FROM {tableName} where id >= ? ORDER BY {whatever -> orderBy{{P}}")
    List<User> selectByIdWithSqlFragment_2(@SqlFragment("tableName") String tableName, @SqlFragment("{whatever -> orderBy{{P}}") String orderBy, long id);

    @Query("SELECT * FROM {tableName} where id >= ? AND first_name != ? ORDER BY {whatever -> orderBy{{P}} LIMIT {count}")
    List<User> selectByIdWithSqlFragment_3(@SqlFragment("tableName") String tableName, long id, @SqlFragment("{whatever -> orderBy{{P}}") String orderBy,
            @SqlFragment("{count}") long count, String firstName);

    @Query("SELECT * FROM {tableName} where id >= :id AND first_name != :firstName ORDER BY {whatever -> orderBy{{P}} LIMIT {count}")
    List<User> selectByIdWithSqlFragment_4(@SqlFragment("tableName") String tableName, @Bind("id") long id, @SqlFragment("{whatever -> orderBy{{P}}") String orderBy,
            @SqlFragment("{count}") long count, @Bind("firstName") String firstName);

    @Query("SELECT * FROM {tableName} where id = :id ORDER BY {{orderBy}}")
    boolean exists(@SqlFragment("tableName") String tableName, @SqlFragment("{{orderBy}}") String orderBy, @Bind("id") long id);

    @Query(value = "SELECT * FROM {tableName} where id = :id ORDER BY {{orderBy}}", op = OP.exists)
    boolean isThere(@SqlFragment("tableName") String tableName, @SqlFragment("{{orderBy}}") String orderBy, @Bind("id") long id) throws SQLException;

}
