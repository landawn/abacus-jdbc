package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.logging.Logger;
import com.landawn.abacus.logging.LoggerFactory;

/**
 * Regression tests for review fixes in {@link JdbcUtil}.
 *
 * <p>The {@code createCacheKey} fix replaces a per-call {@code new Object[0]} allocation for {@code null}
 * args with the shared {@code N.EMPTY_OBJECT_ARRAY} (DAO cache-key generation runs on every cached DAO call,
 * and no-arg DAO methods are common). The allocation itself is not observable, so these tests pin the
 * externally visible behavior: {@code null} args are treated exactly like an empty argument array.</p>
 */
class JdbcUtilReviewFixTest extends JdbcUtilTestSupport {

    private static final Logger daoLogger = LoggerFactory.getLogger(JdbcUtilReviewFixTest.class);

    // createCacheKey: null args and an empty args array must produce the identical cache key.
    @Test
    public void testCreateCacheKey_NullArgsEqualsEmptyArgs() {
        final String keyWithNullArgs = JdbcUtil.createCacheKey("user_account", "UserDao.list", null, daoLogger);
        final String keyWithEmptyArgs = JdbcUtil.createCacheKey("user_account", "UserDao.list", new Object[0], daoLogger);

        assertNotNull(keyWithNullArgs);
        assertEquals(keyWithEmptyArgs, keyWithNullArgs);
        assertTrue(keyWithNullArgs.startsWith("UserDao.list" + JdbcUtil.CACHE_KEY_SEPARATOR + "user_account" + JdbcUtil.CACHE_KEY_SEPARATOR));
    }

    // createCacheKey: distinct argument values must still produce distinct keys after the empty-array sharing change.
    @Test
    public void testCreateCacheKey_DistinctArgsDistinctKeys() {
        final String keyA = JdbcUtil.createCacheKey("user_account", "UserDao.find", new Object[] { "Alice", 1 }, daoLogger);
        final String keyB = JdbcUtil.createCacheKey("user_account", "UserDao.find", new Object[] { "Bob", 1 }, daoLogger);
        final String keyC = JdbcUtil.createCacheKey("user_account", "UserDao.find", new Object[] { "Alice", 2 }, daoLogger);
        final String keyD = JdbcUtil.createCacheKey("user_account", "UserDao.find", new Object[] { "Alice", 1, List.of(1, 2) }, daoLogger);

        assertNotNull(keyA);
        assertNotNull(keyB);
        assertNotNull(keyC);
        assertNotNull(keyD);
        assertNotEquals(keyA, keyB);
        assertNotEquals(keyA, keyC);
        assertNotEquals(keyA, keyD);
    }

    // createCacheKey: same arguments produce a stable, repeatable key (cache lookups depend on this).
    @Test
    public void testCreateCacheKey_Repeatable() {
        final Object[] args = { "Alice", 1, List.of(1, 2) };
        final String first = JdbcUtil.createCacheKey("user_account", "UserDao.list", args, daoLogger);
        final String second = JdbcUtil.createCacheKey("user_account", "UserDao.list", args, daoLogger);

        assertNotNull(first);
        assertEquals(first, second);
    }
}
