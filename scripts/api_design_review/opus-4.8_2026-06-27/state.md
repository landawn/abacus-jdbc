# API Design Review — State

- MODEL: opus-4.8
- DATE: 2026-06-27
- EXECUTION: PARALLEL (one sub-agent per large class to surface candidate findings;
  orchestrator verifies EVERY finding against source by reading the cited lines,
  then authors the final per-class report). Small classes reviewed directly by orchestrator.
- TASK: REPORT-ONLY. No source modifications.
- Output dir: ./scripts/api_design_review/opus-4.8_2026-06-27/

## Scope = PUBLIC top-level types in package com.landawn.abacus.jdbc

Package-private files EXCLUDED (no public API surface; not user-facing):
- DaoImpl.java          (package-private; internal DAO factory/impl — surface exposed via JdbcUtil.createDao)
- JdbcSettings.java     (package-private)
- ResultSetProxy.java   (package-private)
- SqlExecutor.java      (package-private; archived/legacy)
- SqlLogConfig.java     (package-private)

In-scope PUBLIC classes (22):
| Class                       | Lines  | Kind                                    | Status |
|-----------------------------|--------|-----------------------------------------|--------|
| JdbcUtil                    | 12082  | public final util                       | done   |
| AbstractQuery               | 10320  | public abstract (fluent base)           | done   |
| Jdbc                        | 7036   | public final (container of nested ifaces)| done  |
| NamedQuery                  | 4365   | public final extends AbstractQuery      | done   |
| CallableQuery               | 3749   | public final extends AbstractQuery      | done   |
| JdbcUtils                   | 3410   | public final util                       | done   |
| JdbcCodeGenerationUtil      | 2419   | public final util                       | done   |
| JoinInfo                    | 1345   | public final                            | done   |
| SqlTransaction              | 1116   | public final implements Transaction     | done   |
| DBLock                      | 705    | public final                            | done   |
| cs                          | 445    | public final (param-name constants)     | done   |
| OP                          | 308    | public enum                             | done   |
| Transaction                 | 291    | public interface                        | done   |
| DBVersion                   | 256    | public enum                             | done   |
| IsolationLevel              | 251    | public enum                             | done   |
| FetchDirection              | 187    | public enum                             | done   |
| OnDeleteAction              | 171    | public enum                             | done   |
| Propagation                 | 103    | public enum                             | done   |
| SpringApplicationContext    | 135    | public final util                       | done   |
| EmptyHandler                | 69     | public final (@Internal no-op)          | done   |
| PreparedQuery               | 54     | public final extends AbstractQuery      | done   |
| DBProductInfo               | 51     | public record                           | done   |

## Relationship map

- Query fluent hierarchy (intentional API mirroring / inheritance — NOT duplication):
  - AbstractQuery<Stmt, This> — abstract fluent base; self-type `This` for chaining; AutoCloseable.
  - PreparedQuery extends AbstractQuery<PreparedStatement, PreparedQuery> — adds NOTHING public (only pkg-private ctor). Pure positional.
  - NamedQuery extends AbstractQuery<PreparedStatement, NamedQuery> — adds named-parameter setters (setXxx(String name, ...)).
  - CallableQuery extends AbstractQuery<CallableStatement, CallableQuery> — adds named-param setters AND register-out-parameter + out-param getters.
  - NamedQuery and CallableQuery do NOT extend each other; their named-parameter setter families MIRROR each other by design (judge duplication accordingly).
- SqlTransaction implements Transaction (public interface). SqlTransaction is created/managed by JdbcUtil.beginTransaction.
- Jdbc = container of public nested functional interfaces (RowMapper, BiRowMapper, ResultExtractor, BiResultExtractor, RowFilter, BiRowFilter, RowConsumer, BiRowConsumer, ColumnGetter, Columns.ColumnOne/ColumnGetterByIndex, ParametersSetter, BiParametersSetter, TriParametersSetter, Handler, HandlerFactory, DaoCache, etc.). EmptyHandler implements Jdbc.Handler<Dao>.
- JdbcUtil = primary static facade (prepareQuery/prepareNamedQuery/prepareCallableQuery factories, executeQuery/executeUpdate, beginTransaction, createDao, stream, blob/clob helpers, etc.). Delegates DAO creation to DaoImpl.
- JdbcUtils = bulk import/export/copy/parse static utilities (distinct from JdbcUtil).
- JdbcCodeGenerationUtil = static codegen for entity/DAO source.
- JoinInfo = reflection/metadata holder for @JoinedBy entity relationships; used by DAO join helpers.
- DBLock = DB-table-based distributed lock.
- Enums (OP, DBVersion, IsolationLevel, FetchDirection, OnDeleteAction, Propagation): each typically wraps a JDBC/int constant; review value()/intValue() accessor naming consistency across them.
- DBProductInfo = record(productName, productVersion, DBVersion).
- cs = utility class of public static final String parameter-name constants (used with N.checkArgNotNull(x, cs.x)).
- SpringApplicationContext = static Spring ApplicationContext bridge.

## Per-class status: ALL DONE

## Cross-class deliverables
- cross_class_notes.md  : running notes — DONE
- SUMMARY.txt           : final cross-class summary — DONE
