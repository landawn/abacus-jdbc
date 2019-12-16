package com.landawn.abacus.samples.dao;

import java.sql.SQLException;
import java.util.List;

import com.landawn.abacus.samples.entity.User;
import com.landawn.abacus.util.JdbcUtil;
import com.landawn.abacus.util.SQLBuilder;
import com.landawn.abacus.util.stream.Stream;

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
    int batchDeleteByIds(List<Long> users) throws SQLException;

}