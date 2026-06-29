# Design Doc: Capability-Based DAO Interface Split

**Status:** Proposal (analysis only — no code changed)
**Author:** generated for review
**Date:** 2026-06-28
**Scope:** Backward compatibility is **explicitly waived**. Names, generic
signatures, and the type graph may change freely; removed write methods becoming
compile errors is the intended outcome, not a regression to mitigate.

## 1. Goal

Replace the current *subtractive* DAO restriction model — where `ReadOnlyDao`,
`NoUpdateDao`, and their `Crud`/`L`/`Unchecked`/`JoinEntityHelper` variants
re-declare every forbidden method as an `@Deprecated default` that
`throw new UnsupportedOperationException(...)` — with an *additive* capability
model built from `ReadableDao` / `InsertableDao` / `UpdatableDao` /
`DeletableDao` (plus CRUD-level twins). Restrictions are then expressed by
**not inheriting** a capability, so:

* forbidden calls fail at **compile time** instead of throwing at runtime;
* the ~165 throw-stub method bodies (~4,500 lines across 18 files) largely disappear;
* the diamond re-resolution boilerplate in the `Unchecked*ReadOnly/NoUpdate` files disappears.

## 2. Why the boilerplate exists today

Two forces:

1. **Subtractive overriding.** Each forbidden operation must be physically
   re-declared to throw (e.g. `ReadOnlyDao.save`, `NoUpdateCrudDao.deleteById`).
2. **Diamond re-resolution.** `UncheckedReadOnlyDao extends UncheckedNoUpdateDao, ReadOnlyDao`.
   When two unrelated super-interfaces each supply a default for the same
   signature, Java *forces* the sub-interface to override it — so the Unchecked
   restrictive files re-declare throws their parents already declared
   (see `UncheckedReadOnlyDao.java:100-205`).

Throw-stub inventory (counted 2026-06-28):

| File | UOE stubs | File | UOE stubs |
|---|---|---|---|
| ReadOnlyDao | 24 | UncheckedReadOnlyDao | 9 |
| NoUpdateDao | 27 | UncheckedNoUpdateDao | 7 |
| ReadOnlyCrudDao | 9 | UncheckedReadOnlyCrudDao | 9 |
| NoUpdateCrudDao | 21 | UncheckedNoUpdateCrudDao | 21 |
| NoUpdateCrudDaoL | 3 | UncheckedNoUpdateCrudDaoL | 3 |
| ReadOnlyJoinEntityHelper | 16 | UncheckedReadOnlyJoinEntityHelper | 16 |
| **subtotal** | **100** | **subtotal** | **65** |

Total ≈ **165** throw stubs.

## 3. Feasibility against the proxy (`DaoImpl`)

The split is mechanically safe because `DaoImpl` does **not** enforce read-only
by method name:

* It collects `getDeclaredMethods()` over all interfaces (`DaoImpl.java:2067`).
* **Default** methods are run as-is via a bound `MethodHandle` (`DaoImpl.java:2393`).
* **Abstract** methods get a generated implementation by name/signature.

Enforcement lives entirely in the interface default bodies +
`DaoUtil.isReadOnlyQuery/isNoUpdateQuery`. If a restrictive interface simply
doesn't inherit `save`, the method isn't on the type, no handler is generated,
and the call won't compile. **One exception** — caching — is keyed off the
`NoUpdateDao` *type* and needs the marker change in §7.

## 4. Operation taxonomy (exact method → capability assignment)

Verified against `Dao.java`, `CrudDao.java`, `JoinEntityHelper.java`.

### 4.1 Condition-level (declared in `Dao`)

**ReadableDao** (reads + read-form prepares):
`list/findFirst/findOnlyOne/query/stream/count/exists/queryForXxx/...`,
`prepareQuery(String)`, `prepareQueryForLargeResult(String)`,
`prepareNamedQuery(String)`, `prepareNamedQuery(ParsedSql)`,
`prepareNamedQueryForLargeResult(String)`, `prepareNamedQueryForLargeResult(ParsedSql)`.

