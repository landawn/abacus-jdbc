package com.landawn.abacus.samples.dao;

import com.landawn.abacus.samples.entity.EmployeeProject;
import com.landawn.abacus.util.JdbcUtil;
import com.landawn.abacus.util.SQLBuilder;

public interface EmployeeProjectDao2 extends JdbcUtil.CrudDao<EmployeeProject, EmployeeProject, SQLBuilder.PSC, EmployeeProjectDao2> {
}