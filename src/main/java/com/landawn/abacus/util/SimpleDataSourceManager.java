package com.landawn.abacus.util;

import java.util.Map;

import com.landawn.abacus.DataSource;
import com.landawn.abacus.DataSourceManager;
import com.landawn.abacus.DataSourceSelector;
import com.landawn.abacus.dataSource.SimpleSourceSelector;

class SimpleDataSourceManager implements DataSourceManager {

    private final DataSource primaryDataSource;

    private final Map<String, DataSource> activeDataSources;

    private final Properties<String, String> props = new Properties<>();

    private final DataSourceSelector dataSourceSelector = new SimpleSourceSelector();

    private boolean isClosed = false;

    public SimpleDataSourceManager(final DataSource ds) {
        this.primaryDataSource = ds;

        if (N.isNullOrEmpty(ds.getName())) {
            this.activeDataSources = N.asMap(SimpleDataSource.PRIMARY, ds);
        } else {
            this.activeDataSources = N.asMap(ds.getName(), ds);
        }
    }

    /**
     * Gets the primary data source.
     *
     * @return
     */
    @Override
    public DataSource getPrimaryDataSource() {
        return primaryDataSource;
    }

    /**
     * Gets the active data source.
     *
     * @param dataSourceName
     * @return
     */
    @Override
    public DataSource getActiveDataSource(String dataSourceName) {
        return activeDataSources.get(dataSourceName);
    }

    /**
     * Gets the active data sources.
     *
     * @return
     */
    @Override
    public Map<String, DataSource> getActiveDataSources() {
        return activeDataSources;
    }

    /**
     * Gets the data source selector.
     *
     * @return
     */
    @Override
    public DataSourceSelector getDataSourceSelector() {
        return dataSourceSelector;
    }

    /**
     * Gets the properties.
     *
     * @return
     */
    @Override
    public Properties<String, String> getProperties() {
        return props;
    }

    /**
     * Close.
     */
    @Override
    public void close() {
        if (isClosed) {
            return;
        }

        primaryDataSource.close();

        isClosed = true;
    }

    /**
     * Checks if is closed.
     *
     * @return true, if is closed
     */
    @Override
    public boolean isClosed() {
        return isClosed;
    }
}