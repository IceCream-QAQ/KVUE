'use strict';

const { test, before } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { analyze, generate } = require('./generate.cjs');

const nodeModules = path.resolve(process.env.KVUE_NODE_MODULES || path.join(__dirname, '../../build/js/node_modules'));
const configDirectory = path.join(__dirname, 'fixtures');
const configuration = JSON.parse(fs.readFileSync(path.join(configDirectory, 'components.json'), 'utf8'));
let report;
before(() => { report = analyze(configuration, nodeModules, configDirectory); });
const component = name => report.components.find(value => value.name === name);

test('discovers named, default, CommonJS, functional and plain options components', () => {
  assert.deepEqual(report.components.filter(value => value.analysis === 'declaration').map(value => value.name).sort(), ['ElButton', 'ElInput', 'ForeignCommonJs', 'ForeignCounter', 'ForeignDefault', 'ForeignFunctional', 'ForeignOptions']);
  assert.equal(component('ForeignFunctional').kind, 'functional');
  assert.equal(component('ForeignOptions').kind, 'options');
  assert.ok(report.skipped.some(value => value.exportName === 'installer'));
  assert.ok(report.skipped.some(value => value.exportName === 'utility'));
});

test('reads JS options, aliases, imported constants, spreads and JSDoc without declarations', () => {
  assert.deepEqual(report.components.filter(value => value.analysis === 'source').map(value => value.name).sort(), ['SourceArray', 'SourceCommonJs', 'SourceCounter', 'SourceFunctional']);
  const counter = component('SourceCounter');
  assert.equal(counter.props.find(value => value.name === 'title').required, true);
  assert.equal(counter.props.find(value => value.name === 'title').kotlin, 'String');
  assert.equal(counter.props.find(value => value.name === 'initial').required, false);
  assert.equal(counter.props.find(value => value.name === 'initial').kotlin, 'Double');
  assert.equal(counter.props.find(value => value.name === 'enabled').required, false);
  assert.equal(counter.props.find(value => value.name === 'enabled').kotlin, 'Boolean');
  assert.equal(counter.props.find(value => value.name === 'mixed').kotlin, 'Any');
  assert.equal(counter.props.find(value => value.name === 'note').kotlin, 'String?');
  assert.deepEqual(counter.events.find(value => value.name === 'changed').arguments, ['Double', 'String']);
  assert.equal(counter.events.find(value => value.name === 'update:modelValue').variadic, true);
  assert.deepEqual(component('SourceCommonJs').events[0].arguments, ['Double']);
  assert.ok(report.sources.some(file => file.endsWith('/source-props.mjs')));
});

test('preserves string-array prop and event names and functional JSDoc', () => {
  assert.deepEqual(component('SourceArray').props.map(value => [value.name, value.kotlin, value.required]), [['caption', 'Any?', false]]);
  assert.deepEqual(component('SourceArray').events.map(value => [value.name, value.variadic]), [['bulk', true], ['selected', true]]);
  assert.equal(component('SourceFunctional').kind, 'functional');
  assert.deepEqual(component('SourceFunctional').props.map(value => [value.name, value.kotlin, value.required]), [['label', 'String', true]]);
  assert.deepEqual(component('SourceFunctional').events.map(value => value.name), ['picked']);
});

test('resolves a JS entry automatically when no declaration entry is configured', () => {
  const module = path.join(configDirectory, 'source-options.mjs').replaceAll('\\', '/');
  const automatic = analyze({ libraries: [{ module, package: 'fixtures.auto', exports: ['SourceCounter'] }] }, nodeModules);
  assert.equal(automatic.components[0].analysis, 'source');
  assert.equal(automatic.components[0].props.find(value => value.name === 'title').required, true);
});

test('npm resolution falls back to JS, prefers declarations, and permits an explicit source entry', () => {
  const directory = fs.mkdtempSync(path.join(nodeModules, 'kvue-js-source-test-'));
  const module = path.basename(directory);
  const source = path.join(directory, 'index.cjs');
  const library = { module, package: 'fixtures.npm', exports: ['FromPackage'] };
  try {
    fs.writeFileSync(path.join(directory, 'package.json'), JSON.stringify({ name: module, main: './index.cjs' }));
    fs.writeFileSync(source, 'throw new Error("Must not execute"); exports.FromPackage = { props: { label: { type: String, required: true } }, render() {} };');
    const javascript = analyze({ libraries: [library] }, nodeModules).components[0];
    assert.equal(javascript.analysis, 'source');
    assert.equal(javascript.props[0].kotlin, 'String');
    fs.writeFileSync(path.join(directory, 'package.json'), JSON.stringify({ name: module, main: './index.cjs', types: './index.d.ts' }));
    fs.writeFileSync(path.join(directory, 'index.d.ts'), 'export declare const FromPackage: { props: { label: { type: NumberConstructor; required: true } }; render(): any };');
    const declared = analyze({ libraries: [library] }, nodeModules).components[0];
    assert.equal(declared.analysis, 'declaration');
    assert.equal(declared.props[0].kotlin, 'Double');
    const forced = analyze({ libraries: [{ ...library, source }] }, nodeModules).components[0];
    assert.equal(forced.analysis, 'source');
    assert.equal(forced.props[0].kotlin, 'String');
    assert.throws(() => analyze({ libraries: [{ ...library, source, declarations: 'index.d.ts' }] }, nodeModules), /Choose declarations or source/);
  } finally {
    assert.equal(path.dirname(directory), nodeModules);
    fs.rmSync(directory, { recursive: true });
  }
});

