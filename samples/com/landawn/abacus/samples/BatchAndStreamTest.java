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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.jdbc.DataTransferUtil;
import com.landawn.abacus.jdbc.Jdbc.ResultExtractor;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.query.Filters;
import com.landawn.abacus.samples.entity.User;
import com.landawn.abacus.util.stream.Stream;

/** Small batches and deterministic ordering make the expected results easy to follow. */
public class BatchAndStreamTest extends SampleTestSupport {
    @Test
    public void insertABatchAndReturnGeneratedKeys() throws SQLException {
        final List<User> users = List.of(User.builder().firstName("Ada").lastName("Lovelace").build(),
                User.builder().firstName("Grace").lastName("Hopper").build(), User.builder().firstName("Katherine").lastName("Johnson").build());

        // Request generated keys when preparing the INSERT. No explicit id is supplied.
        try (var insert = JdbcUtil.prepareNamedQuery(dataSource, "INSERT INTO user1 (first_name, last_name) VALUES (:firstName, :lastName)", true)) {
            final List<Long> ids = insert.addBatchParameters(users).batchInsert();
            assertEquals(3, ids.size());
            assertTrue(ids.stream().allMatch(id -> id > 0));
            assertEquals(3, userDao.batchGet(ids).size());
        }
    }

    @Test
    public void closeAStreamAfterReadingOnlyTheFirstRow() throws SQLException {
        insertThreeUsers();

        // A lazy stream holds JDBC resources. Close it even if traversal stops early.
        try (var query = JdbcUtil.prepareQuery(dataSource, "SELECT * FROM user1 ORDER BY id");
             Stream<User> users = query.stream(User.class)) {
            assertEquals("Ada", users.findFirst().get().getFirstName());
        }
        assertEquals(3, userDao.count(Filters.alwaysTrue()));
    }

    @Test
    public void readPagesUsingTheLastSeenId() throws SQLException {
        insertThreeUsers();

        // Keyset pagination needs a stable, unique ordering key and a matching predicate.
        try (Stream<List<User>> pages = JdbcUtil.queryByPage(dataSource, "SELECT * FROM user1 WHERE id > ? ORDER BY id LIMIT 2", 2,
                (statement, previousPage) -> statement.setLong(1, previousPage == null ? 0 : previousPage.get(previousPage.size() - 1).getId()),
                ResultExtractor.toList(User.class))) {
            final List<List<User>> result = pages.toList();
            assertEquals(List.of(2, 1), result.stream().map(List::size).toList());
            assertEquals(List.of("Ada", "Grace", "Katherine"), result.stream().flatMap(List::stream).map(User::getFirstName).toList());
        }
    }

    @Test
    public void copyRowsToAnotherTable() throws SQLException {
        insertThreeUsers();
        DataTransferUtil.copy(dataSource, dataSource, "user1", "user2");

        try (var query = JdbcUtil.prepareQuery(dataSource, "SELECT first_name FROM user2 ORDER BY id")) {
            assertEquals(List.of("Ada", "Grace", "Katherine"), query.list(String.class));
        }
    }

    private void insertThreeUsers() throws SQLException {
        final List<User> users = List.of(User.builder().id(10).firstName("Ada").lastName("Lovelace").build(),
                User.builder().id(20).firstName("Grace").lastName("Hopper").build(), User.builder().id(30).firstName("Katherine").lastName("Johnson").build());
        try (var insert = JdbcUtil.prepareNamedQuery(dataSource, "INSERT INTO user1 (id, first_name, last_name) VALUES (:id, :firstName, :lastName)")) {
            assertEquals(3, insert.addBatchParameters(users).batchUpdate().length);
        }
    }
}
