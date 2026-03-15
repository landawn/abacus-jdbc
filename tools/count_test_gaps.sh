#!/usr/bin/env bash
# Reports test files that have fewer than a threshold of @Test methods,
# indicating potential coverage gaps.
#
# Usage: bash tools/count_test_gaps.sh [min_test_count]

MIN=${1:-1}

echo "=== Test files with fewer than $MIN @Test method(s) ==="
for f in $(find src/test/java/com/landawn/abacus -name '*Test.java' | sort); do
  count=$(grep -c "@Test" "$f" 2>/dev/null || echo 0)
  if [ "$count" -lt "$MIN" ]; then
    echo "  $count tests: $f"
  fi
done

echo ""
echo "=== Test method counts per package ==="
for pkg in jdbc jdbc/annotation jdbc/dao; do
  dir="src/test/java/com/landawn/abacus/$pkg"
  if [ -d "$dir" ]; then
    count=$(grep -r "@Test" "$dir" --include='*.java' | wc -l)
    echo "  com.landawn.abacus.$( echo $pkg | tr '/' '.'): $count @Test methods"
  fi
done
