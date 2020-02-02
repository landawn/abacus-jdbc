package com.landawn.abacus.samples.dao;

import com.landawn.abacus.samples.entity.Employee;
import com.landawn.abacus.util.JdbcUtil;
import com.landawn.abacus.util.SQLBuilder;
 
public interface EmployeeDao extends JdbcUtil.CrudDao<Employee, Integer, SQLBuilder.PSC, EmployeeDao> {
}