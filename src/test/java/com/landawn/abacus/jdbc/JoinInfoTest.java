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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import com.landawn.abacus.util.function.IntFunction;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.annotation.Column;
import com.landawn.abacus.annotation.JoinedBy;
import com.landawn.abacus.jdbc.annotation.DaoConfig;
import com.landawn.abacus.jdbc.dao.Dao;
import com.landawn.abacus.query.SqlBuilder.MSC;
import com.landawn.abacus.query.SqlBuilder.PAC;
import com.landawn.abacus.query.SqlBuilder.PSC;
import com.landawn.abacus.util.Tuple.Tuple2;
import com.landawn.abacus.util.Tuple.Tuple3;
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

    @DaoConfig(allowJoiningByNullOrDefaultValue = true)
    interface UserRoleUserDao extends Dao<UserRoleUserEntity, PSC, UserRoleUserDao> {
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

    @Test
    public void testJoinInfo_ManyToManyJoin() {
        JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserRoleUserDao.class, UserRoleUserEntity.class, "user_role_user_entity", "roles");

        assertNotNull(joinInfo);
        assertTrue(joinInfo.isManyToManyJoin());
        assertEquals("roles", joinInfo.joinPropInfo.name);
        assertEquals(RoleLookupEntity.class, joinInfo.referencedEntityClass);
    }

    @Test
    public void testGetBatchSelectSqlPlan_ManyToManyJoin() {
        JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserRoleUserDao.class, UserRoleUserEntity.class, "user_role_user_entity", "roles");
        Tuple2<BiFunction<Collection<String>, Integer, String>, ?> plan = joinInfo.getBatchSelectSqlPlan(PSC.class);

        assertNotNull(plan);

        String sql = plan._1.apply(List.of("roleId"), 2);

        assertNotNull(sql);
        assertTrue(sql.contains("JOIN"));
    }

    // Test deprecated getSelectSqlBuilderAndParamSetter delegates to getSelectSqlPlan
    @Test
    @SuppressWarnings("deprecation")
    public void testGetSelectSqlBuilderAndParamSetter_Deprecated() {
        JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserDao.class, UserEntity.class, "user_entity", "orders");
        Tuple2<Function<Collection<String>, String>, ?> plan = joinInfo.getSelectSqlBuilderAndParamSetter(PSC.class);
        assertNotNull(plan);
        assertNotNull(plan._1.apply(null));
    }

    // Test deprecated getBatchSelectSqlBuilderAndParamSetter delegates to getBatchSelectSqlPlan
    @Test
    @SuppressWarnings("deprecation")
    public void testGetBatchSelectSqlBuilderAndParamSetter_Deprecated() {
        JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserDao.class, UserEntity.class, "user_entity", "orders");
        Tuple2<BiFunction<Collection<String>, Integer, String>, ?> plan = joinInfo.getBatchSelectSqlBuilderAndParamSetter(PSC.class);
        assertNotNull(plan);
        assertNotNull(plan._1.apply(null, 2));
    }

    // Test getDeleteSqlPlan returns valid plan
    @Test
    public void testGetDeleteSqlPlan() {
        JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserDao.class, UserEntity.class, "user_entity", "orders");
        Tuple3<String, String, ?> plan = joinInfo.getDeleteSqlPlan(PSC.class);
        assertNotNull(plan);
        assertNotNull(plan._1);
        assertTrue(plan._1.contains("DELETE"));
    }

    // Test deprecated getDeleteSqlAndParamSetter delegates to getDeleteSqlPlan
    @Test
    @SuppressWarnings("deprecation")
    public void testGetDeleteSqlAndParamSetter_Deprecated() {
        JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserDao.class, UserEntity.class, "user_entity", "orders");
        Tuple3<String, String, ?> plan = joinInfo.getDeleteSqlAndParamSetter(PSC.class);
        assertNotNull(plan);
        assertNotNull(plan._1);
    }

    // Test getDeleteSqlPlan for many-to-many join
    @Test
    public void testGetDeleteSqlPlan_ManyToManyJoin() {
        JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserRoleUserDao.class, UserRoleUserEntity.class, "user_role_user_entity", "roles");
        Tuple3<String, String, ?> plan = joinInfo.getDeleteSqlPlan(PSC.class);
        assertNotNull(plan);
        assertNotNull(plan._1);
        assertTrue(joinInfo.isManyToManyJoin());
    }

    // Test getBatchDeleteSqlPlan returns valid plan
    @Test
    public void testGetBatchDeleteSqlPlan() {
        JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserDao.class, UserEntity.class, "user_entity", "orders");
        Tuple3<IntFunction<String>, IntFunction<String>, ?> plan = joinInfo.getBatchDeleteSqlPlan(PSC.class);
        assertNotNull(plan);
        assertNotNull(plan._1.apply(3));
        assertTrue(plan._1.apply(3).contains("DELETE"));
    }

    // Test deprecated getBatchDeleteSqlBuilderAndParamSetter
    @Test
    @SuppressWarnings("deprecation")
    public void testGetBatchDeleteSqlBuilderAndParamSetter_Deprecated() {
        JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserDao.class, UserEntity.class, "user_entity", "orders");
        Tuple3<IntFunction<String>, IntFunction<String>, ?> plan = joinInfo.getBatchDeleteSqlBuilderAndParamSetter(PSC.class);
        assertNotNull(plan);
        assertNotNull(plan._1.apply(2));
    }

    // Test that getSelectSqlPlan throws for unsupported SqlBuilder
    @Test
    public void testGetSelectSqlPlan_UnsupportedBuilder() {
        JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserDao.class, UserEntity.class, "user_entity", "orders");
        assertThrows(IllegalArgumentException.class, () -> joinInfo.getSelectSqlPlan(MSC.class));
    }

    // Test that getBatchSelectSqlPlan throws for unsupported SqlBuilder
    @Test
    public void testGetBatchSelectSqlPlan_UnsupportedBuilder() {
        JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserDao.class, UserEntity.class, "user_entity", "orders");
        assertThrows(IllegalArgumentException.class, () -> joinInfo.getBatchSelectSqlPlan(MSC.class));
    }

    // Test setJoinPropEntities populates join properties
    @Test
    public void testSetJoinPropEntities() {
        JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserDao.class, UserEntity.class, "user_entity", "orders");
        UserEntity user1 = new UserEntity();
        user1.setUserId(1L);
        UserEntity user2 = new UserEntity();
        user2.setUserId(2L);
        OrderEntity o1 = new OrderEntity();
        o1.setUserId(1L);
        OrderEntity o2 = new OrderEntity();
        o2.setUserId(2L);

        joinInfo.setJoinPropEntities(Arrays.asList(user1, user2), Arrays.asList(o1, o2));

        assertNotNull(user1.getOrders());
        assertEquals(1, user1.getOrders().size());
        assertEquals(o1, user1.getOrders().get(0));
        assertNotNull(user2.getOrders());
        assertEquals(1, user2.getOrders().size());
    }

    // Test isManyToManyJoin is false for direct join
    @Test
    public void testIsManyToManyJoin_False() {
        JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserDao.class, UserEntity.class, "user_entity", "orders");
        assertFalse(joinInfo.isManyToManyJoin());
    }

    // Test constructor throws for non-existent property name
    @Test
    public void testConstructor_PropertyNotFound() {
        assertThrows(IllegalArgumentException.class, () -> new JoinInfo(UserEntity.class, "user_entity", "nonExistentProperty", false));
    }

    // Test constructor throws for property not annotated with @JoinedBy
    @Test
    public void testConstructor_PropertyNotAnnotatedWithJoinedBy() {
        assertThrows(IllegalArgumentException.class, () -> new JoinInfo(OrderEntity.class, "order_entity", "userId", false));
    }

    // Test constructor throws for property with @Column and @JoinedBy
    @Test
    public void testConstructor_PropertyWithColumnAnnotation() {
        assertThrows(IllegalArgumentException.class, () -> new JoinInfo(InvalidColumnJoinEntity.class, "invalid", "orders", false));
    }

    // Test constructor throws for join property with non-bean referenced type
    @Test
    public void testConstructor_NonBeanReferencedType() {
        assertThrows(IllegalArgumentException.class, () -> new JoinInfo(NonBeanJoinEntity.class, "non_bean", "tag", false));
    }

    // Test constructor throws for many-to-many with wrong number of join column pairs
    @Test
    public void testConstructor_ManyToManyJoin_WrongPairCount() {
        assertThrows(IllegalArgumentException.class, () -> new JoinInfo(InvalidManyToManyEntity.class, "invalid_m2m", "items", false));
    }

    // Entity with @Column + @JoinedBy (invalid combination)
    public static final class InvalidColumnJoinEntity {
        private long id;
        @Column("orders_col")
        @JoinedBy("id=OrderEntity.id")
        private List<OrderEntity> orders;

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public List<OrderEntity> getOrders() {
            return orders;
        }

        public void setOrders(List<OrderEntity> orders) {
            this.orders = orders;
        }
    }

    // Entity with @JoinedBy pointing to a non-bean type (String)
    public static final class NonBeanJoinEntity {
        private long id;
        @JoinedBy("id=String.id")
        private String tag;

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public String getTag() {
            return tag;
        }

        public void setTag(String tag) {
            this.tag = tag;
        }
    }

    // Entity with many-to-many @JoinedBy that has wrong number of pairs (3 pairs)
    public static final class InvalidManyToManyEntity {
        private long id;
        @JoinedBy("id = Link.id, Link.itemId = OrderEntity.id, Link.otherId = OrderEntity.otherId")
        private List<OrderEntity> items;

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public List<OrderEntity> getItems() {
            return items;
        }

        public void setItems(List<OrderEntity> items) {
            this.items = items;
        }
    }

    // Entity with @JoinedBy("") - empty value - triggers L241-243
    public static final class EmptyJoinedByEntity {
        private long id;
        @JoinedBy("")
        private List<OrderEntity> orders;

        public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public List<OrderEntity> getOrders() { return orders; }
        public void setOrders(List<OrderEntity> orders) { this.orders = orders; }
    }

    // Direct join with '=' appearing more than once in a pair (L509-511)
    public static final class MultiEqJoinEntity {
        private long userId;
        @JoinedBy("userId = OrderEntity.id = extra")
        private List<OrderEntity> orders;

        public long getUserId() { return userId; }
        public void setUserId(long userId) { this.userId = userId; }
        public List<OrderEntity> getOrders() { return orders; }
        public void setOrders(List<OrderEntity> orders) { this.orders = orders; }
    }

    // Direct join with non-existent source property (L514-517)
    public static final class NoSrcPropJoinEntity {
        private long userId;
        @JoinedBy("nonExistentSrcProp = userId")
        private List<OrderEntity> orders;

        public long getUserId() { return userId; }
        public void setUserId(long userId) { this.userId = userId; }
        public List<OrderEntity> getOrders() { return orders; }
        public void setOrders(List<OrderEntity> orders) { this.orders = orders; }
    }

    // Direct join with non-existent referenced property (L520-524)
    public static final class NoRefPropJoinEntity {
        private long userId;
        @JoinedBy("userId = nonExistentRefProp")
        private List<OrderEntity> orders;

        public long getUserId() { return userId; }
        public void setUserId(long userId) { this.userId = userId; }
        public List<OrderEntity> getOrders() { return orders; }
        public void setOrders(List<OrderEntity> orders) { this.orders = orders; }
    }

    // Referenced entity with a String id to cause type mismatch (L527-530)
    public static final class StringIdEntity {
        private String id;
        private long userId;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public long getUserId() { return userId; }
        public void setUserId(long userId) { this.userId = userId; }
    }

    // Direct join where source type (long) != referenced type (String) → type mismatch (L527-530)
    public static final class TypeMismatchJoinEntity {
        private long userId;
        @JoinedBy("userId = id")
        private List<StringIdEntity> items;

        public long getUserId() { return userId; }
        public void setUserId(long userId) { this.userId = userId; }
        public List<StringIdEntity> getItems() { return items; }
        public void setItems(List<StringIdEntity> items) { this.items = items; }
    }

    // Entity with single (non-collection) join property (L993)
    public static final class SingleJoinEntity {
        private long userId;
        @JoinedBy("userId")
        private OrderEntity order;

        public long getUserId() { return userId; }
        public void setUserId(long userId) { this.userId = userId; }
        public OrderEntity getOrder() { return order; }
        public void setOrder(OrderEntity order) { this.order = order; }
    }

    @DaoConfig(allowJoiningByNullOrDefaultValue = true)
    interface SingleJoinDao extends Dao<SingleJoinEntity, PSC, SingleJoinDao> {}

    // Test constructor with empty @JoinedBy value throws
    @Test
    public void testConstructor_EmptyJoinedByValue() {
        assertThrows(IllegalArgumentException.class,
                () -> new JoinInfo(EmptyJoinedByEntity.class, "empty_joined_by_entity", "orders", false));
    }

    // Test constructor throws when direct join pair contains more than one '='
    @Test
    public void testConstructor_MultiEqInJoinPair() {
        assertThrows(IllegalArgumentException.class,
                () -> new JoinInfo(MultiEqJoinEntity.class, "multi_eq_join_entity", "orders", false));
    }

    // Test constructor throws when source property name does not exist
    @Test
    public void testConstructor_DirectJoin_NoSrcProp() {
        assertThrows(IllegalArgumentException.class,
                () -> new JoinInfo(NoSrcPropJoinEntity.class, "no_src_prop_join_entity", "orders", false));
    }

    // Test constructor throws when referenced property name does not exist
    @Test
    public void testConstructor_DirectJoin_NoRefProp() {
        assertThrows(IllegalArgumentException.class,
                () -> new JoinInfo(NoRefPropJoinEntity.class, "no_ref_prop_join_entity", "orders", false));
    }

    // Test constructor throws when source and referenced property types do not match
    @Test
    public void testConstructor_DirectJoin_TypeMismatch() {
        assertThrows(IllegalArgumentException.class,
                () -> new JoinInfo(TypeMismatchJoinEntity.class, "type_mismatch_join_entity", "items", false));
    }

    // Test setJoinPropEntities populates a single (non-collection) join property (L993)
    @Test
    public void testSetJoinPropEntities_SingleEntityProp() {
        final JoinInfo joinInfo = JoinInfo.getPropJoinInfo(SingleJoinDao.class, SingleJoinEntity.class, "single_join_entity", "order");
        final SingleJoinEntity parent = new SingleJoinEntity();
        parent.setUserId(10L);
        final OrderEntity child = new OrderEntity();
        child.setUserId(10L);

        joinInfo.setJoinPropEntities(List.of(parent), List.of(child));

        assertEquals(child, parent.getOrder());
    }

    // M2M: first pair has no '=' separator → L268-271
    @Test
    public void testConstructor_ManyToManyJoin_NoEqInFirstPair() {
        assertThrows(IllegalArgumentException.class,
                () -> new JoinInfo(M2MNoEqSeparatorEntity.class, "m2m_no_eq", "roles", false));
    }

    // M2M: source property name not found in entity class → L274-277
    @Test
    public void testConstructor_ManyToManyJoin_NoSrcProp() {
        assertThrows(IllegalArgumentException.class,
                () -> new JoinInfo(M2MNoSrcPropEntity.class, "m2m_no_src", "roles", false));
    }

    // M2M: referenced property name not found in referenced entity class → L280-283
    @Test
    public void testConstructor_ManyToManyJoin_NoRefProp() {
        assertThrows(IllegalArgumentException.class,
                () -> new JoinInfo(M2MNoRefPropEntity.class, "m2m_no_ref", "roles", false));
    }

    // M2M: left[1] has no dot notation (no middle entity prefix) → L288-290
    @Test
    public void testConstructor_ManyToManyJoin_NoDotInMiddleRef() {
        assertThrows(IllegalArgumentException.class,
                () -> new JoinInfo(M2MNoDotInMiddleEntity.class, "m2m_no_dot", "roles", false));
    }

    // M2M: right[0] prefix does not match left[1] prefix → L295-298
    @Test
    public void testConstructor_ManyToManyJoin_WrongMiddleEntityPrefix() {
        assertThrows(IllegalArgumentException.class,
                () -> new JoinInfo(M2MWrongMiddleEntityEntity.class, "m2m_wrong_prefix", "roles", false));
    }

    // M2M: intermediate entity class does not exist on classpath → L307-309
    @Test
    public void testConstructor_ManyToManyJoin_MiddleClassNotFound() {
        assertThrows(IllegalArgumentException.class,
                () -> new JoinInfo(M2MClassNotFoundEntity.class, "m2m_class_not_found", "roles", false));
    }

    // M2M: left middle property not found in intermediate entity → L324-326
    @Test
    public void testConstructor_ManyToManyJoin_LeftMiddlePropNotFound() {
        assertThrows(IllegalArgumentException.class,
                () -> new JoinInfo(M2MLeftMiddlePropNotFoundEntity.class, "m2m_left_prop", "roles", false));
    }

    // M2M: right middle property not found in intermediate entity → L329-331
    @Test
    public void testConstructor_ManyToManyJoin_RightMiddlePropNotFound() {
        assertThrows(IllegalArgumentException.class,
                () -> new JoinInfo(M2MRightMiddlePropNotFoundEntity.class, "m2m_right_prop", "roles", false));
    }

    // M2M: source prop type (long) does not match middle entity left prop type (String) → L335-340
    @Test
    public void testConstructor_ManyToManyJoin_TypeMismatch() {
        assertThrows(IllegalArgumentException.class,
                () -> new JoinInfo(M2MTypeMismatchEntity.class, "m2m_type_mismatch", "roles", false));
    }

    // M2M getSelectSqlPlan with selectPropNames not containing the referenced prop (L381-386)
    @Test
    public void testGetSelectSqlPlan_ManyToMany_WithColumnsMissingRefProp() {
        JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserRoleUserDao.class, UserRoleUserEntity.class, "user_role_user_entity", "roles");
        Tuple2<Function<Collection<String>, String>, ?> plan = joinInfo.getSelectSqlPlan(PSC.class);
        // Pass column names that do NOT include the referenced prop (roleId)
        String sql = plan._1.apply(List.of("name"));
        assertNotNull(sql);
        assertTrue(sql.contains("SELECT"));
    }

    // M2M getSelectSqlPlan with selectPropNames containing the referenced prop (L388)
    @Test
    public void testGetSelectSqlPlan_ManyToMany_WithColumnsIncludingRefProp() {
        JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserRoleUserDao.class, UserRoleUserEntity.class, "user_role_user_entity", "roles");
        Tuple2<Function<Collection<String>, String>, ?> plan = joinInfo.getSelectSqlPlan(PSC.class);
        // Pass column names that include roleId (the referenced prop)
        String sql = plan._1.apply(List.of("roleId", "name"));
        assertNotNull(sql);
        assertTrue(sql.contains("SELECT"));
    }
}

