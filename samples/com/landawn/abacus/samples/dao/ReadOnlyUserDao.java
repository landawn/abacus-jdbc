package com.landawn.abacus.samples.dao;

import com.landawn.abacus.dao.ReadOnlyCrudDao;
import com.landawn.abacus.dao.annotation.Cache;
import com.landawn.abacus.dao.annotation.CacheResult;
import com.landawn.abacus.dao.annotation.RefreshCache;
import com.landawn.abacus.samples.entity.User;
import com.landawn.abacus.util.SQLBuilder;

@CacheResult(transfer = "none")
@Cache(capacity = 1000, evictDelay = 6000)
@RefreshCache
public interface ReadOnlyUserDao extends ReadOnlyCrudDao<User, Long, SQLBuilder.PSC, ReadOnlyUserDao> {
}