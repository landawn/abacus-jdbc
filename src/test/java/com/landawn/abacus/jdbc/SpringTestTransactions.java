package com.landawn.abacus.jdbc;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Default registration for integration fixtures; missing/misordered tracker tests construct their managers explicitly. */
final class SpringTestTransactions {
    private SpringTestTransactions() {
    }

    static DataSourceTransactionManager manager(final DataSource dataSource) {
        final DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        SpringTransactionTracker.register(manager);
        return manager;
    }
}
