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

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.annotation.Column;
import com.landawn.abacus.annotation.JoinedBy;
import com.landawn.abacus.jdbc.annotation.DaoConfig;
import com.landawn.abacus.jdbc.dao.Dao;
import com.landawn.abacus.query.SqlBuilder.MSC;
import com.landawn.abacus.query.SqlBuilder.PSC;
import com.landawn.abacus.util.Tuple.Tuple2;
import com.landawn.abacus.util.Tuple.Tuple3;
import com.landawn.abacus.util.function.BiFunction;
import com.landawn.abacus.util.function.Function;
import com.landawn.abacus.util.function.IntFunction;

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

    // Direct join with '=' appearing more than once in a pair (L509-511)
    public static final class MultiEqJoinEntity {
        private long userId;
        @JoinedBy("userId = OrderEntity.id = extra")
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

    // Direct join with non-existent source property (L514-517)
    public static final class NoSrcPropJoinEntity {
        private long userId;
        @JoinedBy("nonExistentSrcProp = userId")
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

    // Direct join with non-existent referenced property (L520-524)
    public static final class NoRefPropJoinEntity {
        private long userId;
        @JoinedBy("userId = nonExistentRefProp")
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

    // Referenced entity with a String id to cause type mismatch (L527-530)
    public static final class StringIdEntity {
        private String id;
        private long userId;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public long getUserId() {
            return userId;
        }

        public void setUserId(long userId) {
            this.userId = userId;
        }
    }

    // Direct join where source type (long) != referenced type (String) → type mismatch (L527-530)
    public static final class TypeMismatchJoinEntity {
        private long userId;
        @JoinedBy("userId = id")
        private List<StringIdEntity> items;

        public long getUserId() {
            return userId;
        }

        public void setUserId(long userId) {
            this.userId = userId;
        }

        public List<StringIdEntity> getItems() {
            return items;
        }

        public void setItems(List<StringIdEntity> items) {
            this.items = items;
        }
    }

    // Entity with single (non-collection) join property (L993)
    public static final class SingleJoinEntity {
        private long userId;
        @JoinedBy("userId")
        private OrderEntity order;

        public long getUserId() {
            return userId;
        }

        public void setUserId(long userId) {
            this.userId = userId;
        }

        public OrderEntity getOrder() {
            return order;
        }

        public void setOrder(OrderEntity order) {
            this.order = order;
        }
    }

    @DaoConfig(allowJoiningByNullOrDefaultValue = true)
    interface SingleJoinDao extends Dao<SingleJoinEntity, PSC, SingleJoinDao> {
    }

    // Test constructor with empty @JoinedBy value throws
    @Test
    public void testConstructor_EmptyJoinedByValue() {
        assertThrows(IllegalArgumentException.class, () -> new JoinInfo(EmptyJoinedByEntity.class, "empty_joined_by_entity", "orders", false));
    }

    // Test constructor throws when direct join pair contains more than one '='
    @Test
    public void testConstructor_MultiEqInJoinPair() {
        assertThrows(IllegalArgumentException.class, () -> new JoinInfo(MultiEqJoinEntity.class, "multi_eq_join_entity", "orders", false));
    }

    // Test constructor throws when source property name does not exist
    @Test
    public void testConstructor_DirectJoin_NoSrcProp() {
        assertThrows(IllegalArgumentException.class, () -> new JoinInfo(NoSrcPropJoinEntity.class, "no_src_prop_join_entity", "orders", false));
    }

    // Test constructor throws when referenced property name does not exist
    @Test
    public void testConstructor_DirectJoin_NoRefProp() {
        assertThrows(IllegalArgumentException.class, () -> new JoinInfo(NoRefPropJoinEntity.class, "no_ref_prop_join_entity", "orders", false));
    }

    // Test constructor throws when source and referenced property types do not match
    @Test
    public void testConstructor_DirectJoin_TypeMismatch() {
        assertThrows(IllegalArgumentException.class, () -> new JoinInfo(TypeMismatchJoinEntity.class, "type_mismatch_join_entity", "items", false));
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
        assertThrows(IllegalArgumentException.class, () -> new JoinInfo(M2MNoEqSeparatorEntity.class, "m2m_no_eq", "roles", false));
    }

    @Test
    public void testConstructor_ManyToManyJoin_MultiEqInFirstPair() {
        assertThrows(IllegalArgumentException.class, () -> new JoinInfo(M2MMultiEqInFirstPairEntity.class, "m2m_multi_eq_first", "roles", false));
    }

    @Test
    public void testConstructor_ManyToManyJoin_MultiEqInSecondPair() {
        assertThrows(IllegalArgumentException.class, () -> new JoinInfo(M2MMultiEqInSecondPairEntity.class, "m2m_multi_eq_second", "roles", false));
    }

    // M2M: source property name not found in entity class → L274-277
    @Test
    public void testConstructor_ManyToManyJoin_NoSrcProp() {
        assertThrows(IllegalArgumentException.class, () -> new JoinInfo(M2MNoSrcPropEntity.class, "m2m_no_src", "roles", false));
    }

    // M2M: referenced property name not found in referenced entity class → L280-283
    @Test
    public void testConstructor_ManyToManyJoin_NoRefProp() {
        assertThrows(IllegalArgumentException.class, () -> new JoinInfo(M2MNoRefPropEntity.class, "m2m_no_ref", "roles", false));
    }

    // M2M: left[1] has no dot notation (no middle entity prefix) → L288-290
    @Test
    public void testConstructor_ManyToManyJoin_NoDotInMiddleRef() {
        assertThrows(IllegalArgumentException.class, () -> new JoinInfo(M2MNoDotInMiddleEntity.class, "m2m_no_dot", "roles", false));
    }

    // M2M: right[0] prefix does not match left[1] prefix → L295-298
    @Test
    public void testConstructor_ManyToManyJoin_WrongMiddleEntityPrefix() {
        assertThrows(IllegalArgumentException.class, () -> new JoinInfo(M2MWrongMiddleEntityEntity.class, "m2m_wrong_prefix", "roles", false));
    }

    // M2M: intermediate entity class does not exist on classpath → L307-309
    @Test
    public void testConstructor_ManyToManyJoin_MiddleClassNotFound() {
        assertThrows(IllegalArgumentException.class, () -> new JoinInfo(M2MClassNotFoundEntity.class, "m2m_class_not_found", "roles", false));
    }

    // M2M: left middle property not found in intermediate entity → L324-326
    @Test
    public void testConstructor_ManyToManyJoin_LeftMiddlePropNotFound() {
        assertThrows(IllegalArgumentException.class, () -> new JoinInfo(M2MLeftMiddlePropNotFoundEntity.class, "m2m_left_prop", "roles", false));
    }

    // M2M: right middle property not found in intermediate entity → L329-331
    @Test
    public void testConstructor_ManyToManyJoin_RightMiddlePropNotFound() {
        assertThrows(IllegalArgumentException.class, () -> new JoinInfo(M2MRightMiddlePropNotFoundEntity.class, "m2m_right_prop", "roles", false));
    }

    // M2M: source prop type (long) does not match middle entity left prop type (String) → L335-340
    @Test
    public void testConstructor_ManyToManyJoin_TypeMismatch() {
        assertThrows(IllegalArgumentException.class, () -> new JoinInfo(M2MTypeMismatchEntity.class, "m2m_type_mismatch", "roles", false));
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

    // getDeleteSqlPlan throws on unsupported SqlBuilder (L845)
    @Test
    public void testGetDeleteSqlPlan_UnsupportedBuilder() {
        JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserDao.class, UserEntity.class, "user_entity", "orders");
        assertThrows(IllegalArgumentException.class, () -> joinInfo.getDeleteSqlPlan(MSC.class));
    }

    // getBatchDeleteSqlPlan throws on unsupported SqlBuilder (L894)
    @Test
    public void testGetBatchDeleteSqlPlan_UnsupportedBuilder() {
        JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserDao.class, UserEntity.class, "user_entity", "orders");
        assertThrows(IllegalArgumentException.class, () -> joinInfo.getBatchDeleteSqlPlan(MSC.class));
    }

    // getJoinPropValue throws when join value is null/default and allowJoiningByNullOrDefaultValue=false (L1026-1028)
    interface UserStrictDao extends Dao<UserEntity, PSC, UserStrictDao> {
    }

    @Test
    public void testParamSetter_NullJoinValue_NotAllowed_Throws() {
        // UserStrictDao has no @DaoConfig allowJoiningByNullOrDefaultValue -> defaults to false.
        final JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserStrictDao.class, UserEntity.class, "user_entity_strict", "orders");
        final Tuple2<?, com.landawn.abacus.jdbc.Jdbc.BiParametersSetter<java.sql.PreparedStatement, Object>> plan = joinInfo.getSelectSqlPlan(PSC.class);
        final java.sql.PreparedStatement stmt = org.mockito.Mockito.mock(java.sql.PreparedStatement.class);
        final UserEntity entity = new UserEntity();
        entity.setUserId(0L); // 0 is the default for long → triggers the "not allowed" path

        assertThrows(IllegalArgumentException.class, () -> plan._2.accept(stmt, entity));
    }

    // setJoinPropEntities for a non-List collection prop (Set) (L978-981)
    @DaoConfig(allowJoiningByNullOrDefaultValue = true)
    interface SetUserDao extends Dao<SetUserEntity, PSC, SetUserDao> {
    }

    @DaoConfig(allowJoiningByNullOrDefaultValue = false)
    interface AllowFalseDao extends Dao<UserEntity, PSC, AllowFalseDao> {
    }

    @DaoConfig(allowJoiningByNullOrDefaultValue = true)
    interface CollectionUserDao extends Dao<CollectionUserEntity, PSC, CollectionUserDao> {
    }

    public static final class CollectionUserEntity {
        private long userId;
        @JoinedBy("userId")
        private Collection<OrderEntity> orders;

        public long getUserId() {
            return userId;
        }

        public void setUserId(long userId) {
            this.userId = userId;
        }

        public Collection<OrderEntity> getOrders() {
            return orders;
        }

        public void setOrders(Collection<OrderEntity> orders) {
            this.orders = orders;
        }
    }

    public static final class SetUserEntity {
        private long userId;
        @JoinedBy("userId")
        private java.util.Set<OrderEntity> orders;

        public long getUserId() {
            return userId;
        }

        public void setUserId(long userId) {
            this.userId = userId;
        }

        public java.util.Set<OrderEntity> getOrders() {
            return orders;
        }

        public void setOrders(java.util.Set<OrderEntity> orders) {
            this.orders = orders;
        }
    }

    @Test
    public void testSetJoinPropEntities_SetCollection() {
        final JoinInfo joinInfo = JoinInfo.getPropJoinInfo(SetUserDao.class, SetUserEntity.class, "set_user_entity", "orders");
        final SetUserEntity user = new SetUserEntity();
        user.setUserId(1L);
        final OrderEntity o = new OrderEntity();
        o.setUserId(1L);

        joinInfo.setJoinPropEntities(List.of(user), List.of(o));

        assertNotNull(user.getOrders());
        assertEquals(1, user.getOrders().size());
    }

    // setJoinPropEntities for Map property (L988-991)
    @DaoConfig(allowJoiningByNullOrDefaultValue = true)
    interface MapUserDao extends Dao<MapUserEntity, PSC, MapUserDao> {
    }

    public static final class MapUserEntity {
        private long userId;
        @JoinedBy("userId")
        private java.util.Map<Object, OrderEntity> orderByKey;

        public long getUserId() {
            return userId;
        }

        public void setUserId(long userId) {
            this.userId = userId;
        }

        public java.util.Map<Object, OrderEntity> getOrderByKey() {
            return orderByKey;
        }

        public void setOrderByKey(java.util.Map<Object, OrderEntity> orderByKey) {
            this.orderByKey = orderByKey;
        }
    }

    @Test
    public void testSetJoinPropEntities_MapProperty() {
        final JoinInfo joinInfo = JoinInfo.getPropJoinInfo(MapUserDao.class, MapUserEntity.class, "map_user_entity", "orderByKey");
        final MapUserEntity user = new MapUserEntity();
        user.setUserId(1L);
        final OrderEntity o = new OrderEntity();
        o.setUserId(1L);

        joinInfo.setJoinPropEntities(List.of(user), List.of(o));

        assertNotNull(user.getOrderByKey());
        assertEquals(1, user.getOrderByKey().size());
    }

    // setJoinPropEntities for Map prop with multiple matched entities (L984-985)
    @Test
    public void testSetJoinPropEntities_MapProperty_MultipleMatches_Throws() {
        final JoinInfo joinInfo = JoinInfo.getPropJoinInfo(MapUserDao.class, MapUserEntity.class, "map_user_entity_multi", "orderByKey");
        final MapUserEntity user = new MapUserEntity();
        user.setUserId(2L);
        final OrderEntity o1 = new OrderEntity();
        o1.setUserId(2L);
        final OrderEntity o2 = new OrderEntity();
        o2.setUserId(2L);

        assertThrows(IllegalArgumentException.class, () -> joinInfo.setJoinPropEntities(List.of(user), List.of(o1, o2)));
    }

    // 2-column composite join — exercise the BiParametersSetter & batch setter for srcPropInfos.length == 2
    @Test
    public void testParamSetter_TwoColumnJoin_ExecutesLambda() throws java.sql.SQLException {
        final JoinInfo joinInfo = JoinInfo.getPropJoinInfo(OrderItemDao.class, OrderItemEntity.class, "order_item_2col", "details");
        final java.sql.PreparedStatement stmt = org.mockito.Mockito.mock(java.sql.PreparedStatement.class);
        final OrderItemEntity entity = new OrderItemEntity();
        entity.setOrderId(10L);
        entity.setProductId(20L);

        final Tuple2<?, com.landawn.abacus.jdbc.Jdbc.BiParametersSetter<java.sql.PreparedStatement, Object>> plan = joinInfo.getSelectSqlPlan(PSC.class);
        plan._2.accept(stmt, entity);

        // Two parameter slots should have been set.
        org.mockito.Mockito.verify(stmt).setLong(1, 10L);
        org.mockito.Mockito.verify(stmt).setLong(2, 20L);
    }

    @Test
    public void testBatchParamSetter_TwoColumnJoin_ExecutesLambda() throws java.sql.SQLException {
        final JoinInfo joinInfo = JoinInfo.getPropJoinInfo(OrderItemDao.class, OrderItemEntity.class, "order_item_2col_batch", "details");
        final java.sql.PreparedStatement stmt = org.mockito.Mockito.mock(java.sql.PreparedStatement.class);
        final OrderItemEntity e1 = new OrderItemEntity();
        e1.setOrderId(1L);
        e1.setProductId(2L);
        final OrderItemEntity e2 = new OrderItemEntity();
        e2.setOrderId(3L);
        e2.setProductId(4L);

        final Tuple2<?, com.landawn.abacus.jdbc.Jdbc.BiParametersSetter<java.sql.PreparedStatement, Collection<?>>> batchPlan = joinInfo
                .getBatchSelectSqlPlan(PSC.class);
        batchPlan._2.accept(stmt, List.of(e1, e2));

        org.mockito.Mockito.verify(stmt).setLong(1, 1L);
        org.mockito.Mockito.verify(stmt).setLong(2, 2L);
        org.mockito.Mockito.verify(stmt).setLong(3, 3L);
        org.mockito.Mockito.verify(stmt).setLong(4, 4L);
    }

    // setNull param setter for 2-column composite join (L575-579)
    @Test
    public void testSetNullParamSetter_TwoColumnJoin() throws java.sql.SQLException {
        final JoinInfo joinInfo = JoinInfo.getPropJoinInfo(OrderItemDao.class, OrderItemEntity.class, "order_item_setnull", "details");
        final java.sql.PreparedStatement stmt = org.mockito.Mockito.mock(java.sql.PreparedStatement.class);
        final OrderItemEntity entity = new OrderItemEntity();
        entity.setOrderId(7L);
        entity.setProductId(8L);

        // The set-null plan exposes a Tuple3<setNullSql, paramSetter, ...>; just confirm we can invoke the param setter.
        final Tuple3<String, ?, com.landawn.abacus.jdbc.Jdbc.BiParametersSetter<java.sql.PreparedStatement, Object>> plan = joinInfo
                .getDeleteSqlPlan(PSC.class);
        // Use deleteSql plan param setter for 2-column entity (L538-547 path).
        plan._3.accept(stmt, entity);

        org.mockito.Mockito.verify(stmt).setLong(1, 7L);
        org.mockito.Mockito.verify(stmt).setLong(2, 8L);
    }

    // 3-column composite key — exercises srcPropInfos.length == 3 branch (L671-683)
    @DaoConfig(allowJoiningByNullOrDefaultValue = true)
    interface ThreeColParentDao extends Dao<ThreeColParent, PSC, ThreeColParentDao> {
    }

    public static final class ThreeColParent {
        private long a;
        private long b;
        private long c;

        @JoinedBy("a=ThreeColChild.a, b=ThreeColChild.b, c=ThreeColChild.c")
        private List<ThreeColChild> children;

        public long getA() {
            return a;
        }

        public void setA(long a) {
            this.a = a;
        }

        public long getB() {
            return b;
        }

        public void setB(long b) {
            this.b = b;
        }

        public long getC() {
            return c;
        }

        public void setC(long c) {
            this.c = c;
        }

        public List<ThreeColChild> getChildren() {
            return children;
        }

        public void setChildren(List<ThreeColChild> children) {
            this.children = children;
        }
    }

    public static final class ThreeColChild {
        private long a;
        private long b;
        private long c;

        public long getA() {
            return a;
        }

        public void setA(long a) {
            this.a = a;
        }

        public long getB() {
            return b;
        }

        public void setB(long b) {
            this.b = b;
        }

        public long getC() {
            return c;
        }

        public void setC(long c) {
            this.c = c;
        }
    }

    @Test
    public void testThreeColumnJoin_KeyExtractor() {
        final JoinInfo joinInfo = JoinInfo.getPropJoinInfo(ThreeColParentDao.class, ThreeColParent.class, "three_col_parent", "children");
        final ThreeColParent p = new ThreeColParent();
        p.setA(1L);
        p.setB(2L);
        p.setC(3L);
        final ThreeColChild c = new ThreeColChild();
        c.setA(1L);
        c.setB(2L);
        c.setC(3L);

        joinInfo.setJoinPropEntities(List.of(p), List.of(c));

        assertNotNull(p.getChildren());
        assertEquals(1, p.getChildren().size());
    }

    // 4-column composite key — exercises srcPropInfos.length > 3 branch (L685-702)
    @DaoConfig(allowJoiningByNullOrDefaultValue = true)
    interface FourColParentDao extends Dao<FourColParent, PSC, FourColParentDao> {
    }

    public static final class FourColParent {
        private long a;
        private long b;
        private long c;
        private long d;

        @JoinedBy("a=FourColChild.a, b=FourColChild.b, c=FourColChild.c, d=FourColChild.d")
        private List<FourColChild> children;

        public long getA() {
            return a;
        }

        public void setA(long a) {
            this.a = a;
        }

        public long getB() {
            return b;
        }

        public void setB(long b) {
            this.b = b;
        }

        public long getC() {
            return c;
        }

        public void setC(long c) {
            this.c = c;
        }

        public long getD() {
            return d;
        }

        public void setD(long d) {
            this.d = d;
        }

        public List<FourColChild> getChildren() {
            return children;
        }

        public void setChildren(List<FourColChild> children) {
            this.children = children;
        }
    }

    public static final class FourColChild {
        private long a;
        private long b;
        private long c;
        private long d;

        public long getA() {
            return a;
        }

        public void setA(long a) {
            this.a = a;
        }

        public long getB() {
            return b;
        }

        public void setB(long b) {
            this.b = b;
        }

        public long getC() {
            return c;
        }

        public void setC(long c) {
            this.c = c;
        }

        public long getD() {
            return d;
        }

        public void setD(long d) {
            this.d = d;
        }
    }

    @Test
    public void testFourColumnJoin_KeyExtractor() {
        final JoinInfo joinInfo = JoinInfo.getPropJoinInfo(FourColParentDao.class, FourColParent.class, "four_col_parent", "children");
        final FourColParent p = new FourColParent();
        p.setA(1L);
        p.setB(2L);
        p.setC(3L);
        p.setD(4L);
        final FourColChild c = new FourColChild();
        c.setA(1L);
        c.setB(2L);
        c.setC(3L);
        c.setD(4L);

        joinInfo.setJoinPropEntities(List.of(p), List.of(c));

        assertNotNull(p.getChildren());
        assertEquals(1, p.getChildren().size());
    }

    @Test
    public void testFourColumnJoin_BatchParamSetter() throws java.sql.SQLException {
        final JoinInfo joinInfo = JoinInfo.getPropJoinInfo(FourColParentDao.class, FourColParent.class, "four_col_parent_batch", "children");
        final java.sql.PreparedStatement stmt = org.mockito.Mockito.mock(java.sql.PreparedStatement.class);
        final FourColParent p = new FourColParent();
        p.setA(10L);
        p.setB(20L);
        p.setC(30L);
        p.setD(40L);

        // Param setter for >2-column branch (L562-570).
        final Tuple2<?, com.landawn.abacus.jdbc.Jdbc.BiParametersSetter<java.sql.PreparedStatement, Collection<?>>> plan = joinInfo
                .getBatchSelectSqlPlan(PSC.class);
        plan._2.accept(stmt, List.of(p));

        org.mockito.Mockito.verify(stmt).setLong(1, 10L);
        org.mockito.Mockito.verify(stmt).setLong(2, 20L);
        org.mockito.Mockito.verify(stmt).setLong(3, 30L);
        org.mockito.Mockito.verify(stmt).setLong(4, 40L);
    }

    // 2-column join key extractors via setJoinPropEntities (L652-653)
    @Test
    public void testTwoColumnJoin_SetJoinPropEntities() {
        final JoinInfo joinInfo = JoinInfo.getPropJoinInfo(OrderItemDao.class, OrderItemEntity.class, "order_item_2col_set", "details");
        final OrderItemEntity item = new OrderItemEntity();
        item.setOrderId(10L);
        item.setProductId(20L);
        final OrderDetailEntity detail = new OrderDetailEntity();
        detail.setOrderId(10L);
        detail.setProductId(20L);
        detail.setQty(5);

        joinInfo.setJoinPropEntities(List.of(item), List.of(detail));

        assertNotNull(item.getDetails());
        assertEquals(1, item.getDetails().size());
    }

    // m2m single param setter (L333-334)
    @Test
    public void testParamSetter_ManyToMany_ExecutesLambda() throws java.sql.SQLException {
        final JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserRoleUserDao.class, UserRoleUserEntity.class, "user_role_user_m2m_param", "roles");
        final java.sql.PreparedStatement stmt = org.mockito.Mockito.mock(java.sql.PreparedStatement.class);
        final UserRoleUserEntity entity = new UserRoleUserEntity();
        entity.setUserId(42L);

        final Tuple2<?, com.landawn.abacus.jdbc.Jdbc.BiParametersSetter<java.sql.PreparedStatement, Object>> plan = joinInfo.getSelectSqlPlan(PSC.class);
        plan._2.accept(stmt, entity);

        org.mockito.Mockito.verify(stmt).setLong(1, 42L);
    }

    // m2m batch param setter (L337-342)
    @Test
    public void testBatchParamSetter_ManyToMany_ExecutesLambda() throws java.sql.SQLException {
        final JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserRoleUserDao.class, UserRoleUserEntity.class, "user_role_user_m2m_batch", "roles");
        final java.sql.PreparedStatement stmt = org.mockito.Mockito.mock(java.sql.PreparedStatement.class);
        final UserRoleUserEntity e1 = new UserRoleUserEntity();
        e1.setUserId(1L);
        final UserRoleUserEntity e2 = new UserRoleUserEntity();
        e2.setUserId(2L);

        final Tuple2<?, com.landawn.abacus.jdbc.Jdbc.BiParametersSetter<java.sql.PreparedStatement, Collection<?>>> plan = joinInfo
                .getBatchSelectSqlPlan(PSC.class);
        plan._2.accept(stmt, List.of(e1, e2));

        org.mockito.Mockito.verify(stmt).setLong(1, 1L);
        org.mockito.Mockito.verify(stmt).setLong(2, 2L);
    }

    // m2m select plan with null/empty selectPropNames (L361-362)
    @Test
    public void testGetSelectSqlPlan_ManyToMany_NullColumns() {
        final JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserRoleUserDao.class, UserRoleUserEntity.class, "user_role_user_m2m_null", "roles");
        final Tuple2<Function<Collection<String>, String>, ?> plan = joinInfo.getSelectSqlPlan(PSC.class);
        final String sql = plan._1.apply(null);
        assertNotNull(sql);
        assertTrue(sql.contains("SELECT"));
    }

    // m2m batch select plan with null/empty selectPropNames (L411-412)
    @Test
    public void testGetBatchSelectSqlPlan_ManyToMany_NullColumns() {
        final JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserRoleUserDao.class, UserRoleUserEntity.class, "user_role_user_m2m_bnull", "roles");
        final Tuple2<BiFunction<Collection<String>, Integer, String>, ?> plan = joinInfo.getBatchSelectSqlPlan(PSC.class);
        final String sql = plan._1.apply(null, 2);
        assertNotNull(sql);
        assertTrue(sql.contains("JOIN"));
    }

    // m2m batch select plan with selectPropNames not containing ref prop (L416-419)
    @Test
    public void testGetBatchSelectSqlPlan_ManyToMany_ColumnsNotIncludingRefProp() {
        final JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserRoleUserDao.class, UserRoleUserEntity.class, "user_role_user_m2m_bref", "roles");
        final Tuple2<BiFunction<Collection<String>, Integer, String>, ?> plan = joinInfo.getBatchSelectSqlPlan(PSC.class);
        final String sql = plan._1.apply(List.of("name"), 2);
        assertNotNull(sql);
        assertTrue(sql.contains("JOIN"));
    }

    // batch select plan with size==1 for direct join (L594-595)
    @Test
    public void testGetBatchSelectSqlPlan_DirectJoin_SizeOne() {
        final JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserDao.class, UserEntity.class, "user_entity_direct_s1", "orders");
        final Tuple2<BiFunction<Collection<String>, Integer, String>, ?> plan = joinInfo.getBatchSelectSqlPlan(PSC.class);
        final String sql = plan._1.apply(null, 1);
        assertNotNull(sql);
        assertTrue(sql.contains("SELECT"));
    }

    // batch select plan with columns missing referenced prop for direct join (L600-609)
    @Test
    public void testGetBatchSelectSqlPlan_DirectJoin_ColumnsMissingRefProp() {
        final JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserDao.class, UserEntity.class, "user_entity_direct_cmr", "orders");
        final Tuple2<BiFunction<Collection<String>, Integer, String>, ?> plan = joinInfo.getBatchSelectSqlPlan(PSC.class);
        final String sql = plan._1.apply(List.of("id"), 2);
        assertNotNull(sql);
        assertTrue(sql.contains("SELECT"));
    }

    // batch select plan with columns including referenced prop for direct join (L612)
    @Test
    public void testGetBatchSelectSqlPlan_DirectJoin_ColumnsIncludingRefProp() {
        final JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserDao.class, UserEntity.class, "user_entity_direct_cir", "orders");
        final Tuple2<BiFunction<Collection<String>, Integer, String>, ?> plan = joinInfo.getBatchSelectSqlPlan(PSC.class);
        final String sql = plan._1.apply(List.of("userId"), 2);
        assertNotNull(sql);
        assertTrue(sql.contains("SELECT"));
    }

    // batch delete plan with size==1 for direct join (L627-628)
    @Test
    public void testGetBatchDeleteSqlPlan_DirectJoin_SizeOne() {
        final JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserDao.class, UserEntity.class, "user_entity_direct_bd1", "orders");
        final Tuple3<IntFunction<String>, IntFunction<String>, ?> plan = joinInfo.getBatchDeleteSqlPlan(PSC.class);
        final String sql = plan._1.apply(1);
        assertNotNull(sql);
        assertTrue(sql.contains("DELETE"));
    }

    // m2m batch delete plan with size==1 (L456-459)
    @Test
    public void testGetBatchDeleteSqlPlan_ManyToMany_SizeOne() {
        final JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserRoleUserDao.class, UserRoleUserEntity.class, "user_role_user_m2m_bd1", "roles");
        final Tuple3<IntFunction<String>, IntFunction<String>, ?> plan = joinInfo.getBatchDeleteSqlPlan(PSC.class);
        final String sql = plan._1.apply(1);
        assertNotNull(sql);
        assertTrue(sql.contains("DELETE"));
    }

    // m2m batch delete plan with size>1 (L459)
    @Test
    public void testGetBatchDeleteSqlPlan_ManyToMany_SizeMultiple() {
        final JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserRoleUserDao.class, UserRoleUserEntity.class, "user_role_user_m2m_bd3", "roles");
        final Tuple3<IntFunction<String>, IntFunction<String>, ?> plan = joinInfo.getBatchDeleteSqlPlan(PSC.class);
        final String sql = plan._1.apply(3);
        assertNotNull(sql);
        assertTrue(sql.contains("DELETE"));
    }

    // 1-column batch param setter (L533-536)
    @Test
    public void testBatchParamSetter_OneColumn_ExecutesLambda() throws java.sql.SQLException {
        final JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserDao.class, UserEntity.class, "user_entity_batch1", "orders");
        final java.sql.PreparedStatement stmt = org.mockito.Mockito.mock(java.sql.PreparedStatement.class);
        final UserEntity e1 = new UserEntity();
        e1.setUserId(10L);
        final UserEntity e2 = new UserEntity();
        e2.setUserId(20L);

        final Tuple2<?, com.landawn.abacus.jdbc.Jdbc.BiParametersSetter<java.sql.PreparedStatement, Collection<?>>> plan = joinInfo
                .getBatchSelectSqlPlan(PSC.class);
        plan._2.accept(stmt, List.of(e1, e2));

        org.mockito.Mockito.verify(stmt).setLong(1, 10L);
        org.mockito.Mockito.verify(stmt).setLong(2, 20L);
    }

    // param setter for strict dao with non-null value (L1018 branch 4: !allow && !isNullOrDefault)
    @Test
    public void testParamSetter_Strict_NonNullValue() throws java.sql.SQLException {
        final JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserStrictDao.class, UserEntity.class, "user_entity_strict_ok", "orders");
        final java.sql.PreparedStatement stmt = org.mockito.Mockito.mock(java.sql.PreparedStatement.class);
        final UserEntity entity = new UserEntity();
        entity.setUserId(5L);

        final Tuple2<?, com.landawn.abacus.jdbc.Jdbc.BiParametersSetter<java.sql.PreparedStatement, Object>> plan = joinInfo.getSelectSqlPlan(PSC.class);
        plan._2.accept(stmt, entity);

        org.mockito.Mockito.verify(stmt).setLong(1, 5L);
    }

    // DaoConfig with allowJoiningByNullOrDefaultValue=false (L1075)
    @Test
    public void testDaoConfig_AllowJoiningFalse() {
        final JoinInfo joinInfo = JoinInfo.getPropJoinInfo(AllowFalseDao.class, UserEntity.class, "user_entity_allow_false", "orders");
        assertNotNull(joinInfo);
        assertFalse(joinInfo.allowJoiningByNullOrDefaultValue);
    }

    // setJoinPropEntities with no matching joined entity (L965 null branch)
    @Test
    public void testSetJoinPropEntities_NoMatchingEntity() {
        final JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserDao.class, UserEntity.class, "user_entity_no_match", "orders");
        final UserEntity user = new UserEntity();
        user.setUserId(99L);
        final OrderEntity order = new OrderEntity();
        order.setUserId(1L);

        joinInfo.setJoinPropEntities(List.of(user), List.of(order));

        assertTrue(user.getOrders() == null || user.getOrders().isEmpty());
    }

    // setJoinPropEntities with Collection (non-List) prop type (L967 assignableFrom branch)
    @Test
    public void testSetJoinPropEntities_CollectionProp_NotList() {
        final JoinInfo joinInfo = JoinInfo.getPropJoinInfo(CollectionUserDao.class, CollectionUserEntity.class, "collection_user", "orders");
        final CollectionUserEntity user = new CollectionUserEntity();
        user.setUserId(1L);
        final OrderEntity o = new OrderEntity();
        o.setUserId(1L);

        joinInfo.setJoinPropEntities(List.of(user), List.of(o));

        assertNotNull(user.getOrders());
        assertEquals(1, user.getOrders().size());
    }

    // Regression: pre-fix the Collection overload silently produced empty/wrong results for M:M
    // joins because srcEntityKeyExtractor (e.g., employee.employeeId) and
    // referencedEntityKeyExtractor (e.g., project.projectId) read unrelated namespaces. The
    // previous form of this test only "passed" because userId=1L and roleId=1L coincidentally
    // matched. Fix throws UnsupportedOperationException to fail fast — callers must use the
    // Map overload with junction-table-augmented keys (DaoImpl's path).
    @Test
    public void testSetJoinPropEntities_ManyToMany_ThrowsForCollectionOverload() {
        final JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserRoleUserDao.class, UserRoleUserEntity.class, "user_role_user_m2m_set", "roles");
        final UserRoleUserEntity user = new UserRoleUserEntity();
        user.setUserId(1L);
        final RoleLookupEntity role = new RoleLookupEntity();
        role.setRoleId(1L);
        role.setName("Admin");

        assertThrows(UnsupportedOperationException.class, () -> joinInfo.setJoinPropEntities(List.of(user), List.of(role)));
    }

    // The Map overload remains the supported entry point for M:M: callers/the framework augment
    // each joined entity with the source-side junction key, then group by that key.
    @Test
    public void testSetJoinPropEntities_ManyToMany_MapOverloadWorks() {
        final JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserRoleUserDao.class, UserRoleUserEntity.class, "user_role_user_m2m_set", "roles");
        final UserRoleUserEntity user = new UserRoleUserEntity();
        user.setUserId(42L);
        final RoleLookupEntity role = new RoleLookupEntity();
        role.setRoleId(7L);
        role.setName("Admin");

        // Caller (DaoImpl in production) provides the junction-keyed map; here we simulate it by
        // keying on the source's userId.
        final java.util.Map<Object, java.util.List<Object>> grouped = new java.util.HashMap<>();
        grouped.put(42L, new java.util.ArrayList<>(List.of(role)));
        joinInfo.setJoinPropEntities(List.of(user), grouped);

        assertNotNull(user.getRoles());
        assertEquals(1, user.getRoles().size());
    }

    // M2M right pair missing '=' separator (L251 branch 2)
    @Test
    public void testConstructor_ManyToManyJoin_NoEqInSecondPair() {
        assertThrows(IllegalArgumentException.class, () -> new JoinInfo(M2MNoEqInSecondPairEntity.class, "m2m_no_eq2", "roles", false));
    }

    // ---- Three-column direct join: exercises the srcPropInfos.length > 2 param-setter branch (JoinInfo L526-530) ----

    @DaoConfig(allowJoiningByNullOrDefaultValue = true)
    interface ThreeColDao extends Dao<ThreeColEntity, PSC, ThreeColDao> {
    }

    public static final class ThreeColEntity {
        private long aId;
        private long bId;
        private long cId;

        @JoinedBy("aId=aId, bId=bId, cId=cId")
        private List<ThreeColRefEntity> refs;

        public long getAId() {
            return aId;
        }

        public void setAId(final long aId) {
            this.aId = aId;
        }

        public long getBId() {
            return bId;
        }

        public void setBId(final long bId) {
            this.bId = bId;
        }

        public long getCId() {
            return cId;
        }

        public void setCId(final long cId) {
            this.cId = cId;
        }

        public List<ThreeColRefEntity> getRefs() {
            return refs;
        }

        public void setRefs(final List<ThreeColRefEntity> refs) {
            this.refs = refs;
        }
    }

    public static final class ThreeColRefEntity {
        private long aId;
        private long bId;
        private long cId;

        public long getAId() {
            return aId;
        }

        public void setAId(final long aId) {
            this.aId = aId;
        }

        public long getBId() {
            return bId;
        }

        public void setBId(final long bId) {
            this.bId = bId;
        }

        public long getCId() {
            return cId;
        }

        public void setCId(final long cId) {
            this.cId = cId;
        }
    }

    // The non-batch param setter for a >2 column direct join loops over all source props (JoinInfo L526-530).
    @Test
    public void testParamSetter_ThreeColumnDirectJoin_ExecutesLoop() throws java.sql.SQLException {
        final JoinInfo joinInfo = JoinInfo.getPropJoinInfo(ThreeColDao.class, ThreeColEntity.class, "three_col_entity", "refs");
        assertNotNull(joinInfo);

        final java.sql.PreparedStatement stmt = org.mockito.Mockito.mock(java.sql.PreparedStatement.class);
        final ThreeColEntity entity = new ThreeColEntity();
        entity.setAId(11L);
        entity.setBId(22L);
        entity.setCId(33L);

        final Tuple3<String, String, com.landawn.abacus.jdbc.Jdbc.BiParametersSetter<java.sql.PreparedStatement, Object>> plan = joinInfo
                .getDeleteSqlPlan(PSC.class);
        assertNotNull(plan);
        plan._3.accept(stmt, entity);

        org.mockito.Mockito.verify(stmt).setLong(1, 11L);
        org.mockito.Mockito.verify(stmt).setLong(2, 22L);
        org.mockito.Mockito.verify(stmt).setLong(3, 33L);
    }

    // The select-plan param setter for a >2 column direct join also loops over all source props.
    @Test
    public void testParamSetter_ThreeColumnDirectJoin_SelectPlan() throws java.sql.SQLException {
        final JoinInfo joinInfo = JoinInfo.getPropJoinInfo(ThreeColDao.class, ThreeColEntity.class, "three_col_entity_sel", "refs");
        final java.sql.PreparedStatement stmt = org.mockito.Mockito.mock(java.sql.PreparedStatement.class);
        final ThreeColEntity entity = new ThreeColEntity();
        entity.setAId(1L);
        entity.setBId(2L);
        entity.setCId(3L);

        final Tuple2<Function<Collection<String>, String>, com.landawn.abacus.jdbc.Jdbc.BiParametersSetter<java.sql.PreparedStatement, Object>> plan = joinInfo
                .getSelectSqlPlan(PSC.class);
        plan._2.accept(stmt, entity);

        org.mockito.Mockito.verify(stmt).setLong(1, 1L);
        org.mockito.Mockito.verify(stmt).setLong(2, 2L);
        org.mockito.Mockito.verify(stmt).setLong(3, 3L);
    }

    // For an m2m join, getBatchDeleteSqlPlan still returns a usable main builder; the middle
    // builder slot (._2) is intentionally null because cascade delete is assumed DB-side.
    @Test
    public void testGetBatchDeleteSqlPlan_ManyToMany_MiddleBuilderIsNull() {
        final JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserRoleUserDao.class, UserRoleUserEntity.class, "user_role_user_m2m_mid1", "roles");
        final Tuple3<IntFunction<String>, IntFunction<String>, ?> plan = joinInfo.getBatchDeleteSqlPlan(PSC.class);
        assertNotNull(plan);
        assertNotNull(plan._1.apply(2));
        // cascadeDeleteDefinedInDB is hardcoded true, so the middle-delete builder is null.
        assertNull(plan._2);
    }

    // TODO: JoinInfo batchMiddleDeleteSqlBuilder bodies (L471, L473) and the middleDeleteSql slot
    // (L446) are unreachable: cascadeDeleteDefinedInDB is a hardcoded `true` constant (JoinInfo
    // L221), so the `? null :` ternary never evaluates the middle-delete branch. Dead code until
    // DB-side cascade handling is implemented.
    // TODO: JoinInfo setNullParamSetterForUpdate lambdas (L344-347, L555-571) are stored in
    // setNullSqlAndParamSetterPool which has no public accessor (getSetNullSqlAndParamSetter is
    // commented out), so they are unreachable in isolation and intentionally left uncovered.
    // TODO: JoinInfo L296 ("intermediate entity class is required but not found" when forName
    // returns null) is defensive dead code — ClassUtil.forName throws before returning null.

    // Regression: pre-fix the M2M batch SELECT SQL builder relied on fixed token offsets
    // (.get(2)/.get(10)/.get(14)) of the parsed middle SELECT SQL. Those positions only hold
    // the right tokens when SqlBuilder emits `AS "alias"` for every column — but it omits AS
    // when the column name already equals the property name. PLC (LOWER_CAMEL_CASE) never
    // emits AS, so pre-fix middleTableName resolved to a column name like "userId" instead of
    // the actual middle table name, producing nonsense SQL like `... INNER JOIN userId ON ...
    // WHERE userId.? IN (...)`. Fix anchors lookups on SELECT/FROM/WHERE keyword tokens
    // instead of fixed offsets.
    @Test
    public void testGetBatchSelectSqlPlan_PLC_ManyToMany_BuildsCorrectSql() {
        final JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserRoleUserDao.class, UserRoleUserEntity.class, "user_role_user_plc_m2m", "roles");
        final Tuple2<BiFunction<Collection<String>, Integer, String>, ?> plan = joinInfo.getBatchSelectSqlPlan(com.landawn.abacus.query.SqlBuilder.PLC.class);
        assertNotNull(plan);
        final String sql = plan._1.apply(null, 2);
        assertNotNull(sql);
        // PLC = LOWER_CAMEL_CASE, so SqlBuilder lowercase-first the middle class name to
        // `userRoleLink`. Post-fix this appears as the JOIN target and as the source-side
        // condition column qualifier. Pre-fix middleTableName resolved to `userId` (a column
        // name, not a table), producing broken SQL.
        assertTrue(sql.contains("INNER JOIN userRoleLink ON"), "Expected join against middle table 'userRoleLink', got:\n" + sql);
        assertTrue(sql.contains("userRoleLink.userId IN ("), "Expected WHERE on middle table's source-side column, got:\n" + sql);
        // Pre-fix the magic indices read column-name tokens as the table name; "INNER JOIN userId"
        // would have appeared instead of "INNER JOIN userRoleLink".
        assertFalse(sql.contains("INNER JOIN userId ON"), "Pre-fix broken-SQL pattern leaked into output:\n" + sql);
    }

    // M2M DAO whose middle FK column (perm_ref) does NOT collide with any referenced-entity column
    // (perm_id, label). This exercises the hasSameColumnName == false branch of the batch SELECT SQL
    // builder (JoinInfo L422 in the eager build and L447 in the dynamic lambda) — the existing M2M
    // fixtures all collide (UserRoleLink.roleId vs RoleLookupEntity.roleId), so only the true branch
    // was covered before.
    @DaoConfig(allowJoiningByNullOrDefaultValue = true)
    interface UserPermDao extends Dao<UserPermEntity, PSC, UserPermDao> {
    }

    @Test
    public void testGetBatchSelectSqlPlan_ManyToMany_DistinctColumnNames() {
        final JoinInfo joinInfo = JoinInfo.getPropJoinInfo(UserPermDao.class, UserPermEntity.class, "user_perm_entity", "perms");
        assertTrue(joinInfo.isManyToManyJoin());

        final Tuple2<BiFunction<Collection<String>, Integer, String>, ?> plan = joinInfo.getBatchSelectSqlPlan(PSC.class);
        assertNotNull(plan);

        // Eager-built batchSelectAllLeftSql already ran the L422 (false) branch during construction;
        // null columns returns that eager SQL.
        final String sqlAll = plan._1.apply(null, 2);
        assertNotNull(sqlAll);
        assertTrue(sqlAll.contains("JOIN"));

        // Non-empty columns (not containing the referenced prop) drives the dynamic builder through
        // the L447 (false) branch.
        final String sqlCols = plan._1.apply(List.of("label"), 2);
        assertNotNull(sqlCols);
        assertTrue(sqlCols.contains("JOIN"));
    }

    // ---- Direct unit tests for the private SQL-token helpers (JoinInfo L1266/L1275/L1283). These
    // defensive return paths are not reached by normal SELECT/FROM/WHERE SQL, so exercise them
    // directly via reflection. ----

    @Test
    public void testIndexOfKeyword_NotFound() throws Exception {
        final java.lang.reflect.Method m = JoinInfo.class.getDeclaredMethod("indexOfKeyword", List.class, String.class);
        m.setAccessible(true);
        final int idx = (int) m.invoke(null, List.of("alpha", "beta", "gamma"), "SELECT");
        assertEquals(-1, idx);
    }

    @Test
    public void testNextNonBlankToken_NegativeIndex() throws Exception {
        final java.lang.reflect.Method m = JoinInfo.class.getDeclaredMethod("nextNonBlankToken", List.class, int.class);
        m.setAccessible(true);
        final Object res = m.invoke(null, List.of("alpha", "beta"), -1);
        assertNull(res);
    }

    @Test
    public void testNextNonBlankToken_NoNonBlankAfterIndex() throws Exception {
        final java.lang.reflect.Method m = JoinInfo.class.getDeclaredMethod("nextNonBlankToken", List.class, int.class);
        m.setAccessible(true);
        // afterIndex=0 is valid, but every token after it is blank -> falls through to the final null.
        final Object res = m.invoke(null, Arrays.asList("alpha", "   ", "\t"), 0);
        assertNull(res);
    }
}

