package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.annotation.JoinedBy;
import com.landawn.abacus.annotation.ReadOnly;
import com.landawn.abacus.jdbc.dao.UncheckedCrudDao;
import com.landawn.abacus.jdbc.dao.UncheckedCrudJoinEntityHelper;
import com.landawn.abacus.query.Filters;

/**
 * Regression tests for array-typed keys (e.g. a {@code byte[]} binary UUID stored as {@code BINARY(16)}). Arrays have
 * identity {@code equals}/{@code hashCode}, and every loaded row carries its own array instance, so matching such keys
 * through a {@code HashMap} never found a row:
 * <ul>
 *   <li>{@code batchUpsert} treated an existing row as new and inserted it again (a primary-key violation, or a
 *       duplicate row when matching on a non-unique column), and never detected two rows sharing one key;</li>
 *   <li>{@code batchRefresh} returned {@code 0} and left the entities stale;</li>
 *   <li>batch join loading ({@code JoinInfo.setJoinPropEntities}) gave every source entity an empty join collection.</li>
 * </ul>
 * Composite ids were already matched by content ({@code EntityId}/{@code IdValuesKey}).
 */
@Tag("integration")
@TestInstance(Lifecycle.PER_CLASS)
public class BinaryKeyMatchingTest extends TestBase {

    public interface BinKeyOwnerDao
            extends UncheckedCrudDao<BinKeyOwner, byte[], BinKeyOwnerDao>, UncheckedCrudJoinEntityHelper<BinKeyOwner, byte[], BinKeyOwnerDao> {
    }

    public interface BinKeyPetDao extends UncheckedCrudDao<BinKeyPet, Long, BinKeyPetDao> {
    }

    public interface BinKeyClubDao extends UncheckedCrudDao<BinKeyClub, Long, BinKeyClubDao> {
    }

    private DataSource ds;
    private BinKeyOwnerDao ownerDao;
    private BinKeyPetDao petDao;

    @BeforeAll
    public void initDb() throws SQLException {
        ds = JdbcUtil.createHikariDataSource("jdbc:h2:mem:binary_key_matching;DB_CLOSE_DELAY=-1", "sa", "");

        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE bin_key_owner (id VARBINARY(16) PRIMARY KEY, name VARCHAR(32))");
            st.execute("CREATE TABLE bin_key_pet (id BIGINT AUTO_INCREMENT PRIMARY KEY, owner_id VARBINARY(16), name VARCHAR(32))");
            st.execute("CREATE TABLE bin_key_club (id BIGINT PRIMARY KEY, name VARCHAR(32))");
            st.execute("CREATE TABLE bin_key_owner_club (owner_id VARBINARY(16), club_id BIGINT)");
        }

