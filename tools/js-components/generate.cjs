#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const sourceAnalyzer = require('./source-analysis.cjs');

const GLOBAL_PROPS = new Set(['key', 'ref', 'ref_for', 'ref_key', 'class', 'style']);
const KOTLIN_KEYWORDS = new Set(('as break class continue do else false for fun if in interface is null object package return super this throw true try typealias typeof val var when while').split(' '));
const DOM_TYPES = new Map([
  ['Event', 'org.w3c.dom.events.Event'],
  ['MouseEvent', 'org.w3c.dom.events.MouseEvent'],
  ['KeyboardEvent', 'org.w3c.dom.events.KeyboardEvent'],
  ['FocusEvent', 'org.w3c.dom.events.FocusEvent'],
  ['Element', 'org.w3c.dom.Element'],
  ['HTMLElement', 'org.w3c.dom.HTMLElement'],
  ['HTMLInputElement', 'org.w3c.dom.HTMLInputElement'],
]);

const kotlinString = value => JSON.stringify(String(value)).replace(/\$/g, '\\$');
const kotlinName = value => KOTLIN_KEYWORDS.has(value) ? '`' + value + '`' : value;
const upperFirst = value => value.charAt(0).toUpperCase() + value.slice(1);
const lowerFirst = value => value.charAt(0).toLowerCase() + value.slice(1);
const listenerName = name => 'on' + upperFirst(name);
const callbackName = name => 'on' + name.split(/[-:]/).map(upperFirst).join('');
function nullable(value) {
  let parentheses = 0;
  let generics = 0;
  for (let index = 0; index < value.length; index++) {
    if (value[index] === '(') parentheses++;
    else if (value[index] === ')') parentheses--;
    else if (value[index] === '<') generics++;
    else if (value[index] === '>' && value[index - 1] !== '-') generics--;
    else if (value[index] === '-' && value[index + 1] === '>' && parentheses === 0 && generics === 0) return '(' + value + ')?';
  }
  return value.endsWith('?') ? value : value + '?';
}

function identifier(value, fallback = 'Component') {
  const parts = String(value).split(/[^A-Za-z0-9_]+/).filter(Boolean);
  let name = parts.map((part, index) => index ? upperFirst(part) : part).join('') || fallback;
  if (/^[0-9]/.test(name)) name = fallback + name;
  return name;
}

function selection(library, exported) {
  if (library.exports === '*' || library.exports === undefined) return exported.map(name => [name, null]);
  if (Array.isArray(library.exports)) return library.exports.map(name => [name, null]);
  if (library.exports && typeof library.exports === 'object') return Object.entries(library.exports);
  throw new Error(`Invalid exports selection for ${library.module}`);
}