**InsertableDao**:
`save(T)`, `save(T, Collection)`, `save(String, T)`,
`batchSave` (×6),
plus the generated-key prepare overloads
`prepareQuery(String, boolean)`, `prepareQuery(String, int[])`, `prepareQuery(String, String[])`,
`prepareNamedQuery(String, boolean|int[]|String[])`,
`prepareNamedQuery(ParsedSql, boolean|int[]|String[])`.

**UpdatableDao**:
`update(String, Object, Condition)`, `update(Map, Condition)`,
`update(T, Condition)`, `update(T, Collection, Condition)`.

**DeletableDao**:
`delete(Condition)`.

### 4.2 CRUD-level (declared in `CrudDao`)

**ReadableCrudDao**:
`get/gett` (×4), `batchGet` (×4), `exists(ID)`, `notExists(ID)`,
`count(Collection<ID>)`, `queryForBoolean/Char/Byte/Short/Int/Long/Float/Double/String/Date/Time/Timestamp/Bytes(String, ID)`,
`refresh(T)`, `refresh(T, Collection)`, `generateId()` *(NonDBOperation)*.

**InsertableCrudDao**:
`insert(T)`, `insert(T, Collection)`, `insert(String, T)`, `batchInsert` (×4).

**UpdatableCrudDao**:
`update(T)`, `update(T, Collection)`, `update(String, Object, ID)`,
`update(Map, ID)`, `batchUpdate` (×4).

**DeletableCrudDao**:
`delete(T)`, `deleteById(ID)`, `batchDelete` (×2), `batchDeleteByIds` (×2).

### 4.3 Join-helper level (declared in `JoinEntityHelper`)

**ReadableJoinEntityHelper**: `loadJoinEntities` (×14), `loadAllJoinEntities` (×6), `loadJoinEntitiesIfAbsent` (all).
**DeletableJoinEntityHelper**: `deleteJoinEntities` (×9).
(There is **no** `saveJoinEntities` in the base — joins have only load + delete.)

### 4.4 Compound operations — the key constraint

`upsert` / `batchUpsert` are **compound**: their default bodies call *both*
`save`/`insert` *and* `update` (e.g. `Dao.upsert(T, Condition)` at
`Dao.java:2447-2466` calls `save(entity)` then `update(...)`; `CrudDao.upsert`
calls `get` + `insert` + `update`). They therefore cannot live inside a single
capability whose siblings they can't see.

**Rule:** compound methods stay **declared directly in the composed interface**
that owns all required capabilities (`Dao` / `CrudDao`), *not* in a capability.
Because `NoUpdateDao`/`ReadOnlyDao` will no longer `extend Dao` (they compose
capabilities instead — §5), they simply **don't inherit** `upsert`/`batchUpsert`
— no throw stub needed. Same treatment for the "full-Dao-only" prepares
(`prepareQuery(..., stmtCreator)`, `prepareNamedQuery(..., stmtCreator)`,
`prepareCallableQuery(...)`), which must be absent from `NoUpdateDao` anyway.

## 5. The exact `extends` graph

`R/I/U/D` = Readable/Insertable/Updatable/Deletable. New capability interfaces
in **bold**. F-bound follows the existing `JoinEntityHelper` pattern
(`<T, TD extends Dao<T, TD>>`).

### 5.1 New capability interfaces (checked)

```
ReadableDao<T, TD extends Dao<T,TD>>
InsertableDao<T, TD extends Dao<T,TD>>
UpdatableDao<T, TD extends Dao<T,TD>>
DeletableDao<T, TD extends Dao<T,TD>>

ReadableCrudDao<T, ID, TD extends CrudDao<T,ID,TD>>   extends ReadableDao<T,TD>
InsertableCrudDao<T, ID, TD extends CrudDao<T,ID,TD>> extends InsertableDao<T,TD>
UpdatableCrudDao<T, ID, TD extends CrudDao<T,ID,TD>>  extends UpdatableDao<T,TD>
DeletableCrudDao<T, ID, TD extends CrudDao<T,ID,TD>>  extends DeletableDao<T,TD>

ReadableJoinEntityHelper<T, TD extends Dao<T,TD>>
DeletableJoinEntityHelper<T, TD extends Dao<T,TD>>
```

### 5.2 Composed convenience interfaces