final class UserRoleUserEntity {
    private long userId;

    @JoinedBy("userId = UserRoleLink.userId, UserRoleLink.roleId = roleId")
    private List<RoleLookupEntity> roles;

    public long getUserId() {
        return userId;
    }

    public void setUserId(final long userId) {
        this.userId = userId;
    }

    public List<RoleLookupEntity> getRoles() {
        return roles;
    }

    public void setRoles(final List<RoleLookupEntity> roles) {
        this.roles = roles;
    }
}

final class RoleLookupEntity {
    private long roleId;
    private String name;

    public long getRoleId() {
        return roleId;
    }

    public void setRoleId(final long roleId) {
        this.roleId = roleId;
    }

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }
}

final class UserRoleLink {
    private long userId;
    private long roleId;

    public long getUserId() {
        return userId;
    }

    public void setUserId(final long userId) {
        this.userId = userId;
    }

    public long getRoleId() {
        return roleId;
    }

    public void setRoleId(final long roleId) {
        this.roleId = roleId;
    }
}

// M2M join where the first pair has no '=' separator → L268-271
final class M2MNoEqSeparatorEntity {
    private long userId;

    @JoinedBy("userId, UserRoleLink.roleId = roleId")
    private List<RoleLookupEntity> roles;

    public long getUserId() { return userId; }
    public void setUserId(long userId) { this.userId = userId; }
    public List<RoleLookupEntity> getRoles() { return roles; }
    public void setRoles(List<RoleLookupEntity> roles) { this.roles = roles; }
}

