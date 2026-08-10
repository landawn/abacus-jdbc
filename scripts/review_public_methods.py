#!/usr/bin/env python3
"""Extract and analyze public method naming conventions under src/main/java."""

from __future__ import annotations

import os
import re
from collections import Counter, defaultdict

ROOT = r"src/main/java"

# Match public / public static / default interface methods
method_pat = re.compile(
    r"^\s*(?:public\s+)?(?:static\s+)?(?:final\s+)?(?:default\s+)?"
    r"(?:synchronized\s+)?"
    r"(?:<[^>]+>\s+)?"
    r"([\w.<>,\s\[\]?@]+?)\s+"
    r"(\w+)\s*\("
)

# Only lines that start with public or default (interface methods)
line_start_pat = re.compile(r"^\s*(public|default)\b")

methods: list[dict] = []

for dirpath, _, files in os.walk(ROOT):
    for f in files:
        if not f.endswith(".java") or f == "package-info.java":
            continue
        path = os.path.join(dirpath, f)
        rel = path.replace("\\", "/")
        cls = f[:-5]
        with open(path, encoding="utf-8", errors="replace") as fh:
            content = fh.read()
        content_nc = re.sub(r"/\*.*?\*/", "", content, flags=re.S)
        content_nc = re.sub(r"//.*?$", "", content_nc, flags=re.M)
        for line in content_nc.splitlines():
            if not line_start_pat.search(line):
                continue
            m = method_pat.search(line)
            if not m:
                continue
            ret, name = m.group(1).strip(), m.group(2)
            ret = re.sub(r"@\w+(?:\([^)]*\))?\s*", "", ret).strip()
            if name in (
                "if",
                "for",
                "while",
                "switch",
                "return",
                "new",
                "class",
                "interface",
                "enum",
                "record",
            ):
                continue
            if name == cls:
                continue
            if not ret or ret in (
                "public",
                "static",
                "final",
                "abstract",
                "default",
                "native",
                "strictfp",
                "sealed",
                "non-sealed",
            ):
                continue
            # skip field-like false positives with = after )
            is_static = bool(re.search(r"\bstatic\b", line[: line.find(name)]))
            is_default = bool(re.search(r"\bdefault\b", line[: line.find(name)]))
            methods.append(
                {
                    "file": rel,
                    "class": cls,
                    "name": name,
                    "ret": ret,
                    "static": is_static,
                    "default": is_default,
                    "line": line.strip()[:220],
                }
            )

print(f"Total public/default methods found: {len(methods)}")
print(f"Unique method names: {len({m['name'] for m in methods})}")
print(f"Classes with methods: {len({m['class'] for m in methods})}")

print("\n=== Classes by public method count (top 40) ===")
for n, c in Counter(m["class"] for m in methods).most_common(40):
    print(f"  {c:4d}  {n}")

print("\n=== Top 50 method names by frequency ===")
for n, c in Counter(m["name"] for m in methods).most_common(50):
    print(f"  {c:4d}  {n}")


def classify(name: str) -> str:
    rules = [
        ("get", 3),
        ("set", 3),
        ("is", 2),
        ("has", 3),
        ("find", 4),
        ("list", 4),
        ("query", 5),
        ("select", 6),
        ("insert", 6),
        ("update", 6),
        ("delete", 6),
        ("remove", 6),
        ("save", 4),
        ("create", 6),
        ("prepare", 7),
        ("execute", 7),
        ("batch", 5),
        ("stream", 6),
        ("count", 5),
        ("exists", 6),
        ("notExists", 9),
        ("check", 5),
        ("parse", 5),
        ("build", 5),
        ("generate", 8),
        ("register", 8),
        ("begin", 5),
        ("commit", 6),
        ("rollback", 8),
        ("close", 5),
        ("open", 4),
        ("add", 3),
        ("clear", 5),
        ("reset", 5),
        ("apply", 5),
        ("map", 3),
        ("join", 4),
        ("load", 4),
        ("fetch", 5),
        ("with", 4),
        ("for", 3),
        ("to", 2),
        ("on", 2),
        ("new", 3),
        ("call", 4),
        ("run", 3),
        ("lock", 4),
        ("unlock", 6),
        ("enable", 6),
        ("disable", 7),
        ("import", 6),
        ("export", 6),
        ("refresh", 7),
        ("foreach", 7),
        ("accept", 6),
        ("sett", 4),  # typo detection
    ]
    for prefix, minlen in rules:
        if name == prefix or (
            name.startswith(prefix)
            and len(name) > len(prefix)
            and (name[len(prefix)].isupper() or name[len(prefix)].isdigit())
        ):
            return prefix + "*"
        if name == prefix:
            return prefix
    if name in ("equals", "hashCode", "toString", "compareTo", "clone"):
        return "Object*"
    return "other_camel"


patterns = Counter(classify(m["name"]) for m in methods)
print("\n=== Naming prefix patterns ===")
for p, c in patterns.most_common():
    print(f"  {c:4d}  {p}")

