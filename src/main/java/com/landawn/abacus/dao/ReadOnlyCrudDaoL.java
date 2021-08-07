package com.landawn.abacus.dao;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.util.SQLBuilder;

@Beta
public interface ReadOnlyCrudDaoL<T, SB extends SQLBuilder, TD extends ReadOnlyCrudDaoL<T, SB, TD>>
        extends ReadOnlyCrudDao<T, Long, SB, TD>, NoUpdateCrudDaoL<T, SB, TD> {
}