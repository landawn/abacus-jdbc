package com.landawn.abacus.samples.dao;

import com.landawn.abacus.samples.entity.User;
import com.landawn.abacus.util.JdbcUtil;
import com.landawn.abacus.util.JdbcUtil.Dao.PerfLog;
import com.landawn.abacus.util.SQLBuilder;

@PerfLog(minExecutionTimeForSql = 101, minExecutionTimeForOperation = 100)
public interface UncheckedUserDaoL extends JdbcUtil.UncheckedCrudDaoL<User, SQLBuilder.PSC, UncheckedUserDaoL>,
        JdbcUtil.UnckeckedReadOnlyCrudJoinEntityHelper<User, Long, SQLBuilder.PSC, UncheckedUserDaoL> {
}