function analyze(config, nodeModules, configDirectory = process.cwd()) {
  const ts = require(path.join(nodeModules, 'typescript'));
  if (!Array.isArray(config.libraries) || !config.libraries.length) throw new Error('Config must contain a non-empty libraries array.');
  for (const library of config.libraries) {
    if (typeof library.module !== 'string' || !library.module.trim()) throw new Error('Every library requires a module name.');
    if (!/^[A-Za-z_]\w*(\.[A-Za-z_]\w*)*$/.test(library.package || '')) throw new Error(`Invalid Kotlin package for ${library.module}.`);
    if (library.declarations && library.source) throw new Error(`Choose declarations or source for ${library.module}, not both.`);
    for (const field of ['declarations', 'source']) {
      if (library[field] !== undefined && (typeof library[field] !== 'string' || !library[field])) throw new Error(`Invalid ${field} entry for ${library.module}.`);
    }
  }
  const probe = path.join(path.dirname(nodeModules), '__kvue_type_imports__.ts');
  const sourceText = config.libraries.map((library, index) => `import * as Library${index} from ${JSON.stringify(library.module)};`).join('\n');
  const options = {
    target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext,
    moduleResolution: ts.ModuleResolutionKind.Bundler, strict: true, skipLibCheck: true,
    esModuleInterop: true, allowSyntheticDefaultImports: true, noEmit: true, types: [],
    allowJs: true, checkJs: false, maxNodeModuleJsDepth: 10, jsx: ts.JsxEmit.Preserve,
  };
  const host = ts.createCompilerHost(options);
  const overrides = new Map(config.libraries.filter(library => library.declarations || library.source)
    .map(library => [library.module, path.resolve(configDirectory, library.declarations || library.source)]));
  const extensions = [ts.Extension.Dmts, ts.Extension.Dcts, ts.Extension.Dts, ts.Extension.Mts, ts.Extension.Cts,
    ts.Extension.Tsx, ts.Extension.Ts, ts.Extension.Mjs, ts.Extension.Cjs, ts.Extension.Jsx, ts.Extension.Js];
  host.resolveModuleNames = (names, containingFile) => names.map(name => {
    if (overrides.has(name)) {
      const resolvedFileName = overrides.get(name);
      const extension = extensions.find(extension => resolvedFileName.endsWith(extension));
      if (!extension) throw new Error(`Unsupported component analysis entry: ${resolvedFileName}`);
      return { resolvedFileName, extension, isExternalLibraryImport: false };
    }
    const resolved = ts.resolveModuleName(name, containingFile, options, host).resolvedModule;
    return resolved || (!name.startsWith('.') && !path.isAbsolute(name)
      ? ts.resolveModuleName(name, probe, options, host).resolvedModule : undefined);
  });
  const originalGetSourceFile = host.getSourceFile.bind(host);
  host.getSourceFile = (name, version, ...rest) => path.resolve(name) === probe
    ? ts.createSourceFile(probe, sourceText, version, true)
    : originalGetSourceFile(name, version, ...rest);
  const program = ts.createProgram([probe], options, host);
  const checker = program.getTypeChecker();
  const source = program.getSourceFile(probe);
  const errors = [...program.getSyntacticDiagnostics(), ...program.getSemanticDiagnostics(source)]
    .filter(diagnostic => diagnostic.category === ts.DiagnosticCategory.Error);
  if (errors.length) throw new Error(ts.formatDiagnosticsWithColorAndContext(errors, {
    getCurrentDirectory: () => process.cwd(), getCanonicalFileName: name => name, getNewLine: () => '\n',
  }));

  const diagnostics = [];
  const components = [];
  const skipped = [];
  const names = new Set();
  let location = {};
  const typeText = type => checker.typeToString(type, source, ts.TypeFormatFlags.NoTruncation).slice(0, 2000);
  const symbolType = symbol => checker.getTypeOfSymbolAtLocation(symbol, symbol.valueDeclaration || symbol.declarations?.[0] || source);
  const propertyType = (type, name) => { const symbol = type.getProperty(name); return symbol && symbolType(symbol); };
  const nonNullable = type => checker.getNonNullableType(type);
  const warn = (reason, type, origin = {}) => diagnostics.push({ ...location, ...origin, reason, typescript: typeof type === 'string' ? type : typeText(type) });
  const sourceReader = sourceAnalyzer(ts, checker, warn, program.getSourceFiles());
  function fallback(type, reason, mayBeNull = false) {
    warn(reason, type);
    return { kotlin: mayBeNull ? 'Any?' : 'Any', variadic: false };
  }

  function signatureParameters(signature, start = 0) {
    const parameters = [];
    for (const symbol of signature.parameters.slice(start)) {
      const declaration = symbol.valueDeclaration || symbol.declarations?.[0];
      const type = symbolType(symbol);
      if (declaration?.dotDotDotToken) {
        if (!checker.isTupleType(type)) return { variadic: true, parameters: [] };
        const flags = type.target?.elementFlags || [];
        if (flags.some(flag => flag & (ts.ElementFlags.Rest | ts.ElementFlags.Variadic))) return { variadic: true, parameters: [] };
        checker.getTypeArguments(type).forEach((item, index) => parameters.push({ type: item, optional: !!(flags[index] & ts.ElementFlags.Optional) }));
      } else {
        parameters.push({ type, optional: !!declaration?.questionToken || !!declaration?.initializer || !!(symbol.flags & ts.SymbolFlags.Optional) });
      }
    }
    return { variadic: false, parameters };
  }

  function toKotlin(type, depth = 0, undefinedAsNull = false) {
    if (depth > 7) return fallback(type, 'Type nesting limit; use a JS object for this value.');
    if (type.flags & (ts.TypeFlags.Any | ts.TypeFlags.Unknown)) return fallback(type, 'TypeScript any/unknown has no narrower Kotlin contract.', true);
    if (type.isUnion()) {
      const allowNull = type.types.some(item => item.flags & ts.TypeFlags.Null) ||
        (undefinedAsNull && type.types.some(item => item.flags & ts.TypeFlags.Undefined));
      const members = type.types.filter(item => !(item.flags & (ts.TypeFlags.Null | ts.TypeFlags.Undefined | ts.TypeFlags.Never)));
      if (!members.length) return { kotlin: 'Any?', variadic: false };
      const converted = members.map(item => toKotlin(item, depth + 1, undefinedAsNull));
      const kinds = [...new Set(converted.map(item => item.kotlin))];
      let result = kinds.length === 1 ? converted[0] : fallback(type, 'Heterogeneous union widened to Any.', allowNull);
      return { ...result, kotlin: allowNull ? nullable(result.kotlin) : result.kotlin };
    }
    if (type.flags & (ts.TypeFlags.StringLike | ts.TypeFlags.TemplateLiteral)) return { kotlin: 'String', variadic: false };
    if (type.flags & ts.TypeFlags.NumberLike) return { kotlin: 'Double', variadic: false };
    if (type.flags & ts.TypeFlags.BooleanLike) return { kotlin: 'Boolean', variadic: false };
    if (type.flags & ts.TypeFlags.Void) return { kotlin: 'Unit', variadic: false };
    if (type.flags & (ts.TypeFlags.Null | ts.TypeFlags.Undefined)) return { kotlin: 'Any?', variadic: false };
    if (type.flags & ts.TypeFlags.TypeParameter) {
      const bound = checker.getBaseConstraintOfType(type);
      return bound && bound !== type ? toKotlin(bound, depth + 1, undefinedAsNull) : fallback(type, 'Unbound generic parameter widened to Any.', true);
    }
    if (type.isIntersection()) {
      const scalar = type.types.find(item => item.flags & (ts.TypeFlags.StringLike | ts.TypeFlags.NumberLike | ts.TypeFlags.BooleanLike));
      if (scalar) return toKotlin(scalar, depth + 1, undefinedAsNull);
    }
    const symbol = type.getSymbol();
    const domType = symbol && DOM_TYPES.get(symbol.name);
    if (domType && symbol.declarations?.some(declaration => declaration.getSourceFile().fileName.replaceAll('\\', '/').endsWith('/lib.dom.d.ts'))) {
      return { kotlin: domType, variadic: false };
    }
    if (checker.isArrayType(type) || (symbol?.name === 'ReadonlyArray' && type.typeArguments?.length)) {
      const item = checker.getTypeArguments(type)[0];
      return { kotlin: `Array<${toKotlin(item, depth + 1, true).kotlin}>`, variadic: false };
    }
    if (checker.isTupleType(type)) return fallback(type, 'Tuple widened to Any; its JS array representation is preserved.');
    const calls = type.getCallSignatures();
    if (calls.length) {
      if (calls.length > 1 || calls[0].typeParameters?.length || calls[0].thisParameter) return fallback(type, 'Overloaded, generic or this-bound callback requires a JS function.');
      const parameters = signatureParameters(calls[0]);
      const returns = toKotlin(checker.getReturnTypeOfSignature(calls[0]), depth + 1, true).kotlin;
      if (parameters.variadic) {
        warn('Rest callback exposed as an argument array with a generated JS adapter.', type);
        return { kotlin: `(Array<Any?>) -> ${returns}`, variadic: true };
      }
      const args = parameters.parameters.map(item => {
        const result = toKotlin(item.type, depth + 1, true).kotlin;
        return item.optional ? nullable(result) : result;
      });
      return { kotlin: `(${args.join(', ')}) -> ${returns}`, variadic: false };
    }
    return fallback(type, 'Structural or unsupported type widened to Any; pass the original JS value.');
  }

  function eventNames(type) {
    if (type.isUnion()) return type.types.flatMap(eventNames);
    return type.flags & ts.TypeFlags.StringLiteral ? [type.value] : [];
  }

  function eventModels(emitType, rawEmits, sourceEvents) {
    const result = new Map();
    const add = (name, signature, start) => {
      const previousLocation = location;
      location = { ...location, member: `event:${name}` };
      const signatureInfo = signature && signatureParameters(signature, start);
      let variadic = !signatureInfo || signatureInfo.variadic;
      let args = variadic ? ['Array<Any?>'] : signatureInfo.parameters.map(item => {
        const mapped = toKotlin(item.type, 0, true).kotlin;
        return item.optional ? nullable(mapped) : mapped;
      });
      const existing = result.get(name);
      if (existing && JSON.stringify(existing.arguments) !== JSON.stringify(args)) { variadic = true; args = ['Array<Any?>']; }
      if (variadic) warn('Event payload is variadic or overloaded; exposed as an argument array.', name);
      result.set(name, { name, arguments: args, variadic });
      location = previousLocation;
    };
    for (const signature of emitType?.getCallSignatures() || []) {
      const first = signature.parameters[0];
      if (!first) continue;
      for (const name of eventNames(symbolType(first))) add(name, signature, 1);
    }
    if (!result.size && rawEmits) {
      const raw = nonNullable(rawEmits);
      if (checker.isTupleType(raw)) {
        checker.getTypeArguments(raw).flatMap(eventNames).forEach(name => add(name, null, 0));
      } else if (!checker.isArrayType(raw)) {
        for (const symbol of raw.getProperties()) {
          const signatures = nonNullable(symbolType(symbol)).getCallSignatures();
          if (signatures.length) signatures.forEach(signature => add(symbol.name, signature, 0));
          else if (symbolType(symbol).flags & (ts.TypeFlags.Null | ts.TypeFlags.Any | ts.TypeFlags.Unknown)) add(symbol.name, null, 0);
        }
      }
    }
    for (const event of sourceEvents || []) {
      const signatures = event.validator && nonNullable(checker.getTypeAtLocation(event.validator)).getCallSignatures();
      if (signatures?.length) signatures.forEach(signature => add(event.name, signature, 0));
      else if (!result.has(event.name)) add(event.name, null, 0);
    }
    const sourceNames = sourceEvents && new Set(sourceEvents.map(event => event.name));
    return [...result.values()].filter(event => !sourceNames || sourceNames.has(event.name)).sort((a, b) => a.name.localeCompare(b.name));
  }

  function componentShape(type, explicit) {
    for (const signature of type.getConstructSignatures()) {
      const instance = checker.getReturnTypeOfSignature(signature);
      const props = propertyType(instance, '$props');
      if (props && instance.getProperty('$emit')) return { kind: 'stateful', props, emits: propertyType(instance, '$emit'), slots: propertyType(instance, '$slots') };
    }
    const calls = type.getCallSignatures();
    if (calls.length && ((type.getProperty('props') && type.getProperty('emits')) ||
        (explicit && checker.getReturnTypeOfSignature(calls[0]).getProperty('__v_isVNode')))) {
      const first = calls[0].parameters[0];
      return { kind: 'functional', props: first && symbolType(first), rawEmits: propertyType(type, 'emits') };
    }
    const render = propertyType(type, 'render');
    const template = propertyType(type, 'template');
    const setup = propertyType(type, 'setup');
    const setupReturnsRender = setup && nonNullable(setup).getCallSignatures().some(signature => {
      const returned = checker.getReturnTypeOfSignature(signature);
      const resolved = returned.getSymbol()?.name === 'Promise' ? checker.getTypeArguments(returned)[0] : returned;
      return resolved && nonNullable(resolved).getCallSignatures().length > 0;
    });
    if ((render && nonNullable(render).getCallSignatures().length) ||
        (template && (nonNullable(template).flags & ts.TypeFlags.StringLike)) || setupReturnsRender ||
        (explicit && (type.getProperty('extends') || type.getProperty('mixins')))) {
      const props = propertyType(type, 'props');
      if (props) return { kind: 'options', runtimeProps: nonNullable(props), rawEmits: propertyType(type, 'emits') };
      return { kind: 'options', props: null, rawEmits: propertyType(type, 'emits') };
    }
    return null;
  }

  function runtimePropType(type) {
    const configured = propertyType(type, 'type') || type;
    const calls = nonNullable(configured).getCallSignatures();
    // String/Number/Boolean constructors describe boxed instances when called
    // with new; Vue expects their primitive values. Date, in contrast, is an object.
    const primitive = /^(String|Number|Boolean)Constructor$/.test(configured.getSymbol()?.name || '');
    if (primitive && calls.length === 1) return checker.getReturnTypeOfSignature(calls[0]);
    const constructors = nonNullable(configured).getConstructSignatures();
    const concrete = constructors.filter(signature => !signature.typeParameters?.length);
    const returned = concrete.map(signature => checker.getReturnTypeOfSignature(signature));
    if (returned.length && returned.every(type => type === returned[0])) return returned[0];
    if (!constructors.length && calls.length === 1) return checker.getReturnTypeOfSignature(calls[0]);
    return null;
  }

  function sourcePropModel(prop, typed, depth = 0) {
    const constructor = prop.constructor;
    if (depth > 20) return { ...fallback(constructor?.getText() || 'unknown', 'Runtime constructor nesting limit; possible circular reference.', true), typescript: 'unknown' };
    const choices = constructor && sourceReader.arrayItems(constructor);
    if (choices) {
      if (!choices.complete) return { ...fallback(constructor.getText(), 'Runtime constructor array is incomplete; no narrower value type can be guaranteed.', true), typescript: 'unknown' };
      const allowNull = choices.items.some(node => sourceReader.resolve(node)?.kind === ts.SyntaxKind.NullKeyword);
      const values = choices.items.filter(node => sourceReader.resolve(node)?.kind !== ts.SyntaxKind.NullKeyword)
        .map(node => sourcePropModel({ constructor: node }, null, depth + 1));
      const kinds = [...new Set(values.map(value => value.kotlin))];
      const kotlin = !kinds.length ? 'Any?' : kinds.length === 1 ? kinds[0] : 'Any';
      if (kinds.length > 1) warn('Heterogeneous runtime constructor array widened to Any.', constructor.getText());
      return { kotlin: allowNull ? nullable(kotlin) : kotlin, variadic: kinds.length === 1 && values.every(value => value.variadic),
        typescript: values.map(value => value.typescript).concat(allowNull ? ['null'] : []).join(' | ') || 'unknown' };
    }
    if (constructor && !sourceReader.resolve(constructor)) return { ...fallback(constructor.getText(), 'Runtime constructor cannot be resolved statically.', true), typescript: 'unknown' };
    // Public instance / JSDoc types carry details that a runtime Object or Function
    // constructor cannot express. Literal required flags still come from the AST.
    const publicType = typed && symbolType(typed);
    const usablePublicType = publicType && !(nonNullable(publicType).flags & (ts.TypeFlags.Any | ts.TypeFlags.Unknown | ts.TypeFlags.Never));
    const runtimeType = constructor && runtimePropType(checker.getTypeAtLocation(constructor));
    const actual = usablePublicType ? publicType : runtimeType;
    if (actual) return { ...toKotlin(actual), typescript: typeText(actual) };
    const typescript = constructor?.getText() || 'unknown';
    return { ...fallback(typescript, 'Runtime prop has no precise value type; add JSDoc or declarations to narrow it.', true), typescript };
  }

  source.statements.forEach((statement, index) => {
    const library = config.libraries[index];
    const moduleSymbol = checker.getSymbolAtLocation(statement.moduleSpecifier);
    if (!moduleSymbol) throw new Error(`Cannot resolve declarations or source for ${library.module}.`);
    const exported = new Map(checker.getExportsOfModule(moduleSymbol).map(symbol => [symbol.name, symbol]));
    const exportEquals = moduleSymbol.exports?.get('export=');
    if (exportEquals) exported.set('export=', exportEquals);
    const explicit = library.exports !== '*' && library.exports !== undefined;
    for (const [exportName, rename] of selection(library, [...exported.keys()].sort())) {
      const exportedSymbol = exported.get(exportName);
      if (!exportedSymbol) throw new Error(`Export '${exportName}' is missing from ${library.module}.`);
      const symbol = exportedSymbol.flags & ts.SymbolFlags.Alias ? checker.getAliasedSymbol(exportedSymbol) : exportedSymbol;
      if (!(symbol.flags & ts.SymbolFlags.Value)) { if (explicit) throw new Error(`${library.module}:${exportName} is a type-only export.`); continue; }
      location = { module: library.module, component: exportName };
      const type = symbolType(symbol);
      let shape = componentShape(type, explicit);
      const sourceInfo = sourceReader.inspect(symbol, type, shape);
      if (sourceInfo && !shape) {
        const first = sourceInfo.kind === 'functional' && type.getCallSignatures()[0]?.parameters[0];
        shape = { kind: sourceInfo.kind, props: first ? symbolType(first) : null };
      }
      if (!shape) {
        if (explicit) throw new Error(`${library.module}:${exportName} is not a recognized Vue component declaration.`);
        skipped.push({ module: library.module, exportName, reason: 'Not a recognized Vue public-instance, functional-component or statically readable options export.' });
        continue;
      }
      const defaultName = upperFirst(identifier(library.module.split('/').pop()));
      const name = identifier(rename || (['default', 'export='].includes(exportName) ? defaultName : exportName));
      const qualifiedName = library.package + '.' + name;
      if (names.has(qualifiedName)) throw new Error(`Duplicate generated component name: ${qualifiedName}`);
      names.add(qualifiedName);
      location = { module: library.module, component: exportName };
      const events = eventModels(shape.emits, shape.rawEmits, sourceInfo?.events);
      const listeners = new Set(events.flatMap(event => [listenerName(event.name), callbackName(event.name)]));
      const reserved = new Set(['key', 'content', ...events.map(event => callbackName(event.name))]);
      const props = [];
      const runtime = !!shape.runtimeProps;
      const propSource = shape.props || shape.runtimeProps;
      const sourceProps = sourceInfo?.props;
      const typedProps = new Map(shape.props?.getProperties().map(prop => [prop.name, prop]) || []);
      for (const prop of sourceProps || propSource?.getProperties() || []) {
        if (GLOBAL_PROPS.has(prop.name) || prop.name.startsWith('onVnode') || listeners.has(prop.name)) continue;
        location = { module: library.module, component: exportName, member: `prop:${prop.name}` };
        const declared = sourceProps ? null : symbolType(prop);
        const actualType = declared && (runtime ? runtimePropType(declared) : declared);
        const mapped = sourceProps ? sourcePropModel(prop, typedProps.get(prop.name)) :
          actualType ? toKotlin(actualType) : fallback(declared, 'Runtime prop constructor has no concrete value type.', true);
        let memberName = identifier(prop.name, 'prop');
        while (reserved.has(memberName)) memberName += 'Prop';
        reserved.add(memberName);
        props.push({ name: prop.name, memberName, kotlin: mapped.kotlin, variadic: mapped.variadic,
          required: sourceProps ? prop.required || (sourceInfo.kind === 'functional' && typedProps.has(prop.name) && !(typedProps.get(prop.name).flags & ts.SymbolFlags.Optional)) :
            runtime ? (propertyType(declared, 'required') && typeText(propertyType(declared, 'required')) === 'true') || false : !(prop.flags & ts.SymbolFlags.Optional),
          typescript: mapped.typescript || typeText(actualType || declared),
        });
      }
      const memberNames = new Set(props.map(prop => prop.memberName));
      for (const event of events) {
        let memberName = 'emit' + upperFirst(identifier(event.name, 'event'));
        while (memberNames.has(memberName)) memberName += 'Event';
        memberNames.add(memberName);
        event.memberName = memberName;
      }
      components.push({ module: library.module, package: library.package, name, exportName, kind: sourceInfo?.kind || shape.kind,
        analysis: sourceInfo ? 'source' : 'declaration', source: sourceInfo?.file || symbol.declarations?.[0]?.getSourceFile().fileName,
        props: props.sort((a, b) => a.name.localeCompare(b.name)), events,
        slots: shape.slots?.getProperties().map(symbol => symbol.name).sort() || [],
      });
    }
  });
  return { schemaVersion: 2, typescriptVersion: ts.version, components, diagnostics, skipped,
    declarations: program.getSourceFiles().filter(file => file.isDeclarationFile).map(file => file.fileName).sort(),
    sources: program.getSourceFiles().filter(file => !file.isDeclarationFile && file !== source).map(file => file.fileName).sort(),
  };
}

