/*
 * Copyright (c) 2021, Haiyang Li.
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
package com.landawn.abacus.jdbc.annotation;

/**
 * Defines how DAO results are copied when stored in or retrieved from a cache.
 *
 * <p>The copying strategies bypass serialization for top-level values implementing
 * {@link com.landawn.abacus.util.Immutable}, except collections, maps and populated optional/nullable
 * wrappers whose contents may need copying. Other {@code Immutable} values are shared as-is; their contents
 * must be deeply immutable to avoid sharing mutable state. Values without this marker are copied even if
 * their Java types are immutable, except that {@link #JSON} shares {@code java.time} values as-is.</p>
 *
 * @see CacheResult#serialization()
 */
public enum CacheSerialization {
    /** Stores and returns direct object references without serialization. */
    NONE,

    /**
     * Uses Kryo to deep-copy cached values that require copying. Requires Kryo on the classpath and
     * successful initialization of the underlying parser. If it is unavailable, DAO creation still
     * succeeds and the failure surfaces at invocation time &mdash; the
     * first attempt to cache a value that needs copying throws {@code UnsupportedOperationException}
     * whose message quotes the failure and states whether Kryo is missing, one of its dependencies is missing,
     * or Kryo is present but its parser failed to initialize.
     *
     * <p>Library {@code ImmutableCollection}/{@code ImmutableList}/{@code ImmutableSet} and immutable map
     * families are copied with their elements, keys and values, including nested containers and references
     * back to the root. Their runtime types and backing-container equality policies are retained. Shared
     * references and cycles follow Kryo's copying rules. Sorted containers retain their comparator instance,
     * as Kryo's standard sorted-container serializers do; {@code BiMap} retains its map-factory instances.
     * The JDK's {@code Collections.unmodifiable*} wrappers, {@code Collections.newSetFromMap} sets, and
     * {@code subList}, {@code headMap}/{@code subMap}/{@code tailMap} and {@code descendingMap} views are
     * adapted the same way, so they are copied as views over their copied backing containers rather than
     * handed to Kryo's field serializer.</p>
     *
     * <p>The immutable-wrapper adapter requires Kryo's {@code sun.misc.Unsafe} support, without additional
     * module-open flags. Both Kryo's Unsafe setting and the JVM's permission to perform Unsafe memory-access
     * operations are required. Disabling Kryo's support or denying those operations, for example with
     * {@code --sun-misc-unsafe-memory-access=deny}, makes adapted-wrapper copying throw
     * {@code UnsupportedOperationException}. The adapter checks these capabilities lazily and retains the
     * cause when an operation is rejected. The underlying parser may also report Kryo unavailable because
     * its own initialization fails under the runtime settings; ordinary copying retains that parser's
     * existing requirements. Custom application
     * serializers take precedence. Class/ID-only registrations of library immutable wrappers are supported;
     * explicitly registered application subclasses need a suitable application serializer. Other object
     * types and backing containers must be supported by Kryo.</p>
     */
    KRYO,

