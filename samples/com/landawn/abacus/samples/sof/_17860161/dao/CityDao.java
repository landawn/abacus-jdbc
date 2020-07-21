package com.landawn.abacus.samples.sof._17860161.dao;

import com.landawn.abacus.samples.sof._17860161.entity.City;
import com.landawn.abacus.util.JdbcUtil;
import com.landawn.abacus.util.SQLBuilder;

public interface CityDao extends JdbcUtil.CrudDao<City, Integer, SQLBuilder.PSC, CityDao> {
}