// M2M join with non-existent source property → L274-277
final class M2MNoSrcPropEntity {
    private long userId;

    @JoinedBy("nonExistentSrc = UserRoleLink.userId, UserRoleLink.roleId = roleId")
    private List<RoleLookupEntity> roles;

    public long getUserId() { return userId; }
    public void setUserId(long userId) { this.userId = userId; }
    public List<RoleLookupEntity> getRoles() { return roles; }
    public void setRoles(List<RoleLookupEntity> roles) { this.roles = roles; }
}

// M2M join with non-existent referenced property → L280-283
final class M2MNoRefPropEntity {
    private long userId;

    @JoinedBy("userId = UserRoleLink.userId, UserRoleLink.roleId = nonExistentRef")
    private List<RoleLookupEntity> roles;

    public long getUserId() { return userId; }
    public void setUserId(long userId) { this.userId = userId; }
    public List<RoleLookupEntity> getRoles() { return roles; }
    public void setRoles(List<RoleLookupEntity> roles) { this.roles = roles; }
}

// M2M join where left[1] has no dot notation → L288-290
final class M2MNoDotInMiddleEntity {
    private long userId;

    @JoinedBy("userId = nodotpart, UserRoleLink.roleId = roleId")
    private List<RoleLookupEntity> roles;

