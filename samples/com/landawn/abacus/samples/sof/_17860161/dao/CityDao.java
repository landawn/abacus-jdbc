package com.landawn.abacus.samples.sof._17860161.dao;

import com.landawn.abacus.dao.CrudDao;
import com.landawn.abacus.samples.sof._17860161.entity.City;
import com.landawn.abacus.util.SQLBuilder;

public interface CityDao extends CrudDao<City, Integer, SQLBuilder.PSC, CityDao> {
}