# Named batch allocation probe

`BatchBindingAllocationProbe.java` preserves the standalone allocation experiment for wide named batches. It calls the actual `JdbcUtil.prepareBatchStmt` path with 64 distinct names and 1,000 map rows per batch. After 30 warmup batches, each of three samples covers 10 batches (10,000 rows).

The connection and statement are no-op JDBC proxies. Every row is bound and added to a batch, but no database or network operation runs. Results measure current-thread allocated bytes for batch preparation, including proxy invocation overhead; they are **not database throughput, latency, retained heap, or an allocation-free binding claim**. Input construction is outside the measurement, and the 1,000 rows share the same preconstructed map, as in the original experiment. Functional tests in `JdbcUtilBatchTest` separately cover different row values, repeated names and all four batch routes.

Run from the repository root using JDK 21 or later with `com.sun.management.ThreadMXBean` allocated-memory support. First compile the project normally and produce its dependency classpath, for example:

```powershell
mvn -DskipTests compile dependency:build-classpath "-Dmdep.outputFile=target/benchmark-classpath.txt"
$probeClasspath = 'target/classes;' + (Get-Content target/benchmark-classpath.txt -Raw).Trim()
javac -proc:none -cp $probeClasspath -d target/benchmark-classes tools/benchmarks/BatchBindingAllocationProbe.java
java -cp ('target/benchmark-classes;' + $probeClasspath) com.landawn.abacus.jdbc.BatchBindingAllocationProbe
```

On a POSIX shell, use `:` instead of `;` as the classpath separator:

```sh
mvn -DskipTests compile dependency:build-classpath -Dmdep.outputFile=target/benchmark-classpath.txt
probe_classpath="target/classes:$(cat target/benchmark-classpath.txt)"
javac -proc:none -cp "$probe_classpath" -d target/benchmark-classes tools/benchmarks/BatchBindingAllocationProbe.java
java -cp "target/benchmark-classes:$probe_classpath" com.landawn.abacus.jdbc.BatchBindingAllocationProbe
```

Compare revisions in separate runs with the same JDK, dependencies, JVM options and machine conditions. Keep the warmup and workload unchanged; inspect all samples instead of enforcing a timing or allocation threshold in functional tests. The probe is outside the default Maven source/test trees and does not change project build or runtime settings.
