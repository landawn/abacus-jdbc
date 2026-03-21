/*
 * Copyright (c) 2025, Haiyang Li.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.annotation.JoinedBy;
import com.landawn.abacus.jdbc.annotation.DaoConfig;
import com.landawn.abacus.jdbc.dao.Dao;
import com.landawn.abacus.query.SqlBuilder.PSC;

@Tag("2025")
public class JoinInfoTest extends TestBase {

    @DaoConfig(allowJoiningByNullOrDefaultValue = true)
    interface UserDao extends Dao<UserEntity, PSC, UserDao> {
    }

    public static final class UserEntity {
        private long userId;

        @JoinedBy("userId")
        private List<OrderEntity> orders;

        public long getUserId() {
            return userId;
        }

        public void setUserId(long userId) {
            this.userId = userId;
        }

        public List<OrderEntity> getOrders() {
            return orders;
        }

        public void setOrders(List<OrderEntity> orders) {
            this.orders = orders;
        }
    }

    public static final class OrderEntity {
        private long id;
        private long userId;

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public long getUserId() {
            return userId;
        }

        public void setUserId(long userId) {
            this.userId = userId;
        }
    }

    @Test
    public void testGetEntityJoinInfo() {
        Map<String, JoinInfo> joinInfoMap = JoinInfo.getEntityJoinInfo(UserDao.class, UserEntity.class, "user_entity");

        assertEquals(1, joinInfoMap.size());
        assertTrue(joinInfoMap.containsKey("orders"));
        assertTrue(joinInfoMap.get("orders").allowJoiningByNullOrDefaultValue);
    }

    @Test
    public void testGetPropJoinInfo() {
        JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserDao.class, UserEntity.class, "user_entity", "orders");

        assertEquals("orders", joinInfo.joinPropInfo.name);
        assertTrue(joinInfo.referencedEntityClass == OrderEntity.class);
    }

    @Test
    public void testGetJoinEntityPropNamesByType() {
        List<String> propNames = JoinInfo.getJoinEntityPropNamesByType(UserDao.class, UserEntity.class, "user_entity", OrderEntity.class);

        assertEquals(List.of("orders"), propNames);
    }

    @Test
    public void testGetPropJoinInfo_InvalidProperty() {
        assertThrows(IllegalArgumentException.class,
                () -> JoinInfo.getPropJoinInfo(UserDao.class, UserEntity.class, "user_entity", "missing"));
    }
}
