package com.landawn.abacus.samples.dao;

import com.landawn.abacus.jdbc.annotation.SqlLogEnabled;
import com.landawn.abacus.jdbc.dao.CrudDao;
import com.landawn.abacus.jdbc.dao.JoinEntityHelper;
import com.landawn.abacus.samples.entity.Employee;
import com.landawn.abacus.util.SQLBuilder;

@SqlLogEnabled
public interface EmployeeDao extends CrudDao<Employee, Integer, SQLBuilder.PSC, EmployeeDao>, JoinEntityHelper<Employee, SQLBuilder.PSC, EmployeeDao> {
}