        ownerDao = JdbcUtil.createDao(BinKeyOwnerDao.class, ds);
        petDao = JdbcUtil.createDao(BinKeyPetDao.class, ds);
        JdbcUtil.createDao(BinKeyClubDao.class, ds);
    }

    @AfterAll
    public void closeDb() {
        ((com.zaxxer.hikari.HikariDataSource) ds).close();
    }

    @BeforeEach
    public void cleanTables() throws SQLException {
        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("TRUNCATE TABLE bin_key_owner");
            st.execute("TRUNCATE TABLE bin_key_pet");
            st.execute("TRUNCATE TABLE bin_key_club");
            st.execute("TRUNCATE TABLE bin_key_owner_club");
        }
    }

    // Every call builds a new array instance, as a row read from the database or a caller-built key would.
    private static byte[] key(final int... bytes) {
        final byte[] result = new byte[bytes.length];

        for (int i = 0; i < bytes.length; i++) {
            result[i] = (byte) bytes[i];
        }

        return result;
    }

    private static BinKeyOwner owner(final byte[] id, final String name) {
        final BinKeyOwner owner = new BinKeyOwner();
        owner.setId(id);
        owner.setName(name);
        return owner;
    }

    private static BinKeyPet pet(final String name) {
        final BinKeyPet pet = new BinKeyPet();
        pet.setName(name);
        return pet;
    }

    private BinKeyPet insertPet(final byte[] ownerId, final String name) {
        final BinKeyPet pet = pet(name);
        pet.setOwnerId(ownerId);
        pet.setId(petDao.insert(pet));
        return pet;
    }

    private List<String> ownerNames() {
        return ownerDao.list(Filters.alwaysTrue()).stream().map(BinKeyOwner::getName).sorted().toList();
    }

    @Test
    public void batchUpsertUpdatesExistingRowsMatchedByBinaryId() {
        ownerDao.insert(owner(key(1, 2, 3), "original"));

        final List<BinKeyOwner> saved = ownerDao.batchUpsert(List.of(owner(key(1, 2, 3), "updated"), owner(key(9), "inserted")));

        assertEquals(List.of("updated", "inserted"), saved.stream().map(BinKeyOwner::getName).toList());
        assertEquals(List.of("inserted", "updated"), ownerNames());
    }

    @Test
    public void batchUpsertMatchesANonUniqueBinaryColumnInsteadOfInsertingADuplicate() {
        final BinKeyPet existing = insertPet(key(7, 7), "rex");
        final BinKeyPet incoming = new BinKeyPet();
        incoming.setOwnerId(key(7, 7));
        incoming.setName("max");

        final List<BinKeyPet> saved = petDao.batchUpsert(List.of(incoming), List.of("ownerId"));

        assertEquals(existing.getId(), saved.get(0).getId());
        assertEquals(1, petDao.count(Filters.alwaysTrue()));
        assertEquals("max", petDao.getOrNull(existing.getId()).getName());
    }

    // Content matching also restores the documented check that one key must not match two existing rows.
    @Test
    public void batchUpsertRejectsTwoExistingRowsSharingOneBinaryKey() {
        insertPet(key(8), "first");
        insertPet(key(8), "second");
        final BinKeyPet incoming = new BinKeyPet();
        incoming.setOwnerId(key(8));
        incoming.setName("third");

        assertThrows(IllegalStateException.class, () -> petDao.batchUpsert(List.of(incoming), List.of("ownerId")));
        assertEquals(2, petDao.count(Filters.alwaysTrue()));
    }

    @Test
    public void batchRefreshMatchesLoadedRowsByBinaryIdContent() {
        ownerDao.insert(owner(key(1, 2, 3), "fresh"));
        final BinKeyOwner stale = owner(key(1, 2, 3), "stale");
        final BinKeyOwner staleCopy = owner(key(1, 2, 3), "stale copy");
        final BinKeyOwner missing = owner(key(5), "missing");

        final int refreshed = ownerDao.batchRefresh(List.of(stale, staleCopy, missing), List.of("name"));

        assertEquals(2, refreshed);
        assertEquals("fresh", stale.getName());
        assertEquals("fresh", staleCopy.getName());
        assertEquals("missing", missing.getName());
    }

    // More than one source entity takes the batch path, which groups the loaded pets by their join key.
    @Test
    public void batchJoinLoadMatchesBinaryJoinKeys() {
        ownerDao.batchInsert(List.of(owner(key(1), "a"), owner(key(2), "b"), owner(key(3), "c")));
        insertPet(key(1), "a1");
        insertPet(key(1), "a2");
        insertPet(key(2), "b1");

        final List<BinKeyOwner> owners = new ArrayList<>(ownerDao.list(Filters.alwaysTrue()));
        owners.sort(Comparator.comparing(BinKeyOwner::getName));
        ownerDao.loadJoinEntities(owners, "pets");

        assertEquals(List.of("a1", "a2"), owners.get(0).getPets().stream().map(BinKeyPet::getName).sorted().toList());
        assertEquals(List.of("b1"), owners.get(1).getPets().stream().map(BinKeyPet::getName).toList());
        assertTrue(owners.get(2).getPets().isEmpty());

        // The single-entity path must agree with the batch path.
        final BinKeyOwner single = owner(key(1), "a");
        ownerDao.loadJoinEntities(single, "pets");
        assertEquals(2, single.getPets().size());
    }

    // Many-to-many batch loading groups the joined rows by the intermediate table's key column.
    @Test
    public void batchManyToManyJoinLoadMatchesBinaryJoinKeys() throws SQLException {
        ownerDao.batchInsert(List.of(owner(key(1), "a"), owner(key(2), "b"), owner(key(3), "c")));

        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("INSERT INTO bin_key_club VALUES (10, 'chess'), (20, 'go')");
            st.execute("INSERT INTO bin_key_owner_club VALUES (X'01', 10), (X'01', 20), (X'02', 20)");
        }

        final List<BinKeyOwner> owners = List.of(owner(key(1), "a"), owner(key(2), "b"), owner(key(3), "c"));
        ownerDao.loadJoinEntities(owners, "clubs");

        assertEquals(List.of("chess", "go"), owners.get(0).getClubs().stream().map(BinKeyClub::getName).sorted().toList());
        assertEquals(List.of("go"), owners.get(1).getClubs().stream().map(BinKeyClub::getName).toList());
        assertTrue(owners.get(2).getClubs().isEmpty());
    }

    // Multi-column join keys are Tuples (2-3 columns) or Lists (4+ columns); an array component made them identity-keyed too.
    @Test
    public void multiColumnJoinKeysWithABinaryComponentMatchByContent() {
        final JoinInfo twoColumns = new JoinInfo(BinKeyTwoColumnParent.class, "bin_key_two_column_parent", "children", false);
        final BinKeyTwoColumnParent first = new BinKeyTwoColumnParent(key(1), 1);
        final BinKeyTwoColumnParent second = new BinKeyTwoColumnParent(key(1), 2);

        twoColumns.setJoinPropEntities(List.of(first, second), List.of(new BinKeyTwoColumnChild(key(1), 1, "x"), new BinKeyTwoColumnChild(key(1), 1, "y"),
                new BinKeyTwoColumnChild(key(2), 2, "other tenant")));

        assertEquals(List.of("x", "y"), first.getChildren().stream().map(BinKeyTwoColumnChild::getName).toList());
        assertTrue(second.getChildren().isEmpty());

        final JoinInfo fourColumns = new JoinInfo(BinKeyFourColumnParent.class, "bin_key_four_column_parent", "children", false);
        final BinKeyFourColumnParent parent = new BinKeyFourColumnParent(key(4), 1, 2, 3);
        final BinKeyFourColumnParent unmatched = new BinKeyFourColumnParent(key(4), 1, 2, 9);

        fourColumns.setJoinPropEntities(List.of(parent, unmatched),
                List.of(new BinKeyFourColumnChild(key(4), 1, 2, 3, "match"), new BinKeyFourColumnChild(key(5), 1, 2, 3, "other")));

        assertEquals(List.of("match"), parent.getChildren().stream().map(BinKeyFourColumnChild::getName).toList());
        assertTrue(unmatched.getChildren().isEmpty());
    }

    // Pre-grouped raw keys with equal content (one group per row) are merged in the map's iteration order.
    @Test
    public void preGroupedBinaryKeysAreMergedInMapOrder() {
        final JoinInfo joinInfo = new JoinInfo(BinKeyOwner.class, "bin_key_owner", "pets", false);
        final Map<Object, List<Object>> grouped = new LinkedHashMap<>();
        grouped.put(key(1), List.<Object> of(pet("first")));
        grouped.put(key(2), List.<Object> of(pet("other owner")));
        grouped.put(key(1), List.<Object> of(pet("second")));
        grouped.put(key(1), null);
        final BinKeyOwner owner = owner(key(1), "owner");

        joinInfo.setJoinPropEntities(List.of(owner), grouped);

        assertEquals(List.of("first", "second"), owner.getPets().stream().map(BinKeyPet::getName).toList());
    }

    // A single-valued join property receives its match by content, and null when nothing matches.
    @Test
    public void singleValuedJoinPropertyIsMatchedAndUnmatchedIsNull() {
        final JoinInfo joinInfo = new JoinInfo(BinKeyTwoColumnParent.class, "bin_key_two_column_parent", "firstChild", false);
        final BinKeyTwoColumnParent matched = new BinKeyTwoColumnParent(key(3), 3);
        final BinKeyTwoColumnParent unmatched = new BinKeyTwoColumnParent(key(3), 4);

        joinInfo.setJoinPropEntities(List.of(matched, unmatched), List.of(new BinKeyTwoColumnChild(key(3), 3, "only")));

        assertEquals("only", matched.getFirstChild().getName());
        assertNull(unmatched.getFirstChild());
    }
}