test('source inspection is static, ordered, bounded and reports unresolved metadata', () => {
  const library = { module: './source-cases.js', source: 'source-cases.js', package: 'fixtures.cases', exports: '*' };
  const result = analyze({ libraries: [library] }, nodeModules, configDirectory);
  const find = name => result.components.find(value => value.exportName === name);
  assert.equal(find('Ordered').props.find(value => value.name === 'title').required, true);
  assert.equal(find('Ordered').props.find(value => value.name === 'count').kotlin, 'String');
  assert.equal(find('Ordered').props.find(value => value.name === 'date').kotlin, 'Any');
  assert.deepEqual(find('Partial').props.map(value => value.name), ['after']);
  assert.deepEqual(find('Partial').events.map(value => value.name), ['known']);
  assert.equal(find('UnknownConstructors').props[0].kotlin, 'Any?');
  assert.deepEqual(find('DynamicProps').props, []);
  assert.deepEqual(find('Circular').props, []);
  assert.deepEqual(find('Mutated').props, []);
  assert.deepEqual(find('Mutated').events, []);
  assert.deepEqual(find('MutatedAlias').props, []);
  assert.deepEqual(find('MutatedExport').props, []);
  assert.deepEqual(find('MutatedShorthand').props, []);
  assert.deepEqual(find('Getter').props, []);
  assert.equal(find('Setup').props[0].name, 'value');
  assert.deepEqual(find('OptionalValidator').events[0].arguments, ['Double?']);
  assert.equal(find('default').props[0].name, 'caption');
  assert.ok(result.skipped.some(value => value.exportName === 'Fake'));
  for (const name of ['Partial', 'DynamicProps', 'Circular', 'Mutated', 'MutatedAlias', 'Getter', 'Fake']) {
    assert.ok(result.diagnostics.some(value => value.component === name && value.file?.endsWith('source-cases.js') && value.line > 0), name);
  }
});

test('preserves requiredness, nullability and reserved prop names', () => {
  const props = component('ForeignCounter').props;
  assert.equal(props.find(value => value.name === 'label').required, true);
  assert.equal(props.find(value => value.name === 'start').kotlin, 'Double');
  assert.equal(props.find(value => value.name === 'start').required, false);
  assert.equal(props.find(value => value.name === 'note').kotlin, 'String?');
  assert.equal(props.find(value => value.name === 'nullableFormat').kotlin, '((String) -> String?)?');
  assert.equal(props.find(value => value.name === 'content').memberName, 'contentProp');
  assert.equal(component('ForeignOptions').props.find(value => value.name === 'label').kotlin, 'String');
});

test('extracts typed events and adapts rest events and rest callback props', () => {
  const counter = component('ForeignCounter');
  assert.deepEqual(counter.events.find(value => value.name === 'change').arguments, ['Double', 'String']);
  assert.deepEqual(counter.events.find(value => value.name === 'update:modelValue').arguments, ['Double']);
  assert.equal(counter.events.find(value => value.name === 'bulk').variadic, true);
  assert.equal(counter.props.find(value => value.name === 'format').variadic, true);
  assert.ok(!counter.props.some(value => value.name === 'onChange'));
});

test('reports lossy type conversions instead of pretending to have exact types', () => {
  assert.ok(report.diagnostics.some(value => value.component === 'ForeignCounter' && value.member === 'event:bulk'));
  assert.ok(report.diagnostics.some(value => value.member === 'prop:format' && value.reason.includes('Rest callback')));
});

test('analysis CLI returns schemas as JSON without creating build outputs', () => {
  const root = path.join(__dirname, 'build/js-importer-tests');
  fs.mkdirSync(root, { recursive: true });
  const workingDirectory = fs.mkdtempSync(path.join(root, 'analysis-'));
  const process = spawnSync(global.process.execPath, [path.join(__dirname, 'generate.cjs'), '--analyze',
    '--config', path.join(configDirectory, 'components.json'), '--node-modules', nodeModules],
    { cwd: workingDirectory, encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 });
  assert.equal(process.status, 0, process.stderr);
  const analysis = JSON.parse(process.stdout);
  assert.equal(analysis.components.length, report.components.length);
  assert.ok(analysis.schemas.find(schema => schema.name === 'SourceCounter').text.includes('@JsComponent'));
  assert.deepEqual(fs.readdirSync(workingDirectory), []);
});

test('fails clearly for a selected missing or non-component export', () => {
  const library = configuration.libraries[0];
  assert.throws(() => analyze({ libraries: [{ ...library, exports: ['MissingComponent'] }] }, nodeModules, configDirectory), /Export 'MissingComponent' is missing/);
  assert.throws(() => analyze({ libraries: [{ ...library, exports: ['installer'] }] }, nodeModules, configDirectory), /not a recognized Vue component/);
});

test('regeneration removes stale generated declarations and preserves unrelated files', () => {
  const root = path.join(__dirname, 'build/js-importer-tests');
  fs.mkdirSync(root, { recursive: true });
  const output = fs.mkdtempSync(path.join(root, 'generation-'));
  const first = generate(configuration, nodeModules, output, configDirectory);
  const unrelated = path.join(output, 'notes.txt');
  fs.writeFileSync(unrelated, 'keep');
  const subset = { libraries: [{ ...configuration.libraries[0], exports: ['ForeignCounter'] }] };
  const second = generate(subset, nodeModules, output, configDirectory);
  assert.equal(second.components.length, 1);
  for (const stale of first.files.filter(file => !second.files.includes(file))) assert.equal(fs.existsSync(path.join(output, stale)), false);
  assert.equal(fs.readFileSync(unrelated, 'utf8'), 'keep');
  const content = fs.readFileSync(path.join(output, second.files[0]), 'utf8');
  assert.match(content, /@JsComponent/);
  assert.match(content, /variadic = true/);
});
