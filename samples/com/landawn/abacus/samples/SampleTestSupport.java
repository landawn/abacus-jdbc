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

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import com.landawn.abacus.jdbc.Jdbc.HandlerFactory;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.samples.dao.AddressDao;
import com.landawn.abacus.samples.dao.DeviceDao;
import com.landawn.abacus.samples.dao.EmployeeDao;
import com.landawn.abacus.samples.dao.EmployeeProjectDao;
import com.landawn.abacus.samples.dao.EmployeeProjectDao2;
import com.landawn.abacus.samples.dao.MyUserDaoA;
import com.landawn.abacus.samples.dao.NonUpdateUserDao;
import com.landawn.abacus.samples.dao.ProjectDao;
import com.landawn.abacus.samples.dao.ReadOnlyUserDao;
import com.landawn.abacus.samples.dao.UncheckedUserDao;
import com.landawn.abacus.samples.dao.UserDao;
import com.landawn.abacus.util.N;

/** Fresh schema and DAOs for each test, so examples can run individually or in any order. */
abstract class SampleTestSupport {
    static {
        HandlerFactory.register("handler1", HandlerFactory.create((obj, args, signature) -> N.println(">>>handler1.beforeInvoke: " + signature._1.getName()),
                (result, obj, args, signature) -> N.println("<<<handler1.afterInvoke: " + signature._1.getName())));
        HandlerFactory.register("handler2", HandlerFactory.create((obj, args, signature) -> N.println(">>>handler2.beforeInvoke: " + signature._1.getName()),
                (result, obj, args, signature) -> N.println("<<<handler2.afterInvoke: " + signature._1.getName())));
        JdbcUtil.setIdExtractorForDao(EmployeeDao.class, rs -> rs.getInt(1));
    }

    private SampleDatabase database;
    DataSource dataSource;
    UserDao userDao;
    MyUserDaoA myUserDaoA;
    UncheckedUserDao uncheckedUserDao;
    NonUpdateUserDao noUpdateUserDao;
    ReadOnlyUserDao readOnlyUserDao;
    EmployeeDao employeeDao;
    ProjectDao projectDao;
    EmployeeProjectDao employeeProjectDao;
    EmployeeProjectDao2 employeeProjectDao2;
    DeviceDao deviceDao;
    AddressDao addressDao;

    @BeforeEach
    void openDatabase() throws Exception {
        database = new SampleDatabase();
        dataSource = database.dataSource();
        userDao = JdbcUtil.createDao(UserDao.class, dataSource);
        myUserDaoA = JdbcUtil.createDao(MyUserDaoA.class, dataSource);
        uncheckedUserDao = JdbcUtil.createDao(UncheckedUserDao.class, dataSource);
        noUpdateUserDao = JdbcUtil.createDao(NonUpdateUserDao.class, dataSource);
        readOnlyUserDao = JdbcUtil.createDao(ReadOnlyUserDao.class, dataSource);
        employeeDao = JdbcUtil.createDao(EmployeeDao.class, dataSource);
        projectDao = JdbcUtil.createDao(ProjectDao.class, dataSource);
        employeeProjectDao = JdbcUtil.createDao(EmployeeProjectDao.class, dataSource);
        employeeProjectDao2 = JdbcUtil.createDao(EmployeeProjectDao2.class, dataSource);
        deviceDao = JdbcUtil.createDao(DeviceDao.class, dataSource);
        addressDao = JdbcUtil.createDao(AddressDao.class, dataSource);
    }

    @AfterEach
    void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }
}