// The intermediate entity of a many-to-many join must be a top-level class in the entity's package, so the fixtures
// are package-level classes (as in JoinInfoTest).
final class BinKeyOwner {
    @Id
    private byte[] id;
    private String name;

    @JoinedBy("id=BinKeyPet.ownerId")
    private List<BinKeyPet> pets;

    @JoinedBy("id = BinKeyOwnerClub.ownerId, BinKeyOwnerClub.clubId = id")
    private List<BinKeyClub> clubs;

    public byte[] getId() {
        return id;
    }

    public void setId(final byte[] id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public List<BinKeyPet> getPets() {
        return pets;
    }

    public void setPets(final List<BinKeyPet> pets) {
        this.pets = pets;
    }

    public List<BinKeyClub> getClubs() {
        return clubs;
    }

    public void setClubs(final List<BinKeyClub> clubs) {
        this.clubs = clubs;
    }
}

final class BinKeyPet {
    @Id
    @ReadOnly
    private Long id;
    private byte[] ownerId;
    private String name;

    public Long getId() {
        return id;
    }

    public void setId(final Long id) {
        this.id = id;
    }

    public byte[] getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(final byte[] ownerId) {
        this.ownerId = ownerId;
    }

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }
}

final class BinKeyClub {
    @Id
    private Long id;
    private String name;