issues: dict[str, list[str]] = defaultdict(list)
for m in methods:
    name = m["name"]
    key = f"{m['class']}.{name}"
    if "_" in name:
        issues["snake_case"].append(key)
    if name[0].isupper():
        issues["PascalCase_method"].append(key)
    if name.startswith("sett") and not name.startswith("setT"):
        issues["typo_sett"].append(key)
    ret = m["ret"].replace(" ", "")
    if ret == "boolean" and not re.match(
        r"^(is|has|can|should|was|were|will|supports|contains|equals|exists|notExists|allMatch|anyMatch|noneMatch)",
        name,
    ):
        # filter JDBC ResultSet API mirrors
        if m["class"] not in ("ResultSetProxy",):
            issues["boolean_nonstandard"].append(f"{key} -> boolean")
    # Acronym casing: mixed Sql/SQL/ID/Id
    if re.search(r"(SQL|JDBC|DAO)[a-z]", name) or re.search(r"[a-z](SQL|JDBC)\b", name):
        issues["acronym_upper_mid"].append(key)
    if "Sql" in name and "SQL" not in name:
        issues["Sql_mixed"].append(key)
    if "SQL" in name:
        issues["SQL_upper"].append(key)
    if re.search(r"Id[A-Z]|Ids$|ById$|Id$", name) or "ID" in name:
        issues["Id_or_ID"].append(key)
    if re.search(r"(Param|Stmt|Conn|Rs|Cfg|Util|Idx|Cnt|Num)(?=[A-Z]|$)", name):
        issues["abbrev"].append(key)

print("\n=== Potential naming issues ===")
for k, v in sorted(issues.items()):
    uniq = sorted(set(v))
    print(f"\n{k} ({len(uniq)}):")
    for x in uniq[:60]:
        print(f"  {x}")
    if len(uniq) > 60:
        print(f"  ... +{len(uniq) - 60} more")

# Verb families unique names
print("\n=== Related verb families (unique names across API) ===")
all_names = sorted({m["name"] for m in methods})
families_prefixes = [
    "find",
    "get",
    "list",
    "query",
    "select",
    "stream",
    "fetch",
    "load",
    "delete",
    "remove",
    "insert",
    "save",
    "create",
    "update",
    "batch",
    "execute",
    "prepare",
    "join",
    "exists",
    "notExists",
    "count",
    "refresh",
    "import",
    "export",
    "generate",
    "call",
    "run",
    "lock",
    "unlock",
]
for pref in families_prefixes:
    names = [
        n
        for n in all_names
        if n == pref
        or (
            n.startswith(pref)
            and len(n) > len(pref)
            and (n[len(pref)].isupper() or n[len(pref)].isdigit())
        )
    ]
    if not names:
        continue
    print(f"\n{pref}* ({len(names)} unique):")
    for n in names:
        print(f"  {n}")

# Inconsistent pairs
print("\n=== Heuristic inconsistency pairs ===")
def suffixes(prefix):
    out = {}
    for n in all_names:
        if n.startswith(prefix) and len(n) > len(prefix) and n[len(prefix)].isupper():
            out[n[len(prefix) :]] = n
    return out

sf, sg, sl, sq = suffixes("find"), suffixes("get"), suffixes("list"), suffixes("query")
sd, sr = suffixes("delete"), suffixes("remove")
print("find vs get same suffix:")
for s in sorted(set(sf) & set(sg)):
    print(f"  {sf[s]}  |  {sg[s]}")
print("find vs list:")
for s in sorted(set(sf) & set(sl)):
    print(f"  {sf[s]}  |  {sl[s]}")
print("find vs query:")
for s in sorted(set(sf) & set(sq)):
    print(f"  {sf[s]}  |  {sq[s]}")
print("list vs query:")
for s in sorted(set(sl) & set(sq)):
    print(f"  {sl[s]}  |  {sq[s]}")
print("delete vs remove:")
for s in sorted(set(sd) & set(sr)):
    print(f"  {sd[s]}  |  {sr[s]}")
print("get vs query:")
for s in sorted(set(sg) & set(sq)):
    print(f"  {sg[s]}  |  {sq[s]}")

# Full lists for major classes
print("\n=== Full unique public method lists for major API classes ===")
majors = [
    "JdbcUtil",
    "AbstractQuery",
    "PreparedQuery",
    "NamedQuery",
    "CallableQuery",
    "SqlExecutor",
    "Dao",
    "CrudDao",
    "DaoBase",
    "Jdbc",
    "DBLock",
    "SqlTransaction",
    "Transaction",
    "JoinInfo",
    "DataTransferUtil",
    "JdbcCodeGenerationUtil",
    "SqlIdentifierUtil",
    "ReadOps",
    "InsertOps",
    "UpdateOps",
    "DeleteOps",
    "JoinEntityHelper",
    "JoinEntityReadOps",
    "JoinEntityDeleteOps",
    "CrudReadOps",
    "CrudInsertOps",
    "CrudUpdateOps",
    "CrudDeleteOps",
    "cs",
    "DaoUtil",
    "ResultSetProxy",
    "SqlLogConfig",
    "IsolationLevel",
    "Propagation",
    "FetchDirection",
    "UncheckedDao",
    "UncheckedCrudDao",
    "UncheckedReadOps",
    "EmptyHandler",
    "SpringApplicationContext",
]
by = defaultdict(set)
for m in methods:
    by[m["class"]].add(m["name"])
for cls in majors:
    names = sorted(by.get(cls, []))
    if not names:
        print(f"\n## {cls} (0)")
        continue
    print(f"\n## {cls} ({len(names)})")
    for n in names:
        print(f"  {n}")

# Also dump all classes not in majors that have methods
print("\n=== Other classes ===")
for cls in sorted(by.keys()):
    if cls in majors:
        continue
    names = sorted(by[cls])
    print(f"\n## {cls} ({len(names)})")
    for n in names:
        print(f"  {n}")

with open("scripts/public_methods_dump.txt", "w", encoding="utf-8") as out:
    for m in sorted(methods, key=lambda x: (x["class"], x["name"])):
        out.write(
            f"{m['class']}\t{m['name']}\t{m['ret']}\t{m['static']}\t{m['default']}\t{m['file']}\n"
        )
print("\nWrote scripts/public_methods_dump.txt")
