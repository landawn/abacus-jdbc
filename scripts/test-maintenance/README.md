# Read-only test maintenance audit

Run from the repository root:

```sh
node scripts/test-maintenance.mjs --check --json target/test-inventory.json
node --test scripts/test-maintenance/test-maintenance.test.mjs
```

Requires a JDK with source-file launching and Node.js with the built-in test
runner. No npm packages or project compilation are required. The Java helper
parses source with the JDK compiler API; invalid Java syntax fails the inventory.

The report includes production ownership, suite discovery tags, unit/integration/
MySQL tiers, test kinds, identical-body candidates, and tests whose assertion path
needs review. Nested classes, annotation arguments, text blocks, parameterized
tests, dynamic test factories, and assertions in local/inherited helpers are
recognized. String literals and parameter-source annotations participate in
duplicate comparison.

`--check` fails for missing/unknown owners or missing suite inclusion tags.
Duplicate candidates and assertion findings require human review and do not
trigger automatic deletion. This is a source audit, not a substitute for JUnit
execution or coverage: custom composed annotations, externally defined assertion
helpers, reflection, and dynamically generated cases can need manual inspection.

Update `ownership.json` when a deliberate cross-cutting test needs explicit
owners. Prefer a production-name prefix for ordinary behavior groups. See the
[test conventions](../../src/test/README.md) for grouping and validation rules.

The dated `test_maintenance_2026-07-05` tools and reports are historical. Use this
entry point for the current suite, which has focused classes and parameterized
tests beyond the historical parser's model.

The latest [cleanup plan and implementation results](cleanup-2026-10-15.md)
record the follow-up consolidation, preserved coverage, and random-order checks.

The subsequent [coverage improvement results](coverage-push-2026-10-15.md)
record 216 additional covered lines, 145 additional test cases, and the remaining
gap to full line and branch coverage.

## Initial reorganization verification (2026-10-15)

| Check | Result |
| --- | --- |
| Baseline local suite | 4,312 passed; external MySQL excluded |
| Final default suite | 4,350 passed; 36 external MySQL cases skipped; no failures or errors |
| Production line coverage | 17,639 covered lines before and after |
| Production branch coverage | 9,952 before; 9,953 after; no class lost line or branch coverage |
| Random order, seed `20261015` | 3,798 unit cases passed; 50 selected integration cases passed; all 38 final logging cases passed separately |
| Source audit | No ownership/discovery problems, identical-body candidates, or assertion-review findings |
| Scanner regressions | 6 passed |

The source preservation comparison accounted for every original test body: 23
were intentionally updated for parameterization, stronger assertions, or fixture
lifecycle changes; all others remained present. Parameterized invocation counts
increased while redundant method bodies were consolidated. Production sources
and root-package suite infrastructure were unchanged. MySQL behavior was compiled
but was not exercised against an external server.
