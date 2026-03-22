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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.annotation.JoinedBy;
import com.landawn.abacus.jdbc.annotation.DaoConfig;
import com.landawn.abacus.jdbc.dao.Dao;
import com.landawn.abacus.query.SqlBuilder.PSC;
import com.landawn.abacus.util.Tuple.Tuple2;
import com.landawn.abacus.util.function.BiFunction;
import com.landawn.abacus.util.function.Function;

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
        assertThrows(IllegalArgumentException.class, () -> JoinInfo.getPropJoinInfo(UserDao.class, UserEntity.class, "user_entity", "missing"));
    }

    // Test getSelectSqlPlan returns valid plan
    @Test
    public void testGetSelectSqlPlan() {
        JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserDao.class, UserEntity.class, "user_entity", "orders");
        Tuple2<Function<Collection<String>, String>, ?> plan = joinInfo.getSelectSqlPlan(PSC.class);
        assertNotNull(plan);
        // SQL should contain the referenced table
        String sql = plan._1.apply(null);
        assertNotNull(sql);
    }

    // Test getSelectSqlPlan with columns
    @Test
    public void testGetSelectSqlPlan_WithColumns() {
        JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserDao.class, UserEntity.class, "user_entity", "orders");
        Tuple2<Function<Collection<String>, String>, ?> plan = joinInfo.getSelectSqlPlan(PSC.class);
        String sql = plan._1.apply(Arrays.asList("id", "userId"));
        assertNotNull(sql);
        assertTrue(sql.contains("SELECT"));
    }

    // Test getBatchSelectSqlPlan returns valid plan
    @Test
    public void testGetBatchSelectSqlPlan() {
        JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserDao.class, UserEntity.class, "user_entity", "orders");
        Tuple2<BiFunction<Collection<String>, Integer, String>, ?> batchPlan = joinInfo.getBatchSelectSqlPlan(PSC.class);
        assertNotNull(batchPlan);
        String sql = batchPlan._1.apply(null, 3);
        assertNotNull(sql);
    }

    // Test PAC SqlBuilder also works
    @Test
    public void testGetSelectSqlPlan_PACBuilder() {
        JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserDao.class, UserEntity.class, "user_entity", "orders");
        Tuple2<Function<Collection<String>, String>, ?> plan = joinInfo.getSelectSqlPlan(com.landawn.abacus.query.SqlBuilder.PAC.class);
        assertNotNull(plan);
        String sql = plan._1.apply(null);
        assertNotNull(sql);
    }

    // Entity with 2-column join (exercises srcPropInfos.length == 2 code path)
    @DaoConfig(allowJoiningByNullOrDefaultValue = true)
    interface OrderItemDao extends Dao<OrderItemEntity, PSC, OrderItemDao> {
    }

    public static final class OrderItemEntity {
        private long orderId;
        private long productId;

        @JoinedBy("orderId=OrderDetailEntity.orderId, productId=OrderDetailEntity.productId")
        private List<OrderDetailEntity> details;

        public long getOrderId() {
            return orderId;
        }

        public void setOrderId(long orderId) {
            this.orderId = orderId;
        }

        public long getProductId() {
            return productId;
        }

        public void setProductId(long productId) {
            this.productId = productId;
        }

        public List<OrderDetailEntity> getDetails() {
            return details;
        }

        public void setDetails(List<OrderDetailEntity> details) {
            this.details = details;
        }
    }

    public static final class OrderDetailEntity {
        private long orderId;
        private long productId;
        private int qty;

        public long getOrderId() {
            return orderId;
        }

        public void setOrderId(long orderId) {
            this.orderId = orderId;
        }

        public long getProductId() {
            return productId;
        }

        public void setProductId(long productId) {
            this.productId = productId;
        }

        public int getQty() {
            return qty;
        }

        public void setQty(int qty) {
            this.qty = qty;
        }
    }

    @Test
    public void testJoinInfo_TwoColumnJoin() {
        JoinInfo joinInfo = JoinInfo.getPropJoinInfo(OrderItemDao.class, OrderItemEntity.class, "order_item", "details");
        assertNotNull(joinInfo);
        assertEquals("details", joinInfo.joinPropInfo.name);
    }

    @Test
    public void testGetSelectSqlPlan_TwoColumnJoin() {
        JoinInfo joinInfo = JoinInfo.getPropJoinInfo(OrderItemDao.class, OrderItemEntity.class, "order_item", "details");
        Tuple2<Function<Collection<String>, String>, ?> plan = joinInfo.getSelectSqlPlan(PSC.class);
        assertNotNull(plan);
        String sql = plan._1.apply(null);
        assertNotNull(sql);
    }

    @Test
    public void testGetBatchSelectSqlPlan_TwoColumnJoin() {
        JoinInfo joinInfo = JoinInfo.getPropJoinInfo(OrderItemDao.class, OrderItemEntity.class, "order_item", "details");
        Tuple2<BiFunction<Collection<String>, Integer, String>, ?> plan = joinInfo.getBatchSelectSqlPlan(PSC.class);
        assertNotNull(plan);
        String sql = plan._1.apply(null, 2);
        assertNotNull(sql);
    }
}
