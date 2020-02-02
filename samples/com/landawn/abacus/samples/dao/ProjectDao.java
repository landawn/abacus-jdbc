package com.landawn.abacus.samples.dao;

import com.landawn.abacus.samples.entity.Project;
import com.landawn.abacus.util.JdbcUtil;
import com.landawn.abacus.util.SQLBuilder;
 
public interface ProjectDao extends JdbcUtil.CrudDao<Project, Integer, SQLBuilder.PSC, ProjectDao> {
}