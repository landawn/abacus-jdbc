package com.landawn.abacus.samples.dao;

import com.landawn.abacus.samples.entity.Device;
import com.landawn.abacus.util.JdbcUtil;
import com.landawn.abacus.util.SQLBuilder;

public interface DeviceDao extends JdbcUtil.CrudDao<Device, Integer, SQLBuilder.PSC, DeviceDao> {
}