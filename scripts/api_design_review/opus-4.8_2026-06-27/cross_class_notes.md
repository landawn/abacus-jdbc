# Cross-class consistency notes (consolidated, all source-verified)

## Conventions confirmed (baseline)
- Fluent self-type: AbstractQuery<Stmt,This> setters return `This`. Subclasses (Prepared/Named/Callable)
  return their own type. Consistent.
- Enum int-wrapper accessor convention = intValue() + valueOf(int): IsolationLevel (195/227),
  FetchDirection (184/146). OnDeleteAction diverges with value() (126) + get(String) (160) — but it is
  @Deprecated.
- Optional/Nullable is the library's "may be absent" return convention (AbstractQuery queryForXxx,
  findFirst, etc.). DBLock.lock() (null) and SpringApplicationContext.getBean (null) diverge.

## Verified cross-class observations
1. CASE-ONLY / ARITY-ONLY OVERLOAD DISAMBIGUATION (lambda-ambiguity workarounds):
   - AbstractQuery.foreach vs forEach (8925/8975 vs 8684+); settParameters vs setParameters (3140/3187).
   - Jdbc.HandlerFactory.create — two single-arg overloads disambiguated only by lambda arity (6614/6646).
   Same anti-pattern in two classes; readers/typos silently hit the wrong method.

2. NUMBERED MULTI-RESULT-SET METHODS accept only BiResultExtractor while single query() accepts both
   ResultExtractor and BiResultExtractor: AbstractQuery.query2/query3ResultSets (5493/5559) AND
   CallableQuery.query2/query3ResultSetsAndGetOutParameters (2822/2913).

3. NAMED-SETTER MIRROR DIVERGENCE NamedQuery vs CallableQuery (both extend AbstractQuery, mirror by design):
   - CallableQuery missing setObject SQLType overloads (NamedQuery 3630/3704), missing setParameters(Object)
     bean overload (NamedQuery 3924), missing setNString(String,CharSequence).
   - CallableQuery.setParameters(Object,List<String>) (1581) vs NamedQuery (...,Collection<String>) (4017).
   - NamedQuery.setNull(String,int)/setString(String,String) declare `throws SQLException` only, unlike the
     rest of its family (throws IAE,SQLException); CallableQuery's whole mirror declares SQLException only.

4. BIGINTEGER BINDING CLUSTER spans AbstractQuery/NamedQuery/CallableQuery:
   - setBigIntegerAsString(int,BigInteger) == setString(int,BigInteger) (AbstractQuery 1069/1170) — genuine
     duplicate, mirrored into Named/Callable.
   - setLong(int/String, BigInteger) — surprising (longValueExact, ArithmeticException) in all three.

5. INCOMPLETE FACTORY MATRICES (DataSource/Connection or output-target gaps):
   - JdbcUtil.prepareNamedQueryForLargeResult missing (Connection,ParsedSql) (4346/4380/4413).
   - JdbcUtil DDL/metadata helpers Connection-only while tableExists has both DS+Connection.
   - JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql DataSource-only (1831/1868).
   - JdbcUtils.exportCsv Writer side missing PreparedStatement / Connection+cols / DataSource+cols;
     importCsv Reader side missing the Connection overload.

6. ROW-COUNT WIDTH int vs long:
   - JdbcUtils.importData: int for Dataset (174+), long for File/Reader/Iterator (1016/1122/1290).
   - JdbcUtil.skip(ResultSet,long) returns int (1736).

7. DEPRECATION POLICY INCONSISTENCY (deprecated method's replacement still reachable un-deprecated):
   - AbstractQuery list(maxResult) 2-arg deprecated, 3-arg (filter,mapper,maxResult) not (6614/6679/6834 vs
     6749/6907); findFirst(filter) deprecated but list/forEach/count(filter) not; count() deprecated but
     count(filter) not.

8. DEAD / ORPHAN PUBLIC MEMBERS:
   - DBLock.UNLOCKED (123); JdbcCodeGenerationUtil S/SF (113/119), MIN_FUNC/MAX_FUNC (141/161),
     EntityCodeConfig.extendFieldNameTableClassName (2347); JoinInfo.setNullSqlAndParamSetterPool (143,
     built+never read).

9. DEPRECATED ALIASES READY FOR REMOVAL (BC relaxed): JoinInfo ×4 *SqlBuilderAndParamSetter (781/844/907/974),
   SqlTransaction runNotInMe/callNotInMe (963/1012), plus the many already-deprecated thin delegators in
   JdbcUtil/AbstractQuery/Jdbc.

10. CROSS-TYPE DEPRECATION GAP: SqlTransaction.rollback() @Deprecated (431) but Transaction.rollback() (165)
    is not, and the Transaction.Action example still calls rollback().

11. VOCABULARY DRIFT: JdbcUtil.getColumn2FieldNameMap ("Field") vs getXxxPropNames ("Prop"); param-name
    constants targetType/targetValueType/targetClass/cls/entityClass (cs.java) reflect real API drift.

12. MIRROR GAP within Jdbc: ResultExtractor has toDataset(×5)/toMergedList(×3)/toDatasetAndThen;
    BiResultExtractor has the TO_DATASET constant but none of those factories. RowMapper.toDisposableObjArray
    vs RowConsumer.oneOff name the same concept differently.