final class UserPermEntity {
    private long userId;

    @JoinedBy("userId = UserPermLink.userId, UserPermLink.permRef = permId")
    private List<PermLookupEntity> perms;

    public long getUserId() {
        return userId;
    }

    public void setUserId(final long userId) {
        this.userId = userId;
    }

    public List<PermLookupEntity> getPerms() {
        return perms;
    }

    public void setPerms(final List<PermLookupEntity> perms) {
        this.perms = perms;
    }
}

final class PermLookupEntity {
    private long permId;
    private String label;

    public long getPermId() {
        return permId;
    }

    public void setPermId(final long permId) {
        this.permId = permId;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(final String label) {
        this.label = label;
    }
}

final class UserPermLink {
    private long userId;
    private long permRef;

    public long getUserId() {
        return userId;
    }

    public void setUserId(final long userId) {
        this.userId = userId;
    }

    public long getPermRef() {
        return permRef;
    }

    public void setPermRef(final long permRef) {
        this.permRef = permRef;
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

// M2M join where only the second pair has no '=' separator → L251 branch 2
final class M2MNoEqInSecondPairEntity {
    private long userId;

    @JoinedBy("userId = UserRoleLink.userId, UserRoleLink.roleId")
    private List<RoleLookupEntity> roles;

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public List<RoleLookupEntity> getRoles() {
        return roles;
    }

    public void setRoles(List<RoleLookupEntity> roles) {
        this.roles = roles;
    }
}

// M2M join where the first pair has no '=' separator → L268-271
final class M2MNoEqSeparatorEntity {
    private long userId;

    @JoinedBy("userId, UserRoleLink.roleId = roleId")
    private List<RoleLookupEntity> roles;

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public List<RoleLookupEntity> getRoles() {
        return roles;
    }

    public void setRoles(List<RoleLookupEntity> roles) {
        this.roles = roles;
    }
}

// M2M join where the first pair has more than one '=' separator.
final class M2MMultiEqInFirstPairEntity {
    private long userId;

    @JoinedBy("userId = UserRoleLink.userId = extra, UserRoleLink.roleId = roleId")
    private List<RoleLookupEntity> roles;

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public List<RoleLookupEntity> getRoles() {
        return roles;
    }

    public void setRoles(List<RoleLookupEntity> roles) {
        this.roles = roles;
    }
}

// M2M join where the second pair has more than one '=' separator.
final class M2MMultiEqInSecondPairEntity {
    private long userId;

    @JoinedBy("userId = UserRoleLink.userId, UserRoleLink.roleId = roleId = extra")
    private List<RoleLookupEntity> roles;

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public List<RoleLookupEntity> getRoles() {
        return roles;
    }

    public void setRoles(List<RoleLookupEntity> roles) {
        this.roles = roles;
    }
}

// M2M join with non-existent source property → L274-277
final class M2MNoSrcPropEntity {
    private long userId;

    @JoinedBy("nonExistentSrc = UserRoleLink.userId, UserRoleLink.roleId = roleId")
    private List<RoleLookupEntity> roles;

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public List<RoleLookupEntity> getRoles() {
        return roles;
    }

    public void setRoles(List<RoleLookupEntity> roles) {
        this.roles = roles;
    }
}

// M2M join with non-existent referenced property → L280-283
final class M2MNoRefPropEntity {
    private long userId;

    @JoinedBy("userId = UserRoleLink.userId, UserRoleLink.roleId = nonExistentRef")
    private List<RoleLookupEntity> roles;

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public List<RoleLookupEntity> getRoles() {
        return roles;
    }

    public void setRoles(List<RoleLookupEntity> roles) {
        this.roles = roles;
    }
}

// M2M join where left[1] has no dot notation → L288-290
final class M2MNoDotInMiddleEntity {
    private long userId;

    @JoinedBy("userId = nodotpart, UserRoleLink.roleId = roleId")
    private List<RoleLookupEntity> roles;

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public List<RoleLookupEntity> getRoles() {
        return roles;
    }

    public void setRoles(List<RoleLookupEntity> roles) {
        this.roles = roles;
    }
}

// M2M join where right[0] doesn't start with the middle entity prefix → L295-298
final class M2MWrongMiddleEntityEntity {
    private long userId;

    @JoinedBy("userId = UserRoleLink.userId, DifferentClass.roleId = roleId")
    private List<RoleLookupEntity> roles;

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public List<RoleLookupEntity> getRoles() {
        return roles;
    }

    public void setRoles(List<RoleLookupEntity> roles) {
        this.roles = roles;
    }
}

// M2M join where intermediate entity class does not exist → L307-309
final class M2MClassNotFoundEntity {
    private long userId;

    @JoinedBy("userId = NonExistentMiddleClassXyz.userId, NonExistentMiddleClassXyz.roleId = roleId")
    private List<RoleLookupEntity> roles;

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public List<RoleLookupEntity> getRoles() {
        return roles;
    }

    public void setRoles(List<RoleLookupEntity> roles) {
        this.roles = roles;
    }
}

// M2M join where left middle property does not exist → L324-326
final class M2MLeftMiddlePropNotFoundEntity {
    private long userId;

    @JoinedBy("userId = UserRoleLink.nonExistentProp, UserRoleLink.roleId = roleId")
    private List<RoleLookupEntity> roles;

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public List<RoleLookupEntity> getRoles() {
        return roles;
    }

    public void setRoles(List<RoleLookupEntity> roles) {
        this.roles = roles;
    }
}

// M2M join where right middle property does not exist → L329-331
final class M2MRightMiddlePropNotFoundEntity {
    private long userId;

    @JoinedBy("userId = UserRoleLink.userId, UserRoleLink.nonExistentProp = roleId")
    private List<RoleLookupEntity> roles;

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public List<RoleLookupEntity> getRoles() {
        return roles;
    }

    public void setRoles(List<RoleLookupEntity> roles) {
        this.roles = roles;
    }
}

// Middle entity where userId is String (type mismatch with long in source entity) → L335-340
final class M2MTypeMismatchLink {
    private String userId; // String instead of long → type mismatch
    private long roleId;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public long getRoleId() {
        return roleId;
    }

    public void setRoleId(long roleId) {
        this.roleId = roleId;
    }
}

// M2M join where source prop type (long) does not match middle entity left prop type (String) → L335-340
final class M2MTypeMismatchEntity {
    private long userId;

    @JoinedBy("userId = M2MTypeMismatchLink.userId, M2MTypeMismatchLink.roleId = roleId")
    private List<RoleLookupEntity> roles;

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public List<RoleLookupEntity> getRoles() {
        return roles;
    }

    public void setRoles(List<RoleLookupEntity> roles) {
        this.roles = roles;
    }
}
