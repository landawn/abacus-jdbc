#!/usr/bin/env node
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve, relative, basename } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const testAnnotation = /^@(Test|ParameterizedTest|RepeatedTest|TestFactory)\b/;
const assertion = /^(assert\w*|verify\w*|fail|<java-assert>)$/;
const simpleAnnotation = annotation => annotation.replace(/^@(?:[\w$]+\.)+/, '@');

export function inventory(directory) {
    return JSON.parse(execFileSync('java', [resolve(root, 'scripts/test-maintenance/TestInventory.java'), directory], {
        cwd: root, encoding: 'utf8', windowsHide: true, maxBuffer: 32 * 1024 * 1024,
    }));
}

// Preserve literal contents, including whitespace. Comments and formatting are not behavior.
export function canonicalBody(body) {
    const tokens = /\/\/[^\r\n]*|\/\*[\s\S]*?\*\/|"""(?:\\[\s\S]|(?!""")[\s\S])*"""|"(?:\\[\s\S]|[^"\\])*"|'(?:\\[\s\S]|[^'\\])*'|[\p{ID_Start}_$][\p{ID_Continue}$]*|\d[\w.]*|>>>=|>>>|>>=|<<=|>>|<<|\+\+|--|&&|\|\||==|!=|<=|>=|\+=|-=|\*=|\/=|%=|&=|\|=|\^=|->|::|\S/gu;
    return JSON.stringify([...body.matchAll(tokens)].map(m => m[0]).filter(token => !token.startsWith('//') && !token.startsWith('/*')));
}

export function analyze(files, sources, ownership = { classes: {}, prefixes: {} }, read = readFileSync) {
    const classes = new Map(), testCases = [], problems = [];
    const flatten = (file, type, parent) => {
        const entry = { ...type, file, parent };
        classes.set(file.package + '.' + type.name, entry);
        for (const member of type.members) if (member.class) flatten(file, member.class, entry);
    };
    for (const file of files) for (const type of file.classes) flatten(file, type, null);
    const baseClass = type => classes.get(type.file.package + '.' + type.extends)
        || [...classes.values()].find(c => c.name === type.extends);
    const tagged = (type, tag, seen = new Set()) => {
        if (!type || seen.has(type)) return false;
        seen.add(type);
        return type.annotations.some(a => simpleAnnotation(a) === '@Tag("' + tag + '")')
            || tagged(type.parent, tag, seen) || tagged(baseClass(type), tag, seen);
    };
    const hasAssertion = (method, type, seen = new Set()) => {
        const key = type.name + '#' + method.name;
        if (seen.has(key)) return false;
        seen.add(key);
        for (const call of method.calls || []) {
            const name = call.split('.').at(-1);
            if (assertion.test(name)) return true;
            for (let scope = type; scope; scope = scope.parent || baseClass(scope)) {
                if (scope.members.some(m => m.kind === 'METHOD' && m.name === name && hasAssertion(m, scope, seen))) return true;
            }
        }
        return false;
    };
    const sourceNames = sources.map(f => basename(f.path, '.java')).sort((a, b) => b.length - a.length);
    for (const type of classes.values()) {
        if (!type.file.package.startsWith('com.landawn.abacus.jdbc')) continue;
        const topName = type.name.split('.')[0];
        const explicit = ownership.classes?.[topName];
        const exact = sourceNames.find(n => topName === n + 'Test' || topName === n + 'IntegrationTest' || topName === n + 'MySQLTest');
        const prefix = Object.keys(ownership.prefixes || {}).sort((a, b) => b.length - a.length).find(p => topName.startsWith(p));
        const inferred = sourceNames.find(n => topName.startsWith(n));
        const owners = explicit || (exact ? [exact] : prefix ? ownership.prefixes[prefix] : inferred ? [inferred] : []);
        for (const method of type.members.filter(m => (m.annotations || []).some(a => testAnnotation.test(simpleAnnotation(a))))) {
            const file = relative(root, type.file.path).replaceAll('\\', '/');
            const id = type.file.package + '.' + type.name + '#' + method.name;
            const annotations = method.annotations.map(simpleAnnotation);
            const kind = annotations.find(a => testAnnotation.test(a)).match(testAnnotation)[1];
            const hasTag = tag => tagged(type, tag) || annotations.includes('@Tag("' + tag + '")');
            const tier = hasTag('mysql') ? 'mysql' : hasTag('integration') ? 'integration' : 'unit';
            const source = read(type.file.path, 'utf8');
            const body = source.slice(method.bodyStart, method.bodyEnd);
            const parameters = annotations.filter(a => !/^@(Test|Tag|DisplayName)\b/.test(a));
            testCases.push({ id, file, line: method.line, kind, tier, owners, assertion: hasAssertion(method, type),
                fingerprint: JSON.stringify([canonicalBody(body), parameters]) });
            if (!owners.length) problems.push(id + ': missing production owner');
            for (const owner of owners) if (!sourceNames.includes(owner)) problems.push(id + ': unknown production owner ' + owner);
            if (!tagged(type, 'base-test') && !tagged(type, '2025')) problems.push(id + ': not discoverable through AbacusJdbcTestSuite');
        }
    }
    const byBody = new Map();
    for (const test of testCases) {
        if (!byBody.has(test.fingerprint)) byBody.set(test.fingerprint, []);
        byBody.get(test.fingerprint).push(test);
    }
    const duplicates = [...byBody.values()].filter(group => group.length > 1).map(group => group.map(t => t.id));
    return {
        counts: {
            files: new Set(testCases.map(t => t.file)).size, methods: testCases.length,
            kinds: Object.fromEntries(['Test', 'ParameterizedTest', 'RepeatedTest', 'TestFactory'].map(k => [k, testCases.filter(t => t.kind === k).length])),
            tiers: Object.fromEntries(['unit', 'integration', 'mysql'].map(k => [k, testCases.filter(t => t.tier === k).length])),
        },
        problems: [...new Set(problems)],
        duplicateCandidates: duplicates,
        assertionReview: testCases.filter(t => !t.assertion).map(t => t.id),
        tests: testCases.map(({ fingerprint, ...test }) => test),
    };
}

function main(args) {
    let output;
    for (let i = 0; i < args.length; i++) {
        if (args[i] === '--check') continue;
        if (args[i] === '--json' && args[i + 1] && !args[i + 1].startsWith('--')) output = args[++i];
        else throw new Error('Usage: node scripts/test-maintenance.mjs [--check] [--json report.json]');
    }
    const ownership = JSON.parse(readFileSync(resolve(root, 'scripts/test-maintenance/ownership.json'), 'utf8'));
    const report = analyze(inventory(resolve(root, 'src/test/java')), inventory(resolve(root, 'src/main/java')), ownership);
    if (output) writeFileSync(resolve(output), JSON.stringify(report, null, 2) + '\n');
    console.log(JSON.stringify({ ...report, tests: undefined }, null, 2));
    // Duplicate bodies can exercise different fixtures; assertion helpers can live outside the file.
    // These require review, whereas missing ownership/discovery is an actionable structural failure.
    if (args.includes('--check') && report.problems.length) process.exitCode = 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) main(process.argv.slice(2));
