/*
 * Copyright (c) 2021, Haiyang Li.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.landawn.abacus.jdbc.dao;

import java.sql.SQLException;
import java.util.Map;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.util.SQLBuilder;

@Beta
public interface NoUpdateCrudDaoL<T, SB extends SQLBuilder, TD extends NoUpdateCrudDaoL<T, SB, TD>>
        extends NoUpdateCrudDao<T, Long, SB, TD>, CrudDaoL<T, SB, TD> {

    /**
     *
     * @param propName
     * @param propValue
     * @param id
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int update(final String propName, final Object propValue, final long id) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param updateProps
     * @param id
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int update(final Map<String, Object> updateProps, final long id) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
     * @param id
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteById(final long id) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }
}