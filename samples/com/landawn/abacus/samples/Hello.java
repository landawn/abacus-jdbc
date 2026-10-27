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

import com.landawn.abacus.jdbc.JdbcUtil;

/** Smallest runnable example; run main in your IDE or run HelloTest through Maven. */
public class Hello {
    public static void main(String[] args) throws Exception {
        System.out.println(greeting());
    }

    static String greeting() throws Exception {
        try (SampleDatabase database = new SampleDatabase()) {
            // The helper creates schema.sql and owns the connection pool.
            final var dataSource = database.dataSource();
            try (var insert = JdbcUtil.prepareQuery(dataSource, "INSERT INTO user1 (first_name, last_name) VALUES (?, ?)")) {
                insert.setString(1, "Ada").setString(2, "Lovelace").update();
            }

            try (var query = JdbcUtil.prepareNamedQuery(dataSource, "SELECT first_name FROM user1 WHERE last_name = :lastName")) {
                final String firstName = query.setString("lastName", "Lovelace").queryForString().get();
                return "Hello, " + firstName + "!";
            }
        }
    }
}