    /**
     * Uses JSON to deep-copy cached values that require copying.
     *
     * <p>The top-level copy is rebuilt as the value's runtime class, using the element, key/value or wrapped types
     * declared by the DAO method's generic return type (for example {@code List<User>},
     * {@code Map<Long, User>} or {@code u.Optional<User>}). Where the declared return type does not resolve these
     * types beyond {@code Object} (for example, it uses a type variable), the value held by an optional/nullable
     * wrapper uses its runtime class, and collection elements and map keys/values use their common runtime class
     * when all non-null values have the same class. Otherwise the unresolved types fall back to the serializer's
     * defaults; nested generic type arguments cannot be recovered from runtime classes alone.</p>
     *
     * <p>A {@code Dataset} is copied together with its column types (the common class of each column's non-null
     * values). Other values typed only as {@code Object}, such as the values of a {@code Map<String, Object>} row,
     * are read back with the JSON parser's default types (for example, a {@code Long} id may come back as an
     * {@code Integer}) unless the runtime-class fallback above applies to them. Use {@link #KRYO} or {@link #NONE}
     * when such values must keep their exact Java types.</p>
     *
     * <p>A {@code java.util.Date} keeps its milliseconds. One that is a map key inside a bean property, or a map
     * key whose key type does not resolve to a date type (for example in a nested, unsorted {@code Map<Object, V>}:
     * only the top-level map and sorted maps fall back to the keys' common runtime class), is still copied through
     * its string form, which has whole seconds only (an untyped key comes back as that {@code String}).</p>
     *
     * <p>A {@code java.sql.Timestamp}, a {@code Calendar} or a {@code java.time} value ({@code Instant},
     * {@code LocalDate}/{@code LocalTime}/{@code LocalDateTime}, {@code ZonedDateTime}, {@code OffsetDateTime},
     * {@code Duration}, ...) is copied exactly, keeping its nanos, time zone or offset and its runtime class, as the
     * result itself, as the value held by an optional/nullable wrapper, and as an element, map key or map value
     * anywhere in the result's array, collection, map or optional/nullable container tree, whatever the declared type
     * at that position (even {@code Object}): a {@code Timestamp} or {@code Calendar} is cloned, and a
     * {@code java.time} value, being immutable, is shared as-is. Values the JSON parser copies itself keep its date/time
     * handling: in a bean property (also inside a bean nested in that container tree) or among the values of a
     * {@code Dataset}, a {@code Calendar}, {@code ZonedDateTime}, {@code OffsetDateTime} or {@code Instant} is written
     * as epoch milliseconds, so it loses any sub-millisecond part, and a {@code Calendar}, {@code ZonedDateTime} or
     * {@code OffsetDateTime} comes back in the JVM default time zone; a {@code Timestamp} loses its sub-millisecond
     * nanos; and a {@code java.time} type the parser cannot write (for example {@code OffsetTime}, {@code Period} or
     * {@code YearMonth}) fails the copy. Use {@link #KRYO} or {@link #NONE} when such bean properties must be copied
     * exactly.</p>
     *
     * <p>Supported standard and library sorted sets and maps reached through the result's array, collection,
     * map or optional/nullable container tree retain their comparator instance while their contents are copied.
     * Supported library immutable container wrappers are retained when required by the declared type or the
     * root runtime type; plain {@code ImmutableSet}, {@code ImmutableMap} and {@code ImmutableBiMap} copies keep
     * the source's iteration order, as their own factories do. The comparator is shared as an ordering policy and must remain stable; its own mutable
     * state is not cloned. This also preserves comparator-distinct entries and
     * supports keys or elements that do not implement {@link Comparable}. Nested containers use their declared
     * generic types where available; unresolved comparison keys or elements use a common runtime type when
     * possible so that they remain compatible with the comparator.</p>
     *
     * <p>During comparator-preserving copying, custom concrete sorted-container classes use the JSON parser's
     * constructor rules and retain the comparator and other state initialized by their constructor. A fresh
     * comparator instance is allowed. After population, each copy checks its size and compares corresponding
     * source and copied keys/elements in iteration order using the source comparator (or natural ordering).
     * Detected changes in size, comparison keys or order, or an inability to verify the copied values, cause
     * {@code UnsupportedOperationException}. This validates the current contents, not comparator equivalence
     * for other values; copied values must be accepted by the source comparator. Arbitrary subclass fields
     * are not copied beyond the parser's existing behavior. Natural-order container trees without a custom
     * comparator or one of those plain immutable wrappers follow the ordinary JSON parser path.</p>
     *
     * <p>JSON writes map keys as strings. A {@code null} key of a map reached through the result's array,
     * collection, map or optional/nullable container tree is copied as a {@code null} key, rather than read back
     * as the String {@code "null"} (or rejected by a non-String key type such as {@code Integer}).</p>
     *
     * <p>Bean properties and other values handled directly by the JSON parser retain that parser's existing
     * behavior, including its limitations for custom comparators inside bean properties. JSON does not
     * preserve shared object identity, and cyclic result graphs are not supported. Use {@link #KRYO} when
     * those relationships or comparators inside arbitrary bean graphs must be retained.</p>
     */
    JSON
}
