#!/usr/bin/env bash
# Verifies test coverage by checking that every source class under
# src/main/java/com/landawn/abacus has a corresponding test class
# under src/test/java/com/landawn/abacus and that all tests pass.
#
# Usage: bash tools/verify_test_coverage.sh

set -e

echo "=== Checking source-to-test mapping ==="
missing=0
for src in $(find src/main/java/com/landawn/abacus -name '*.java' | sort); do
  base=$(basename "$src" .java)
  test_file=$(echo "$src" | sed 's|src/main/java|src/test/java|' | sed "s|${base}.java|${base}Test.java|")
  if [ ! -f "$test_file" ]; then
    echo "MISSING TEST: $src -> $test_file"
    missing=$((missing + 1))
  fi
done

if [ "$missing" -eq 0 ]; then
  echo "All source classes have corresponding test classes."
else
  echo "$missing source class(es) are missing test classes."
fi

echo ""
echo "=== Counting test methods ==="
total=$(grep -r "@Test" src/test/java/com/landawn/abacus --include='*.java' | wc -l)
echo "Total @Test methods: $total"

echo ""
echo "=== Running full test suite ==="
mvn test -pl . 2>&1 | grep -E "Tests run:|BUILD"
