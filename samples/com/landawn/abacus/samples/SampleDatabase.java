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

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import org.h2.tools.RunScript;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** Owns the temporary database and connection pool used by one example. */
final class SampleDatabase implements AutoCloseable {
    private final HikariDataSource dataSource;

    SampleDatabase() throws SQLException, IOException {
        final HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:abacus_samples_" + UUID.randomUUID());
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(1);
        dataSource = new HikariDataSource(config);

        try (var schema = SampleDatabase.class.getResourceAsStream("/schema.sql")) {
            if (schema == null) {
                throw new IOException("schema.sql is missing from the sample classpath");
            }

            try (Connection connection = dataSource.getConnection();
                 var reader = new InputStreamReader(schema, StandardCharsets.UTF_8)) {
                RunScript.execute(connection, reader);
            }
        } catch (SQLException | IOException | RuntimeException e) {
            dataSource.close();
            throw e;
        }
    }

    HikariDataSource dataSource() {
        return dataSource;
    }

    @Override
    public void close() {
        // With no remaining connections, H2 also releases this in-memory database.
        dataSource.close();
    }
}
