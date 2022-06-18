package com.landawn.abacus.samples.dao;

import com.landawn.abacus.jdbc.annotation.PerfLog;
import com.landawn.abacus.jdbc.dao.UncheckedCrudDaoL;
import com.landawn.abacus.jdbc.dao.UncheckedReadOnlyCrudJoinEntityHelper;
import com.landawn.abacus.samples.entity.User;
import com.landawn.abacus.util.SQLBuilder;

@PerfLog(minExecutionTimeForSql = 101, minExecutionTimeForOperation = 100)
public interface UncheckedUserDaoL extends UncheckedCrudDaoL<User, SQLBuilder.PSC, UncheckedUserDaoL>,
        UncheckedReadOnlyCrudJoinEntityHelper<User, Long, SQLBuilder.PSC, UncheckedUserDaoL> {
}