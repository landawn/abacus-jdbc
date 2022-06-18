package com.landawn.abacus.samples.dao;

import com.landawn.abacus.jdbc.dao.CrudDao;
import com.landawn.abacus.samples.entity.Device;
import com.landawn.abacus.util.SQLBuilder;

public interface DeviceDao extends CrudDao<Device, Integer, SQLBuilder.PSC, DeviceDao> {
}