function renderSchema(component) {
  const lines = ['// Generated by KVUE TypeScript importer. Do not edit.', `package ${component.package.split('.').map(kotlinName).join('.')}`, '',
    'import kvue.annotation.JsComponent', 'import kvue.annotation.Prop', 'import kvue.annotation.Emit', '',
    `@JsComponent(module = ${kotlinString(component.module)}, exportName = ${kotlinString(component.exportName)})`,
    `interface ${kotlinName(component.name)} {`,
  ];
  for (const prop of component.props) {
    lines.push(`    /** TypeScript: ${prop.typescript.replaceAll('*/', '* /').replaceAll('/*', '/ *').replace(/[\r\n]/g, ' ')} */`,
      `    @Prop(required = ${prop.required}, name = ${kotlinString(prop.name)}, variadic = ${prop.variadic})`,
      `    val ${kotlinName(prop.memberName)}: ${prop.kotlin}`, '');
  }
  for (const event of component.events) {
    lines.push(`    @Emit(name = ${kotlinString(event.name)}, variadic = ${event.variadic})`,
      `    val ${kotlinName(event.memberName)}: (${event.arguments.join(', ')}) -> Unit`, '');
  }
  lines.push('}', '');
  return lines.join('\n');
}

function generate(config, nodeModules, outputDirectory, configDirectory = process.cwd()) {
  const report = analyze(config, path.resolve(nodeModules), configDirectory);
  if (!report.components.length) throw new Error('No Vue component declarations were found in the selected libraries.');
  const output = path.resolve(outputDirectory);
  fs.mkdirSync(output, { recursive: true });
  const manifestPath = path.join(output, 'report.json');
  const previous = fs.existsSync(manifestPath) ? JSON.parse(fs.readFileSync(manifestPath, 'utf8')).files || [] : [];
  const files = [];
  for (const component of report.components) {
    const relative = path.join('kotlin', ...component.package.split('.'), component.name + '.kt');
    const target = path.join(output, relative);
    const content = renderSchema(component);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    if (!fs.existsSync(target) || fs.readFileSync(target, 'utf8') !== content) fs.writeFileSync(target, content);
    files.push(relative);
  }
  for (const stale of previous.filter(file => !files.includes(file))) {
    const target = path.resolve(output, stale);
    if (!target.startsWith(output + path.sep) || !target.endsWith('.kt')) throw new Error(`Unsafe generated file path: ${stale}`);
    if (fs.existsSync(target)) {
      if (!fs.readFileSync(target, 'utf8').startsWith('// Generated by KVUE TypeScript importer.')) throw new Error(`Refusing to remove a non-generated file: ${target}`);
      fs.unlinkSync(target);
    }
  }
  report.files = files;
  fs.writeFileSync(manifestPath, JSON.stringify(report, null, 2) + '\n');
  return report;
}

module.exports = { analyze, generate, renderSchema };

if (require.main === module) {
  try {
    const args = process.argv.slice(2);
    const option = name => { const index = args.indexOf(name); if (index < 0 || !args[index + 1]) throw new Error(`Missing ${name}`); return args[index + 1]; };
    const configFile = path.resolve(option('--config'));
    const config = JSON.parse(fs.readFileSync(configFile, 'utf8'));
    const report = generate(config, option('--node-modules'), option('--out'), path.dirname(configFile));
    console.log(`KVUE: generated ${report.components.length} components, ${report.components.reduce((count, component) => count + component.props.length, 0)} props and ${report.components.reduce((count, component) => count + component.events.length, 0)} events.`);
    console.log(`KVUE: ${report.diagnostics.length} type conversion notes; ${report.skipped.length} non-component exports skipped. See ${path.resolve(option('--out'), 'report.json')}.`);
  } catch (error) {
    console.error(`KVUE TypeScript importer: ${error.stack || error.message}`);
    process.exitCode = 1;
  }
}