    public long getUserId() { return userId; }
    public void setUserId(long userId) { this.userId = userId; }
    public List<RoleLookupEntity> getRoles() { return roles; }
    public void setRoles(List<RoleLookupEntity> roles) { this.roles = roles; }
}

// M2M join where right[0] doesn't start with the middle entity prefix → L295-298
final class M2MWrongMiddleEntityEntity {
    private long userId;

    @JoinedBy("userId = UserRoleLink.userId, DifferentClass.roleId = roleId")
    private List<RoleLookupEntity> roles;

    public long getUserId() { return userId; }
    public void setUserId(long userId) { this.userId = userId; }
    public List<RoleLookupEntity> getRoles() { return roles; }
    public void setRoles(List<RoleLookupEntity> roles) { this.roles = roles; }
}

// M2M join where intermediate entity class does not exist → L307-309
final class M2MClassNotFoundEntity {
    private long userId;

    @JoinedBy("userId = NonExistentMiddleClassXyz.userId, NonExistentMiddleClassXyz.roleId = roleId")
    private List<RoleLookupEntity> roles;

    public long getUserId() { return userId; }
    public void setUserId(long userId) { this.userId = userId; }
    public List<RoleLookupEntity> getRoles() { return roles; }
    public void setRoles(List<RoleLookupEntity> roles) { this.roles = roles; }
}