These are now **pure capability bundles** — thin `interface X extends <caps> {}`
with empty bodies, except `Dao`/`CrudDao` which additionally declare the compound
methods (§4.4) and the runtime-checked prepares (§6, unless Option B is taken).
With BC waived they are *sugar*, not contracts: a user is now free to compose
capabilities directly (e.g. `extends ReadableCrudDao<...>, InsertableCrudDao<...>`)
for combinations the old tree could not express. Crucially, `ReadOnlyDao` **no
longer extends `NoUpdateDao`** — that old subtype edge was a Liskov violation
(a read-only DAO can't stand in for a no-update one, which has `save`) papered
over by throw-stubs. The capability model removes it cleanly.

```
Dao            = R + I + U + D            (+ compound: upsert/batchUpsert, stmtCreator/callable prepares)
NoUpdateDao    = R + I + Cacheable        (§7)
ReadOnlyDao    = R + Cacheable            (§7)   // NOT a NoUpdateDao anymore

CrudDao        = Dao + R_crud + I_crud + U_crud + D_crud   (+ compound upsert/batchUpsert(ID))
NoUpdateCrudDao = NoUpdateDao + R_crud + I_crud
ReadOnlyCrudDao = ReadOnlyDao + R_crud

CrudDaoL          extends CrudDao<T,Long,TD>
NoUpdateCrudDaoL  extends NoUpdateCrudDao<T,Long,TD>, CrudDaoL<T,TD>
ReadOnlyCrudDaoL  extends ReadOnlyCrudDao<T,Long,TD>, NoUpdateCrudDaoL<T,TD>

JoinEntityHelper          = ReadableJoinEntityHelper + DeletableJoinEntityHelper
ReadOnlyJoinEntityHelper  = ReadableJoinEntityHelper        (delete stubs gone)
CrudJoinEntityHelper      extends JoinEntityHelper          (+ id-based join reads)
ReadOnlyCrudJoinEntityHelper extends ReadOnlyJoinEntityHelper, CrudJoinEntityHelper-read-part
```

### 5.3 Unchecked mirror

Each capability gets an `Unchecked*` twin that re-declares the same methods
**without** `throws SQLException` (this is the existing reason the Unchecked
tree exists — it is *not* eliminated by this work):

```
UncheckedReadableDao / UncheckedInsertableDao / UncheckedUpdatableDao / UncheckedDeletableDao
UncheckedReadableCrudDao / ... / UncheckedDeletableCrudDao
UncheckedReadableJoinEntityHelper / UncheckedDeletableJoinEntityHelper

UncheckedDao         = UncheckedR + UncheckedI + UncheckedU + UncheckedD,  Dao
UncheckedNoUpdateDao = UncheckedR + UncheckedI,                            NoUpdateDao
UncheckedReadOnlyDao = UncheckedR,                                         ReadOnlyDao
   ... (Crud / L analogues)
```

Diamond re-resolution: a composed Unchecked interface still inherits a default
from both its checked counterpart (e.g. `NoUpdateDao`) and the unchecked
capability (e.g. `UncheckedReadableDao`). Where both supply a body for the same
signature, the composed interface re-overrides — **but only for the methods it
actually keeps** (reads/inserts), never for forbidden ops, because forbidden ops
are no longer present on either branch. This collapses the
`Unchecked*ReadOnly/NoUpdate` re-resolution sets dramatically.

### 5.4 Before / after counts

| Interface | UOE stubs before | after (capability) | after (capability + §6 centralization) |
|---|---|---|---|
| ReadOnlyDao | 24 | ~6 (read-prepare runtime checks) | **0** |
| NoUpdateDao | 27 | ~15 (read+insert-prepare runtime checks) | **0** |
| ReadOnlyCrudDao | 9 | **0** | **0** |
| NoUpdateCrudDao | 21 | **0** | **0** |
| ReadOnlyJoinEntityHelper | 16 | **0** | **0** |
| Unchecked* mirror | 65 | small (read-prepare only) | **0** |

## 6. The `prepareQuery` problem (the one thing the split can't fix)

A read-only DAO *legitimately has* `prepareQuery(String)` — it must accept
`SELECT` strings but reject everything else **at runtime**. That is "same
signature, narrower runtime contract," which interface inheritance cannot
express. So `ReadableDao.prepareQuery(String)` (and the other read-form prepares)
remain runtime-checked in `ReadOnlyDao`/`NoUpdateDao` *unless* the check moves
into the proxy.

The write-oriented prepares are fine: generated-key overloads → `InsertableDao`
(absent from `ReadOnlyDao`, so no stub); `stmtCreator`/`callable` →
composed-`Dao`-only (absent from `NoUpdateDao`, so no stub).

**Option A (capability split only):** keep the ~6-15 read-form prepare overrides
as runtime-checked defaults in `NoUpdateDao`/`ReadOnlyDao`. Simple, localized.

**Option B (recommended companion): centralize the SQL-kind check in `DaoImpl`.**
The proxy already knows the DAO's restriction level. Wrap the `prepareQuery`/
`prepareNamedQuery`/`*ForLargeResult` handlers so that, when the target interface
is `ReadOnlyDao`/`NoUpdateDao` (via the §7 marker), the SQL is validated with the
existing `DaoUtil.isReadOnlyQuery/isNoUpdateQuery` before delegating. Then
`ReadableDao` declares plain prepares with **zero** per-interface overrides — and
*all* prepare boilerplate disappears (last column of §5.4). This is independent
of the split and could even ship first.

## 7. The `Cacheable` marker change (spelled out)

**Problem.** Caching is gated on the `NoUpdateDao` *type* at three sites:

* `DaoImpl.java:2241` — `if (NoUpdateDao.class.isAssignableFrom(daoInterface) || UncheckedNoUpdateDao.class.isAssignableFrom(daoInterface))` — gate for `@CacheResult`/`@RefreshCache` on the class.
* `DaoImpl.java:2275` — `if (NoUpdateDao.class.isAssignableFrom(daoInterface))` — gate for `@Cache` / programmatic cache.
* `DaoImpl.java:6386` — `if ((isAnnotatedRefreshResult || isAnnotatedCacheResult) && !NoUpdateDao.class.isAssignableFrom(daoInterface))` — per-method gate.

Caching works on read-only DAOs **today only because `ReadOnlyDao extends NoUpdateDao`.**
In the new model `ReadOnlyDao = ReadableDao` and does **not** extend
`NoUpdateDao`, so these checks would silently reject caching on read-only DAOs —
a regression.

**Fix.** Introduce a marker and gate on it instead of the concrete type.

```java
// new file: dao/Cacheable.java
package com.landawn.abacus.jdbc.dao;
import com.landawn.abacus.annotation.Beta;
/** Marker: DAO interfaces for which result caching is permitted
 *  (i.e. no UPDATE/DELETE side effects). */
@Beta
public interface Cacheable { }
```

Make the *composed* read-restricted interfaces extend it (capabilities stay clean):

```
NoUpdateDao extends ReadableDao, InsertableDao, Cacheable
ReadOnlyDao extends ReadableDao,                Cacheable
UncheckedNoUpdateDao extends ..., Cacheable
UncheckedReadOnlyDao extends ..., Cacheable
```

Then change the three gates:

```java
// DaoImpl.java:2241
if (Cacheable.class.isAssignableFrom(daoInterface)) {            // was NoUpdateDao || UncheckedNoUpdateDao
// DaoImpl.java:2275
if (Cacheable.class.isAssignableFrom(daoInterface)) {            // was NoUpdateDao
// DaoImpl.java:6386
... && !Cacheable.class.isAssignableFrom(daoInterface)) {        // was !NoUpdateDao
```

Also update the two error messages and the `@Cache` javadoc (and the memory note
"Cache is only supported for NoUpdateDao") to read "Cacheable DAOs
(NoUpdate/ReadOnly and their Unchecked variants)". Imports of `NoUpdateDao`/
`UncheckedNoUpdateDao` in `DaoImpl` can be dropped if unused afterward.

Net behavioral change: caching becomes *available* on `ReadOnlyDao` (it already
is, transitively) and the gate is now semantic rather than tied to one type —
strictly an improvement.

## 8. Backward compatibility — out of scope

BC is **waived** for this work, so the prior constraints no longer apply and the
design is freed accordingly:

* **No name/arity preservation required.** Composed interfaces in §5.2 are kept
  only because they're useful entry points, not for compatibility. They may be
  renamed or dropped if a cleaner naming emerges during implementation.
* **Removed write methods → compile errors is the goal.** Code that called
  `readOnlyDao.save(x)` and caught the runtime UOE will fail to compile; that's
  the intended fail-fast upgrade, documented in release notes, not mitigated.
* **`@Deprecated` throw-stubs are deleted outright** rather than retained as a
  migration shim.
* **Liskov edge removed.** `ReadOnlyDao` no longer extends `NoUpdateDao` (§5.2);
  any code relying on that assignability is expected to break and be fixed.
* **New ability unlocked:** because capabilities are now first-class, users can
  declare arbitrary combinations (e.g. insert-only-no-read) that the old fixed
  tree could not express. `DaoImpl` generates handlers per method over
  `allInterfaces`, so arbitrary capability composition works as long as the
  `T`/`ID` type arguments remain resolvable.

## 9. Cost / risk summary

| Dimension | Effect |
|---|---|
| Throw-stub LOC | −~165 methods (≈ −3,000–4,000 lines incl. javadoc) |
| Total interface count | **+~16–26** capability interfaces (checked + unchecked + crud) — *up*, even as per-file size drops |
| Forbidden-call failure mode | runtime UOE → **compile error** ✅ |
| Generics complexity | high — F-bounded `<T,(ID,)TD>` across many small interfaces; diamond resolution still needed for kept methods |
| Proxy impact | none for the split itself; **moderate** if Option B (centralized prepare check) is taken |
| Cache gating | requires the §7 `Cacheable` marker (3 sites + messages) |
| Public-API risk | **N/A — BC waived** (§8); write calls on restricted refs become compile errors by design |
| Build/test | full `mvn test` (3289 cases) + `javadoc:javadoc` must stay green; new tests for compile-time exclusion are not possible in-suite (they're *negative* compile checks) — verify via samples module |

## 10. Recommendation & phasing

The model is sound and the proxy supports it. With BC waived, a **single clean
cut** (all phases at once, deleting every throw-stub file's stub bodies) is now
viable and avoids carrying a half-migrated tree. Phasing is therefore optional —
recommended only to keep each PR reviewable and the suite green at every step.
Smallest blast radius first:

1. **Phase 0 — `Cacheable` marker (§7).** Pure internal refactor, no API change,
   unblocks the rest. Low risk.
2. **Phase 1 — `ReadableJoinEntityHelper` / `DeletableJoinEntityHelper`.**
   Eliminates all 32 `deleteJoinEntities` stubs (checked + unchecked), no
   `prepareQuery` complication. Cleanest standalone win; good proof-of-concept.
3. **Phase 2 — entity-op capabilities in `Dao`/`CrudDao`** (`Insertable*`,
   `Updatable*`, `Deletable*`; `Readable*` is the remainder). Removes the
   save/insert/update/upsert/delete stubs (~110) and the entity-op diamond
   re-resolution. Depends on Phase 0.
4. **Phase 3 (optional) — Option B prepare centralization (§6).** Removes the
   residual read-form prepare overrides; the only thing that gets to *zero* stubs.
   Independent of Phases 1-2; can be done any time.

If the appetite is only for the highest-ROI slice: **Phase 0 + Phase 1 + Phase 3**
removes the join stubs *and* every prepare stub with minimal generics churn, and
leaves the (larger but mechanical) entity-op split (Phase 2) as a follow-up.

## 11. Implementation status

**Phase 0 (`Cacheable` marker) — DONE.** New `dao/Cacheable.java`; `NoUpdateDao extends
Cacheable`; the three `DaoImpl` cache gates (was `2241/2275/6386`) now test
`Cacheable.class.isAssignableFrom(daoInterface)` with updated messages; the
`NoUpdateDao`/`UncheckedNoUpdateDao` imports in `DaoImpl` were dropped. Behavior
preserved (cache still enabled exactly on NoUpdate/ReadOnly + Unchecked).

**Phase 1 (join-helper read/delete split) — DONE.** All 32 `deleteJoinEntities`/
`deleteAllJoinEntities` throw-stubs (16 checked in `ReadOnlyJoinEntityHelper` + 16
unchecked in `UncheckedReadOnlyJoinEntityHelper`) are eliminated; calling them on a
read-only join DAO is now a **compile error**.

New interfaces (6): `ReadableJoinEntityHelper`, `DeletableJoinEntityHelper`
(`extends ReadableJoinEntityHelper`), `ReadableCrudJoinEntityHelper`, and the
`Unchecked*` twins `UncheckedReadableJoinEntityHelper`,
`UncheckedDeletableJoinEntityHelper`, `UncheckedReadableCrudJoinEntityHelper`.

Re-parented composites: `JoinEntityHelper = Readable + Deletable`;
`CrudJoinEntityHelper = ReadableCrud + JoinEntityHelper`;
`UncheckedJoinEntityHelper = UncheckedReadable + UncheckedDeletable + JoinEntityHelper`;
`UncheckedCrudJoinEntityHelper = UncheckedReadableCrud + UncheckedJoinEntityHelper + CrudJoinEntityHelper`.
Re-parented read-only variants to extend only the `Readable*` side.

Supporting changes:
* `DaoUtil.getDao`/`getCrudDao` parameter types widened to the `Readable*` roots
  (`ReadableJoinEntityHelper`, `ReadableCrudJoinEntityHelper`, and unchecked twins),
  since `Deletable extends Readable` and every read-only variant is a `Readable*`.
* `DaoImpl` join detection switched from `JoinEntityHelper`/`CrudJoinEntityHelper`
  `isAssignableFrom` to `ReadableJoinEntityHelper`/`ReadableCrudJoinEntityHelper`
  (the new common roots that *all* join DAOs — full and read-only — share); the
  abstract-join-method dispatch guard (`declaringClass.equals(...)`) was broadened to
  the four new `Readable*`/`Deletable*` declaring classes.

Deviations from §5: the `Deletable*` interfaces `extend Readable*` (rather than being
fully orthogonal) — there is no delete-only join use case, and this avoids duplicating
the four internal accessor methods and keeps the `getDao` helpers single-overload. The
read-only `*L` variants extend `ReadOnlyCrudJoinEntityHelper<…,Long,…>` only and so drop
the primitive-`long` `get(long, …)` convenience overloads; these still resolve via
autoboxing to the `Long` overloads, so it is behavior-preserving for callers.

Verification: `mvn clean test` → **3331 tests, 0 failures/0 errors**; `mvn javadoc:javadoc`
→ BUILD SUCCESS. ~24 obsolete tests (asserting the old hierarchy / runtime-UOE delete
behavior) were updated to assert the new capability structure via reflection.

**Phase 2 (entity-op split in `Dao`/`CrudDao`) — DONE.** All entity-operation throw-stubs
are eliminated: `ReadOnlyCrudDao`/`NoUpdateCrudDao` and **every** `Unchecked*` restricted
variant now carry **0** stubs (was 21/21/9/9/3/3); `ReadOnlyDao` (24→**6**) and
`NoUpdateDao` (27→**15**) retain only the runtime-checked read/insert `prepareXxx`
overrides (Option A; Phase 3 would centralize those to reach 0). `save`/`insert`/`update`/
`upsert`/`delete`/`batchXxx` and `prepareCallableQuery`/stmtCreator-prepares on a
restricted DAO are now **compile errors**.

New interfaces (18): condition-level `ReadableDao`/`InsertableDao`/`UpdatableDao`/
`DeletableDao` + unchecked twins; CRUD-level `ReadableCrudDao`/`InsertableCrudDao`/
`UpdatableCrudDao`/`DeletableCrudDao` + unchecked twins; and the `Long`-id markers
`ReadableCrudDaoL`/`UncheckedReadableCrudDaoL`.

Key structural decisions:
* Capability self-type bound is `TD extends ReadableDao<T,TD>` (not `Dao<T,TD>` as
  sketched in §5.1) — required so `ReadOnlyDao`/`NoUpdateDao` (whose self-type is not a
  `Dao`) can extend the capabilities. `Insertable/Updatable/Deletable extend ReadableDao`
  (and CRUD twins extend their condition twin), matching the Phase 1 join precedent, so
  the shared internal accessors live once in `ReadableDao`.
* `Dao = Insertable + Updatable + Deletable` (+ compound `upsert`/`batchUpsert` and
  stmtCreator/callable prepares kept in `Dao`); `NoUpdateDao = InsertableDao + Cacheable`;
  `ReadOnlyDao = ReadableDao + Cacheable` (no longer a `NoUpdateDao` — the old Liskov
  edge is gone). `CrudDao = Dao + the four *CrudDao caps`; `NoUpdateCrudDao = NoUpdateDao
  + ReadableCrudDao + InsertableCrudDao`; `ReadOnlyCrudDao = ReadOnlyDao + ReadableCrudDao`.
* Restricted `*L` variants extend the empty `ReadableCrudDaoL`/`UncheckedReadableCrudDaoL`
  marker (not the full `CrudDaoL`), dropping primitive-`long` convenience (boxing covers it).

`DaoImpl`/`JdbcUtil` supporting changes: `createDao` bound and
`JdbcUtil.getIdGeneratorGetterSetter` widened `Dao` → `ReadableDao`; `isUncheckedDao`
→ `UncheckedReadableDao`, `isCrudDao` → `ReadableCrudDao`, `isCrudDaoL` →
`ReadableCrudDaoL`, the entity-class type-arg extraction (`Dao`/`UncheckedDao`
`isAssignableFrom`) and the join-helper-requires-Dao check → `ReadableDao`; the two
`declaringClass.equals(Dao.class)`/`CrudDao.class` proxy-dispatch guards broadened to the
10 condition-level / 10 CRUD-level capability classes; the proxy invoker plumbing
(`Throwables.BiFunction<Dao,…>`, `(Dao) proxy` cast, `daoPool`/`joinEntityDaoPool`,
`getApplicableDaoForJoinEntity`) retyped `Dao` → `ReadableDao` (the universal proxy root,
since a read-only proxy no longer implements `Dao`); internal CRUD-insert handlers route
generated-key prepares through `JdbcUtil.prepareNamedQuery(proxy.dataSource(), …)` and
cast `(ReadableCrudDao) proxy` for `generateId()`.

Verification: `mvn clean test-compile` clean; `mvn clean test` → **3268 tests, 0
failures/0 errors** (count dropped from 3331 as ~63 obsolete runtime-UOE restriction
tests became compile-time-enforced and were removed); `mvn javadoc:javadoc` → BUILD
SUCCESS.

*Test-suite flakiness note:* one clean run intermittently hit `NoClassDefFoundError`
for `JdbcUtil`/`DaoUtil` inside `CrudDaoTest`/`JoinEntityHelperTest` `refresh`/`stream`/
`loadJoinEntitiesIfAbsent` tests — a pre-existing Mockito `mockStatic` +
`CALLS_REAL_METHODS` instrumentation-dispatch fragility (the real default method, now in
`ReadableCrudDao`/`ReadableJoinEntityHelper`, is invoked via Mockito's
`InstrumentationMemberAccessor`, whose dispatcher classloader can't always resolve the
mocked-static class). It is **order-sensitive and non-deterministic** (passed on rerun;
`CrudDaoTest` passes in isolation), surfaced only because the refactor changed suite
discovery order. Not a product regression — `refresh`/`stream`/join-load work unchanged
in real DAO proxies. Stabilizing these mock-heavy tests is tracked separately.

**Phase 3 (centralize the `prepareQuery` SQL-kind check) — DONE.** The residual runtime-checked
`prepareXxx` overrides are gone: `ReadOnlyDao` (6) and `NoUpdateDao` (15) are now **empty capability
composites** (`ReadOnlyDao = ReadableDao + Cacheable`, `NoUpdateDao = InsertableDao + Cacheable`),
so **every** restricted DAO interface in the tree now carries **0** stubs (the original ~165 are
fully eliminated).

The SELECT-only / SELECT+INSERT gate moved into the `DaoImpl` proxy: at proxy creation it computes
`isReadOnlyDao = ReadOnlyDao.class.isAssignableFrom(daoInterface)` and
`isNoUpdateDao = !isReadOnlyDao && NoUpdateDao.class.isAssignableFrom(daoInterface)`; the
default-method dispatch then gates the `prepareQuery`/`prepareNamedQuery`/`*ForLargeResult` overloads
whose first arg is a raw `String`/`ParsedSql` — `ParsedSql` null → `IllegalArgumentException` (mirrors
the old `checkArgNotNull`), then `DaoUtil.isReadOnlyQuery`/`isNoUpdateQuery` → `UnsupportedOperationException`
with the original messages. The `Condition`/`Collection`-based prepare *builders* (always SELECT) are
intentionally excluded. To let `DaoImpl` (package `com.landawn.abacus.jdbc`) reuse the hardened
tokenizer, `dao.DaoUtil` was promoted to `public` (still `@Internal`) with `isReadOnlyQuery`/
`isNoUpdateQuery` made `public` (all other members stay package-private).

Because the gate now lives in the proxy (not the interface defaults), the former `Mockito.mock(...,
CALLS_REAL_METHODS)` + `mockStatic` unit tests of `NoUpdateDaoTest`/`ReadOnlyDaoTest` could no longer
exercise it; both were rewritten to drive a real `createDao` proxy (mock `DataSource`) and assert
allowed SELECT/INSERT vs rejected UPDATE/DELETE/CTE/`SELECT INTO`, plus `ParsedSql` null → IAE.

Verification (Phase 3): `mvn clean test` → **3255 tests, 0 failures/0 errors** (count vs Phase 2's
3268: ~21 mock-based prepare tests replaced by ~9 proxy-based gate tests); `mvn javadoc:javadoc` →
BUILD SUCCESS.

All three phases are now implemented; the capability redesign is complete.

## 12. Post-implementation review & test hardening

A full behavior-preservation review was performed across all phases (the hierarchy has since also
been `sealed`/`non-sealed` with explicit `permits` clauses — compiles clean):

* **No functional regressions found.** The centralized prepare-gate fires for exactly the prepare
  overloads the old per-interface overrides covered (raw `String`/`ParsedSql` first arg;
  `Condition`/`Collection` SELECT-builders excluded), preserves `ParsedSql` null → `IllegalArgumentException`
  and bad-SQL → `UnsupportedOperationException` with identical messages; `isReadOnly`/`isNoUpdate` are
  derived from `daoInterface` and disjoint. The detection retypings (`UncheckedReadableDao`/
  `ReadableCrudDao`/`ReadableCrudDaoL`) select the same DAO sets as the old `Dao`/`CrudDao`/`CrudDaoL`
  checks, and the `Dao` → `ReadableDao` proxy-plumbing/cast retyping is *required* (a read-only proxy
  no longer implements `Dao`), not merely neutral.
* **Tests added** for the new surface: `CapabilityGatingTest` (Cacheable marker structure; cache
  permitted on ReadOnly/NoUpdate DAOs but rejected on a full `Dao` with message check; CRUD-level
  prepare-gate via real `createDao` proxies for ReadOnly/NoUpdate/full; capability composition graph);
  `DaoUtilTest` (the now-public `isReadOnlyQuery`/`isNoUpdateQuery` gates incl. UPDATE/DELETE/MERGE,
  upsert clauses, `INSERT OVERWRITE`, `SELECT INTO`, mutating CTE, literal-`'DELETE'`, null);
  `NoUpdateDaoTest`/`ReadOnlyDaoTest` already rewritten in Phase 3 to drive real proxies.
* **Javadoc** reviewed: the restricted interfaces (`ReadOnlyDao`/`NoUpdateDao`) and markers
  (`Cacheable`/`ReadableCrudDaoL`) were given accurate class docs; the generated capability interfaces
  carry concise accurate class docs with `@param`/`@see`; `mvn javadoc:javadoc` → BUILD SUCCESS.

Verification: `mvn clean test` → **3265 tests, 0 failures/0 errors**; `mvn javadoc:javadoc` →
BUILD SUCCESS.
