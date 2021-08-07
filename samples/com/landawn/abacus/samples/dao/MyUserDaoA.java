package com.landawn.abacus.samples.dao;

import com.landawn.abacus.dao.annotation.Config;
import com.landawn.abacus.dao.annotation.Handler;
import com.landawn.abacus.dao.annotation.PerfLog;
import com.landawn.abacus.samples.dao.handler.UserDaoHandlerA;

@PerfLog(minExecutionTimeForSql = 101, minExecutionTimeForOperation = 100)
@Handler(type = UserDaoHandlerA.class)
@Handler(qualifier = "handler1", filter = ".*")
@Handler(qualifier = "handler2", filter = ".*", isForInvokeFromOutsideOfDaoOnly = true)
@Config(addLimitForSingleQuery = true, callGenerateIdForInsertIfIdNotSet = false)
public interface MyUserDaoA extends UserDao {
}