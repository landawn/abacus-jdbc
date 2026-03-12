#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SRC_ROOT = ROOT / "src" / "main" / "java"
TEST_ROOT = ROOT / "src" / "test" / "java"

STUB_REASONS: dict[str, str] = {
    "com/landawn/abacus/jdbc/AbstractQuery.java": "Abstract query base class. The public API surface is exercised indirectly by concrete subclasses such as PreparedQuery and NamedQuery; direct unit tests here would require a large synthetic subclass matrix.",
    "com/landawn/abacus/jdbc/CallableQuery.java": "Stateful CallableStatement wrapper with a very large DB/driver-sensitive API surface. Add targeted behavioral tests when stored-procedure fixtures or stable integration scaffolding are available.",
    "com/landawn/abacus/jdbc/JdbcCodeGenerationUtil.java": "Code-generation utility with schema/file-system heavy workflows. Add targeted tests when stable metadata fixtures are available.",
    "com/landawn/abacus/jdbc/JdbcSettings.java": "Archived internal settings holder with no active public runtime API to exercise.",
    "com/landawn/abacus/jdbc/SqlExecutor.java": "Archived historical implementation retained only for reference, not for active runtime behavior.",
    "com/landawn/abacus/jdbc/dao/DaoUtil.java": "Internal DAO support utility with non-public entry points; direct tests would be artificial compared with coverage through the DAO/query integration surface.",
    "com/landawn/abacus/jdbc/annotation/Bind.java": "Metadata-only annotation type. Add reflection-based tests if annotation contract verification becomes necessary.",
    "com/landawn/abacus/jdbc/annotation/BindList.java": "Metadata-only annotation type. Add reflection-based tests if annotation contract verification becomes necessary.",
    "com/landawn/abacus/jdbc/annotation/Cache.java": "Metadata-only annotation type. Add reflection-based tests if annotation contract verification becomes necessary.",
    "com/landawn/abacus/jdbc/annotation/CacheResult.java": "Metadata-only annotation type. Add reflection-based tests if annotation contract verification becomes necessary.",
    "com/landawn/abacus/jdbc/annotation/DaoConfig.java": "Metadata-only annotation type. Add reflection-based tests if annotation contract verification becomes necessary.",
    "com/landawn/abacus/jdbc/annotation/FetchColumnByEntityClass.java": "Metadata-only annotation type. Add reflection-based tests if annotation contract verification becomes necessary.",
    "com/landawn/abacus/jdbc/annotation/Handler.java": "Metadata-only annotation type. Add reflection-based tests if annotation contract verification becomes necessary.",
    "com/landawn/abacus/jdbc/annotation/HandlerList.java": "Metadata-only annotation type. Add reflection-based tests if annotation contract verification becomes necessary.",
    "com/landawn/abacus/jdbc/annotation/MappedByKey.java": "Metadata-only annotation type. Add reflection-based tests if annotation contract verification becomes necessary.",
    "com/landawn/abacus/jdbc/annotation/MergedById.java": "Metadata-only annotation type. Add reflection-based tests if annotation contract verification becomes necessary.",
    "com/landawn/abacus/jdbc/annotation/NonDBOperation.java": "Metadata-only annotation type. Add reflection-based tests if annotation contract verification becomes necessary.",
    "com/landawn/abacus/jdbc/annotation/OnDelete.java": "Metadata-only annotation type. Add reflection-based tests if annotation contract verification becomes necessary.",
    "com/landawn/abacus/jdbc/annotation/OutParameter.java": "Metadata-only annotation type. Add reflection-based tests if annotation contract verification becomes necessary.",
    "com/landawn/abacus/jdbc/annotation/OutParameterList.java": "Metadata-only annotation type. Add reflection-based tests if annotation contract verification becomes necessary.",
    "com/landawn/abacus/jdbc/annotation/PerfLog.java": "Metadata-only annotation type. Add reflection-based tests if annotation contract verification becomes necessary.",
    "com/landawn/abacus/jdbc/annotation/PrefixFieldMapping.java": "Metadata-only annotation type. Add reflection-based tests if annotation contract verification becomes necessary.",
    "com/landawn/abacus/jdbc/annotation/Query.java": "Metadata-only annotation type. Add reflection-based tests if annotation contract verification becomes necessary.",
    "com/landawn/abacus/jdbc/annotation/RefreshCache.java": "Metadata-only annotation type. Add reflection-based tests if annotation contract verification becomes necessary.",
    "com/landawn/abacus/jdbc/annotation/SqlFragment.java": "Metadata-only annotation type. Add reflection-based tests if annotation contract verification becomes necessary.",
    "com/landawn/abacus/jdbc/annotation/SqlFragmentList.java": "Metadata-only annotation type. Add reflection-based tests if annotation contract verification becomes necessary.",
    "com/landawn/abacus/jdbc/annotation/SqlLogEnabled.java": "Metadata-only annotation type. Add reflection-based tests if annotation contract verification becomes necessary.",
    "com/landawn/abacus/jdbc/annotation/SqlScript.java": "Metadata-only annotation type. Add reflection-based tests if annotation contract verification becomes necessary.",
    "com/landawn/abacus/jdbc/annotation/SqlSource.java": "Metadata-only annotation type. Add reflection-based tests if annotation contract verification becomes necessary.",
    "com/landawn/abacus/jdbc/annotation/Transactional.java": "Metadata-only annotation type. Add reflection-based tests if annotation contract verification becomes necessary.",
    "com/landawn/abacus/jdbc/dao/CrudDao.java": "Generated-style DAO contract with abstract/default API methods. Exercise via concrete DAO implementations and JdbcUtil/DaoImpl integration tests instead of artificial direct stubs.",
    "com/landawn/abacus/jdbc/dao/CrudDaoL.java": "Generated-style DAO contract with abstract/default API methods. Exercise via concrete DAO implementations and JdbcUtil/DaoImpl integration tests instead of artificial direct stubs.",
    "com/landawn/abacus/jdbc/dao/CrudJoinEntityHelper.java": "Generated-style helper contract with abstract/default API methods. Exercise via concrete helper integration tests instead of artificial direct stubs.",
    "com/landawn/abacus/jdbc/dao/CrudJoinEntityHelperL.java": "Generated-style helper contract with abstract/default API methods. Exercise via concrete helper integration tests instead of artificial direct stubs.",
    "com/landawn/abacus/jdbc/dao/Dao.java": "Generated-style DAO contract with abstract/default API methods. Exercise via concrete DAO implementations and JdbcUtil/DaoImpl integration tests instead of artificial direct stubs.",
    "com/landawn/abacus/jdbc/dao/JoinEntityHelper.java": "Generated-style helper contract with abstract/default API methods. Exercise via concrete helper integration tests instead of artificial direct stubs.",
    "com/landawn/abacus/jdbc/dao/NoUpdateCrudDao.java": "Generated-style DAO contract with abstract/default API methods. Exercise via concrete DAO implementations and JdbcUtil/DaoImpl integration tests instead of artificial direct stubs.",
    "com/landawn/abacus/jdbc/dao/NoUpdateCrudDaoL.java": "Generated-style DAO contract with abstract/default API methods. Exercise via concrete DAO implementations and JdbcUtil/DaoImpl integration tests instead of artificial direct stubs.",
    "com/landawn/abacus/jdbc/dao/NoUpdateDao.java": "Generated-style DAO contract with abstract/default API methods. Exercise via concrete DAO implementations and JdbcUtil/DaoImpl integration tests instead of artificial direct stubs.",
    "com/landawn/abacus/jdbc/dao/ReadOnlyCrudDao.java": "Generated-style DAO contract with abstract/default API methods. Exercise via concrete DAO implementations and JdbcUtil/DaoImpl integration tests instead of artificial direct stubs.",
    "com/landawn/abacus/jdbc/dao/ReadOnlyCrudDaoL.java": "Generated-style DAO contract with abstract/default API methods. Exercise via concrete DAO implementations and JdbcUtil/DaoImpl integration tests instead of artificial direct stubs.",
    "com/landawn/abacus/jdbc/dao/ReadOnlyCrudJoinEntityHelper.java": "Generated-style helper contract with abstract/default API methods. Exercise via concrete helper integration tests instead of artificial direct stubs.",
    "com/landawn/abacus/jdbc/dao/ReadOnlyCrudJoinEntityHelperL.java": "Generated-style helper contract with abstract/default API methods. Exercise via concrete helper integration tests instead of artificial direct stubs.",
    "com/landawn/abacus/jdbc/dao/ReadOnlyDao.java": "Generated-style DAO contract with abstract/default API methods. Exercise via concrete DAO implementations and JdbcUtil/DaoImpl integration tests instead of artificial direct stubs.",
    "com/landawn/abacus/jdbc/dao/ReadOnlyJoinEntityHelper.java": "Generated-style helper contract with abstract/default API methods. Exercise via concrete helper integration tests instead of artificial direct stubs.",
    "com/landawn/abacus/jdbc/dao/UncheckedCrudDao.java": "Generated-style DAO contract with abstract/default API methods. Exercise via concrete DAO implementations and JdbcUtil/DaoImpl integration tests instead of artificial direct stubs.",
    "com/landawn/abacus/jdbc/dao/UncheckedCrudDaoL.java": "Generated-style DAO contract with abstract/default API methods. Exercise via concrete DAO implementations and JdbcUtil/DaoImpl integration tests instead of artificial direct stubs.",
    "com/landawn/abacus/jdbc/dao/UncheckedCrudJoinEntityHelper.java": "Generated-style helper contract with abstract/default API methods. Exercise via concrete helper integration tests instead of artificial direct stubs.",
    "com/landawn/abacus/jdbc/dao/UncheckedCrudJoinEntityHelperL.java": "Generated-style helper contract with abstract/default API methods. Exercise via concrete helper integration tests instead of artificial direct stubs.",
    "com/landawn/abacus/jdbc/dao/UncheckedDao.java": "Generated-style DAO contract with abstract/default API methods. Exercise via concrete DAO implementations and JdbcUtil/DaoImpl integration tests instead of artificial direct stubs.",
    "com/landawn/abacus/jdbc/dao/UncheckedJoinEntityHelper.java": "Generated-style helper contract with abstract/default API methods. Exercise via concrete helper integration tests instead of artificial direct stubs.",
    "com/landawn/abacus/jdbc/dao/UncheckedNoUpdateCrudDao.java": "Generated-style DAO contract with abstract/default API methods. Exercise via concrete DAO implementations and JdbcUtil/DaoImpl integration tests instead of artificial direct stubs.",
    "com/landawn/abacus/jdbc/dao/UncheckedNoUpdateCrudDaoL.java": "Generated-style DAO contract with abstract/default API methods. Exercise via concrete DAO implementations and JdbcUtil/DaoImpl integration tests instead of artificial direct stubs.",
    "com/landawn/abacus/jdbc/dao/UncheckedNoUpdateDao.java": "Generated-style DAO contract with abstract/default API methods. Exercise via concrete DAO implementations and JdbcUtil/DaoImpl integration tests instead of artificial direct stubs.",
    "com/landawn/abacus/jdbc/dao/UncheckedReadOnlyCrudDao.java": "Generated-style DAO contract with abstract/default API methods. Exercise via concrete DAO implementations and JdbcUtil/DaoImpl integration tests instead of artificial direct stubs.",
    "com/landawn/abacus/jdbc/dao/UncheckedReadOnlyCrudDaoL.java": "Generated-style DAO contract with abstract/default API methods. Exercise via concrete DAO implementations and JdbcUtil/DaoImpl integration tests instead of artificial direct stubs.",
    "com/landawn/abacus/jdbc/dao/UncheckedReadOnlyCrudJoinEntityHelper.java": "Generated-style helper contract with abstract/default API methods. Exercise via concrete helper integration tests instead of artificial direct stubs.",
    "com/landawn/abacus/jdbc/dao/UncheckedReadOnlyCrudJoinEntityHelperL.java": "Generated-style helper contract with abstract/default API methods. Exercise via concrete helper integration tests instead of artificial direct stubs.",
    "com/landawn/abacus/jdbc/dao/UncheckedReadOnlyDao.java": "Generated-style DAO contract with abstract/default API methods. Exercise via concrete DAO implementations and JdbcUtil/DaoImpl integration tests instead of artificial direct stubs.",
    "com/landawn/abacus/jdbc/dao/UncheckedReadOnlyJoinEntityHelper.java": "Generated-style helper contract with abstract/default API methods. Exercise via concrete helper integration tests instead of artificial direct stubs.",
}


def package_name(source: Path) -> str:
    for line in source.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line.startswith("package "):
            return line.removeprefix("package ").removesuffix(";")
    raise ValueError(f"Package declaration not found in {source}")


def render_test_class(package: str, class_name: str, reason: str) -> str:
    return f"""package {package};

import com.landawn.abacus.TestBase;

public class {class_name}Test extends TestBase {{

    // TODO: {reason}
}}
"""


def main() -> int:
    created = 0

    for relative_source, reason in sorted(STUB_REASONS.items()):
        source_path = SRC_ROOT / relative_source
        test_relative = Path(relative_source)
        test_path = TEST_ROOT / test_relative.parent / f"{test_relative.stem}Test.java"

        if test_path.exists():
            continue

        package = package_name(source_path)
        class_name = test_relative.stem
        test_path.parent.mkdir(parents=True, exist_ok=True)
        test_path.write_text(render_test_class(package, class_name, reason), encoding="utf-8")
        created += 1
        print(f"created {test_path.relative_to(ROOT).as_posix()}")

    print(f"created {created} test stubs")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
