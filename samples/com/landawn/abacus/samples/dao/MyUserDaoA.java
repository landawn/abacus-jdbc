package com.landawn.abacus.samples.dao;

import com.landawn.abacus.samples.dao.handler.UserDaoHandlerA;
import com.landawn.abacus.util.JdbcUtil.Dao;
import com.landawn.abacus.util.JdbcUtil.Dao.Handler;
import com.landawn.abacus.util.JdbcUtil.Dao.PerfLog;

@PerfLog(minExecutionTimeForSql = 101, minExecutionTimeForOperation = 100)
@Handler(type = UserDaoHandlerA.class)
@Handler(qualifier = "handler1", filter = ".*")
@Handler(qualifier = "handler2", filter = ".*", isForInvokeFromOutsideOfDaoOnly = true)
@Dao.Config(addLimitForSingleQuery = true, callGenerateIdForInsertIfIdNotSet = false)
public interface MyUserDaoA extends UserDao {
}