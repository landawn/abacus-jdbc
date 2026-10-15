import assert from 'node:assert/strict';
import test from 'node:test';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { inventory, analyze, canonicalBody } from '../test-maintenance.mjs';

const directory = dirname(fileURLToPath(import.meta.url));
const files = inventory(resolve(directory, 'fixtures'));
const report = analyze(files, [{ path: 'JdbcUtil.java' }], { classes: { InventoryFixtureTest: ['JdbcUtil'] } });

test('Java parsing handles array annotations, nested classes, and text blocks', () => {
    assert.equal(report.counts.methods, 7);
    assert.deepEqual(report.counts.kinds, { Test: 4, ParameterizedTest: 2, RepeatedTest: 0, TestFactory: 1 });
    assert.equal(report.tests.some(t => t.id.includes('imaginaryTest')), false);
    assert.ok(report.tests.some(t => t.id.includes('NestedCases#')));
});

test('inherited suite tags and method-level integration tags are recognized', () => {
    assert.deepEqual(report.problems, []);
    assert.deepEqual(report.counts.tiers, { unit: 6, integration: 1, mysql: 0 });
});

test('assertions through helpers and dynamic-test method references are recognized', () => {
    assert.deepEqual(report.assertionReview, ['com.landawn.abacus.jdbc.InventoryFixtureTest#aCommentIsNotAnAssertion']);
});

test('equal parameterized bodies with different input sources are distinct', () => {
    assert.deepEqual(report.duplicateCandidates, []);
});

test('duplicate normalization ignores code formatting and comments', () => {
    assert.equal(canonicalBody('{ assertEquals(1, /* reason */ 2); }'), canonicalBody('{assertEquals(1,2);}'));
});

test('duplicate normalization preserves literal contents and token boundaries', () => {
    assert.notEqual(canonicalBody('call("a  b");'), canonicalBody('call("a b");'));
    assert.notEqual(canonicalBody('call("/* text */");'), canonicalBody('call("");'));
    assert.notEqual(canonicalBody('a + +b;'), canonicalBody('a++ + b;'));
    assert.notEqual(canonicalBody('a instanceof B;'), canonicalBody('ainstanceofB;'));
});
