package com.landawn.abacus.samples.dao;

import com.landawn.abacus.dao.UncheckedCrudDaoL;
import com.landawn.abacus.dao.UnckeckedReadOnlyCrudJoinEntityHelper;
import com.landawn.abacus.dao.annotation.PerfLog;
import com.landawn.abacus.samples.entity.User;
import com.landawn.abacus.util.SQLBuilder;

@PerfLog(minExecutionTimeForSql = 101, minExecutionTimeForOperation = 100)
public interface UncheckedUserDaoL extends UncheckedCrudDaoL<User, SQLBuilder.PSC, UncheckedUserDaoL>,
        UnckeckedReadOnlyCrudJoinEntityHelper<User, Long, SQLBuilder.PSC, UncheckedUserDaoL> {
}