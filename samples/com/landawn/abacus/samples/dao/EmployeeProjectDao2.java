package com.landawn.abacus.samples.dao;

import com.landawn.abacus.dao.CrudDao;
import com.landawn.abacus.samples.entity.EmployeeProject;
import com.landawn.abacus.util.SQLBuilder;

public interface EmployeeProjectDao2 extends CrudDao<EmployeeProject, EmployeeProject, SQLBuilder.PSC, EmployeeProjectDao2> {
}