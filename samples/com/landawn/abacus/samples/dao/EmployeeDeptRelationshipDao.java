package com.landawn.abacus.samples.dao;

import com.landawn.abacus.EntityId;
import com.landawn.abacus.samples.entity.EmployeeDeptRelationship;
import com.landawn.abacus.util.JdbcUtil;
import com.landawn.abacus.util.SQLBuilder;

public interface EmployeeDeptRelationshipDao extends JdbcUtil.CrudDao<EmployeeDeptRelationship, EntityId, SQLBuilder.PSC, EmployeeDeptRelationshipDao> {
}