    public Long getId() {
        return id;
    }

    public void setId(final Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }
}

final class BinKeyOwnerClub {
    private byte[] ownerId;
    private long clubId;

    public byte[] getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(final byte[] ownerId) {
        this.ownerId = ownerId;
    }

    public long getClubId() {
        return clubId;
    }

    public void setClubId(final long clubId) {
        this.clubId = clubId;
    }
}

final class BinKeyTwoColumnParent {
    private byte[] tenant;
    private int code;

    @JoinedBy("tenant=BinKeyTwoColumnChild.tenant, code=BinKeyTwoColumnChild.code")
    private List<BinKeyTwoColumnChild> children;

    @JoinedBy("tenant=BinKeyTwoColumnChild.tenant, code=BinKeyTwoColumnChild.code")
    private BinKeyTwoColumnChild firstChild;

    public BinKeyTwoColumnParent() {
    }

    BinKeyTwoColumnParent(final byte[] tenant, final int code) {
        this.tenant = tenant;
        this.code = code;
    }

    public byte[] getTenant() {
        return tenant;
    }

    public void setTenant(final byte[] tenant) {
        this.tenant = tenant;
    }

    public int getCode() {
        return code;
    }

    public void setCode(final int code) {
        this.code = code;
    }

    public List<BinKeyTwoColumnChild> getChildren() {
        return children;
    }

    public void setChildren(final List<BinKeyTwoColumnChild> children) {
        this.children = children;
    }

    public BinKeyTwoColumnChild getFirstChild() {
        return firstChild;
    }

    public void setFirstChild(final BinKeyTwoColumnChild firstChild) {
        this.firstChild = firstChild;
    }
}

final class BinKeyTwoColumnChild {
    private byte[] tenant;
    private int code;
    private String name;

    public BinKeyTwoColumnChild() {
    }

    BinKeyTwoColumnChild(final byte[] tenant, final int code, final String name) {
        this.tenant = tenant;
        this.code = code;
        this.name = name;
    }

    public byte[] getTenant() {
        return tenant;
    }

    public void setTenant(final byte[] tenant) {
        this.tenant = tenant;
    }

    public int getCode() {
        return code;
    }

    public void setCode(final int code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }
}

final class BinKeyFourColumnParent {
    private byte[] a;
    private int b;
    private int c;
    private int d;

    @JoinedBy("a=BinKeyFourColumnChild.a, b=BinKeyFourColumnChild.b, c=BinKeyFourColumnChild.c, d=BinKeyFourColumnChild.d")
    private List<BinKeyFourColumnChild> children;

    public BinKeyFourColumnParent() {
    }

    BinKeyFourColumnParent(final byte[] a, final int b, final int c, final int d) {
        this.a = a;
        this.b = b;
        this.c = c;
        this.d = d;
    }

    public byte[] getA() {
        return a;
    }

    public void setA(final byte[] a) {
        this.a = a;
    }

    public int getB() {
        return b;
    }

    public void setB(final int b) {
        this.b = b;
    }

    public int getC() {
        return c;
    }

    public void setC(final int c) {
        this.c = c;
    }

    public int getD() {
        return d;
    }

    public void setD(final int d) {
        this.d = d;
    }

    public List<BinKeyFourColumnChild> getChildren() {
        return children;
    }

    public void setChildren(final List<BinKeyFourColumnChild> children) {
        this.children = children;
    }
}

final class BinKeyFourColumnChild {
    private byte[] a;
    private int b;
    private int c;
    private int d;
    private String name;

    public BinKeyFourColumnChild() {
    }

    BinKeyFourColumnChild(final byte[] a, final int b, final int c, final int d, final String name) {
        this.a = a;
        this.b = b;
        this.c = c;
        this.d = d;
        this.name = name;
    }

    public byte[] getA() {
        return a;
    }

    public void setA(final byte[] a) {
        this.a = a;
    }

    public int getB() {
        return b;
    }

    public void setB(final int b) {
        this.b = b;
    }

    public int getC() {
        return c;
    }

    public void setC(final int c) {
        this.c = c;
    }

    public int getD() {
        return d;
    }

    public void setD(final int d) {
        this.d = d;
    }

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }
}
