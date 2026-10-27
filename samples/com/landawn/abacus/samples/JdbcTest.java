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
package com.landawn.abacus.samples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.jdbc.IsolationLevel;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.SqlTransaction;
import com.landawn.abacus.jdbc.dao.CrudDao;
import com.landawn.abacus.samples.entity.User;

/** Start here after Hello: the same data access task at three levels of abstraction. */
public class JdbcTest extends SampleTestSupport {
    /** A minimal DAO inherits CRUD operations without an implementation class. */
    public interface BasicUserDao extends CrudDao<User, Long, BasicUserDao> {
    }

    @Test
    public void insertAndReadWithPlainJdbc() throws SQLException {
        // Plain JDBC owns each Connection, PreparedStatement, and ResultSet explicitly.
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement("INSERT INTO user1 (id, first_name, last_name, email) VALUES (?, ?, ?, ?)")) {
            insert.setLong(1, 100);
            insert.setString(2, "Ada");
            insert.setString(3, "Lovelace");
            insert.setString(4, "ada@example.com");
            assertEquals(1, insert.executeUpdate());
        }

        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement("SELECT first_name FROM user1 WHERE id = ?")) {
            select.setLong(1, 100);
            try (ResultSet rows = select.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("Ada", rows.getString("first_name"));
                assertFalse(rows.next());
            }
        }
    }

    @Test
    public void createReadUpdateDeleteWithPreparedQuery() throws SQLException {
        // Parameter positions start at 1. Values are bound separately from SQL.
        try (var insert = JdbcUtil.prepareQuery(dataSource, "INSERT INTO user1 (id, first_name, last_name, email) VALUES (?, ?, ?, ?)")) {
            assertEquals(1, insert.setLong(1, 100).setString(2, "Ada").setString(3, "Lovelace").setString(4, "ada@example.com").update());
        }

        try (var select = JdbcUtil.prepareQuery(dataSource, "SELECT * FROM user1 WHERE id = ?")) {
            final User user = select.setLong(1, 100).findOnlyOne(User.class).get();
            assertEquals("Ada", user.getFirstName());
            assertNotNull(user.getCreateTime()); // @ReadOnly: the database supplies this value.
        }

        try (var update = JdbcUtil.prepareQuery(dataSource, "UPDATE user1 SET email = ? WHERE id = ?")) {
            assertEquals(1, update.setString(1, "ada.lovelace@example.com").setLong(2, 100).update());
        }
        try (var select = JdbcUtil.prepareQuery(dataSource, "SELECT email FROM user1 WHERE id = ?")) {
            assertEquals("ada.lovelace@example.com", select.setLong(1, 100).queryForString().get());
        }
        try (var delete = JdbcUtil.prepareQuery(dataSource, "DELETE FROM user1 WHERE id = ?")) {
            assertEquals(1, delete.setLong(1, 100).update());
        }
        try (var select = JdbcUtil.prepareQuery(dataSource, "SELECT id FROM user1 WHERE id = ?")) {
            assertFalse(select.setLong(1, 100).exists());
        }
    }

    @Test
    public void createReadUpdateDeleteWithDao() throws SQLException {
        final BasicUserDao dao = JdbcUtil.createDao(BasicUserDao.class, dataSource);
        final User user = User.builder().firstName("Ada").lastName("Lovelace").email("ada@example.com").build();

        // Leave id unset for an identity column. insert returns the database-generated key.
        final long id = dao.insert(user);
        final User saved = dao.getOrNull(id);
        assertNotNull(saved);
        assertEquals("Ada", saved.getFirstName());

        saved.setEmail("ada.lovelace@example.com");
        assertEquals(1, dao.update(saved));
        assertEquals("ada.lovelace@example.com", dao.getOrNull(id).getEmail());
        assertEquals(1, dao.deleteById(id));
        assertFalse(dao.exists(id));
    }

    @Test
    public void selectWithExternalSqlMapper() throws SQLException {
        final long id = userDao.insert(User.builder().firstName("Ada").lastName("Lovelace").build());

        // UserDao's @Query id resolves to the statement in samples/userSqlMapper.xml.
        assertEquals("Ada", userDao.selectUserById(id).get().getFirstName());
        assertFalse(userDao.selectUserById(-1).isPresent());
    }

    @Test
    public void commitOrRollBackAUnitOfWork() throws SQLException {
        final BasicUserDao dao = JdbcUtil.createDao(BasicUserDao.class, dataSource);
        final long id = dao.insert(User.builder().firstName("Ada").lastName("Lovelace").email("ada@example.com").build());

        // Every operation must use the same DataSource on the transaction's thread.
        // Closing without commit rolls back, including when an operation throws.
        assertThrows(SQLException.class, () -> {
            try (SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.DEFAULT)) {
                try (var update = JdbcUtil.prepareQuery(dataSource, "UPDATE user1 SET email = ? WHERE id = ?")) {
                    update.setString(1, "temporary@example.com").setLong(2, id).update();
                }
                // first_name is NOT NULL: this deliberately fails after the email update.
                try (var invalidUpdate = JdbcUtil.prepareQuery(dataSource, "UPDATE user1 SET first_name = NULL WHERE id = ?")) {
                    invalidUpdate.setLong(1, id).update();
                }
                transaction.commit();
            }
        });
        assertEquals("ada@example.com", dao.getOrNull(id).getEmail());

        try (SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.DEFAULT)) {
            try (var update = JdbcUtil.prepareQuery(dataSource, "UPDATE user1 SET email = ? WHERE id = ?")) {
                assertEquals(1, update.setString(1, "committed@example.com").setLong(2, id).update());
            }
            transaction.commit();
        }
        assertEquals("committed@example.com", dao.getOrNull(id).getEmail());
    }

    @Test
    public void mapBuiltInDaoOperationsToAnotherTable() throws SQLException {
        final BasicUserDao archiveDao = JdbcUtil.createDao(BasicUserDao.class, dataSource,
                JdbcUtil.DaoCreationOptions.builder().targetTableName("user2").build());
        final long id = archiveDao.insert(User.builder().firstName("Ada").lastName("Lovelace").build());

        assertEquals("Ada", archiveDao.getOrNull(id).getFirstName());
        assertFalse(userDao.exists(id));
        // targetTableName changes generated CRUD SQL. Literal SQL in @Query stays as written.
    }
}