// M2M join where left middle property does not exist → L324-326
final class M2MLeftMiddlePropNotFoundEntity {
    private long userId;

    @JoinedBy("userId = UserRoleLink.nonExistentProp, UserRoleLink.roleId = roleId")
    private List<RoleLookupEntity> roles;

    public long getUserId() { return userId; }
    public void setUserId(long userId) { this.userId = userId; }
    public List<RoleLookupEntity> getRoles() { return roles; }
    public void setRoles(List<RoleLookupEntity> roles) { this.roles = roles; }
}

// M2M join where right middle property does not exist → L329-331
final class M2MRightMiddlePropNotFoundEntity {
    private long userId;

    @JoinedBy("userId = UserRoleLink.userId, UserRoleLink.nonExistentProp = roleId")
    private List<RoleLookupEntity> roles;

    public long getUserId() { return userId; }
    public void setUserId(long userId) { this.userId = userId; }
    public List<RoleLookupEntity> getRoles() { return roles; }
    public void setRoles(List<RoleLookupEntity> roles) { this.roles = roles; }
}

// Middle entity where userId is String (type mismatch with long in source entity) → L335-340
final class M2MTypeMismatchLink {
    private String userId;  // String instead of long → type mismatch
    private long roleId;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public long getRoleId() { return roleId; }
    public void setRoleId(long roleId) { this.roleId = roleId; }
}

// M2M join where source prop type (long) does not match middle entity left prop type (String) → L335-340
final class M2MTypeMismatchEntity {
    private long userId;

    @JoinedBy("userId = M2MTypeMismatchLink.userId, M2MTypeMismatchLink.roleId = roleId")
    private List<RoleLookupEntity> roles;

    public long getUserId() { return userId; }
    public void setUserId(long userId) { this.userId = userId; }
    public List<RoleLookupEntity> getRoles() { return roles; }
    public void setRoles(List<RoleLookupEntity> roles) { this.roles = roles; }
}
