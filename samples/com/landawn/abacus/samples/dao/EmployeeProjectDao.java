package com.landawn.abacus.samples.dao;

import com.landawn.abacus.EntityId;
import com.landawn.abacus.samples.entity.EmployeeProject;
import com.landawn.abacus.util.JdbcUtil;
import com.landawn.abacus.util.SQLBuilder;
 
public interface EmployeeProjectDao extends JdbcUtil.CrudDao<EmployeeProject, EntityId, SQLBuilder.PSC, EmployeeProjectDao> {
}