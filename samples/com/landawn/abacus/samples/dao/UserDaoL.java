package com.landawn.abacus.samples.dao;

import com.landawn.abacus.jdbc.annotation.PerfLog;
import com.landawn.abacus.jdbc.dao.CrudDaoL;
import com.landawn.abacus.jdbc.dao.JoinEntityHelper;
import com.landawn.abacus.samples.entity.User;
import com.landawn.abacus.util.SQLBuilder;

@PerfLog(minExecutionTimeForSql = 101, minExecutionTimeForOperation = 100)
public interface UserDaoL extends CrudDaoL<User, SQLBuilder.PSC, UserDaoL>, JoinEntityHelper<User, SQLBuilder.PSC, UserDaoL> {
}