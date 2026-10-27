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

import static com.landawn.abacus.query.Dsl.NSC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.sql.SQLException;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.query.Filters;
import com.landawn.abacus.samples.entity.User;

/** Named parameters bind by name, independently of their order in a map or bean. */
public class NamedQueryTest extends SampleTestSupport {
    @Test
    public void bindAnEntityAndAMap() throws SQLException {
        final User user = User.builder().id(100).firstName("Ada").lastName("Lovelace").email("ada@example.com").build();
        try (var insert = JdbcUtil.prepareNamedQuery(dataSource,
                "INSERT INTO user1 (id, first_name, last_name, email) VALUES (:id, :firstName, :lastName, :email)")) {
            assertEquals(1, insert.setParameters(user).update());
        }

        try (var update = JdbcUtil.prepareNamedQuery(dataSource, "UPDATE user1 SET email = :email WHERE id = :id")) {
            assertEquals(1, update.setParameters(Map.of("id", 100L, "email", "ada.lovelace@example.com")).update());
        }
        assertEquals("ada.lovelace@example.com", userDao.getOrNull(100L).getEmail());
    }

    @Test
    public void generateSqlFromEntityPropertyNames() throws SQLException {
        userDao.insert(User.builder().firstName("Ada").lastName("Lovelace").nickName("Enchantress").build());

        // NSC maps camelCase Java properties to SQL columns, including @Column aliases.
        final String sql = NSC.selectFrom(User.class).where(Filters.eq("nickName")).build().query();
        try (var query = JdbcUtil.prepareNamedQuery(dataSource, sql)) {
            assertEquals("Ada", query.setString("nickName", "Enchantress").findOnlyOne(User.class).get().getFirstName());
        }
    }

    @Test
    public void bindRepeatedNamesAndHandleNoMatch() throws SQLException {
        userDao.insert(User.builder().firstName("Ada").lastName("Lovelace").build());
        final String sql = "SELECT * FROM user1 WHERE first_name = :name OR last_name = :name";

        // One binding supplies both occurrences of :name.
        try (var query = JdbcUtil.prepareNamedQuery(dataSource, sql)) {
            assertEquals(1, query.setString("name", "Ada").list(User.class).size());
        }
        try (var query = JdbcUtil.prepareNamedQuery(dataSource, sql)) {
            assertFalse(query.setString("name", "Grace").findOnlyOne(User.class).isPresent());
        }
    }
}
