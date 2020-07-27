package com.landawn.abacus.samples.dao;

import com.landawn.abacus.samples.entity.Employee;
import com.landawn.abacus.util.JdbcUtil;
import com.landawn.abacus.util.JdbcUtil.Dao.SqlLogEnabled;
import com.landawn.abacus.util.SQLBuilder;

@SqlLogEnabled
public interface EmployeeDao
        extends JdbcUtil.CrudDao<Employee, Integer, SQLBuilder.PSC, EmployeeDao>, JdbcUtil.JoinEntityHelper<Employee, SQLBuilder.PSC, EmployeeDao> {
}