'use strict';

// A deliberately small static reader, not a JavaScript evaluator. Calls other than
// Vue's defineComponent are never unwrapped and no library module is executed.
module.exports = function sourceAnalyzer(ts, checker, warn, sourceFiles) {
  const unalias = symbol => symbol?.flags & ts.SymbolFlags.Alias ? checker.getAliasedSymbol(symbol) : symbol;
  const symbolAt = node => unalias(node && (ts.isIdentifier(node) && ts.isShorthandPropertyAssignment(node.parent)
    ? checker.getShorthandAssignmentValueSymbol(node.parent) : checker.getSymbolAtLocation(node)));
  const isSource = node => node && !node.getSourceFile().isDeclarationFile;
  const unknown = (reason, node) => {
    const file = node?.getSourceFile();
    const position = file && file.getLineAndCharacterOfPosition(node.getStart(file));
    warn(reason, node?.getText().slice(0, 500) || 'unknown', file && { file: file.fileName, line: position.line + 1 });
  };
  const mutations = new Map();

  function mutationTarget(node, seen = new Set()) {
    while (ts.isPropertyAccessExpression(node) || ts.isElementAccessExpression(node) || ts.isParenthesizedExpression(node)) node = node.expression;
    if (!ts.isIdentifier(node)) return null;
    const symbol = symbolAt(node);
    if (!symbol || seen.has(symbol)) return symbol;
    seen.add(symbol);
    const declaration = symbol.valueDeclaration;
    const initializer = declaration && ts.isVariableDeclaration(declaration) && declaration.initializer;
    return initializer && (ts.isIdentifier(initializer) || ts.isPropertyAccessExpression(initializer) || ts.isElementAccessExpression(initializer))
      ? mutationTarget(initializer, seen) || symbol : symbol;
  }

  function scanWrites(node) {
    let target;
    if (ts.isBinaryExpression(node) && node.operatorToken.kind >= ts.SyntaxKind.FirstAssignment && node.operatorToken.kind <= ts.SyntaxKind.LastAssignment) target = node.left;
    else if (ts.isDeleteExpression(node)) target = node.expression;
    else if ((ts.isPrefixUnaryExpression(node) || ts.isPostfixUnaryExpression(node)) &&
        [ts.SyntaxKind.PlusPlusToken, ts.SyntaxKind.MinusMinusToken].includes(node.operator)) target = node.operand;
    const declaration = target && symbolAt(target)?.valueDeclaration;
    const initialization = target && ts.isBinaryExpression(node) && node.operatorToken.kind === ts.SyntaxKind.EqualsToken &&
      ts.isExpressionStatement(node.parent) && ts.isSourceFile(node.parent.parent) &&
      (declaration === target || declaration === node);
    const symbol = !initialization && target && mutationTarget(target);
    if (symbol) mutations.set(symbol, node);
    ts.forEachChild(node, scanWrites);
  }
  sourceFiles.filter(file => !file.isDeclarationFile).forEach(scanWrites);

  function expressionOf(declaration) {
    if (!declaration || !isSource(declaration)) return null;
    if (ts.isVariableDeclaration(declaration)) {
      return declaration.parent.flags & ts.NodeFlags.Const ? declaration.initializer : null;
    }
    if (ts.isExportAssignment(declaration)) return declaration.expression;
    if (ts.isPropertyAssignment(declaration)) return declaration.initializer;
    if (ts.isShorthandPropertyAssignment(declaration)) {
      return declaration.name;
    }
    if (ts.isPropertyAccessExpression(declaration) || ts.isElementAccessExpression(declaration)) {
      const assignment = declaration.parent;
      return ts.isBinaryExpression(assignment) && assignment.left === declaration ? expressionOf(assignment) : null;
    }
    if (ts.isBinaryExpression(declaration) && declaration.operatorToken.kind === ts.SyntaxKind.EqualsToken &&
        ts.isExpressionStatement(declaration.parent) && ts.isSourceFile(declaration.parent.parent)) return declaration.right;
    if (ts.isFunctionDeclaration(declaration) || ts.isMethodDeclaration(declaration)) return declaration;
    return null;
  }

  function unwrap(node) {
    while (node && (ts.isParenthesizedExpression(node) || ts.isAsExpression(node) || ts.isTypeAssertionExpression(node) ||
      ts.isSatisfiesExpression(node) || ts.isNonNullExpression(node))) node = node.expression;
    return node;
  }

  function resolve(node, seen = new Set()) {
    if (!node || seen.has(node) || seen.size > 40) return null;
    seen = new Set(seen).add(node);
    if (ts.isParenthesizedExpression(node) || ts.isAsExpression(node) || ts.isTypeAssertionExpression(node) ||
        ts.isSatisfiesExpression(node) || ts.isNonNullExpression(node)) return resolve(node.expression, seen);
    if (ts.isIdentifier(node) || ts.isPropertyAccessExpression(node) || ts.isElementAccessExpression(node)) {
      const symbol = symbolAt(node);
      const value = expressionOf(symbol?.valueDeclaration || symbol?.declarations?.[0]);
      if (value && mutations.has(symbol) && (ts.isObjectLiteralExpression(unwrap(value)) || ts.isArrayLiteralExpression(unwrap(value)))) {
        unknown('Source object or array is mutated after initialization; its final metadata is not statically resolved.', mutations.get(symbol));
        return null;
      }
      if (value && value !== node) return resolve(value, seen);
    }
    return node;
  }

  function literal(node, seen = new Set()) {
    node = resolve(node);
    if (!node || seen.has(node) || seen.size > 40) return undefined;
    seen = new Set(seen).add(node);
    if (ts.isStringLiteralLike(node)) return node.text;
    if (ts.isNumericLiteral(node)) return Number(node.text);
    if (ts.isBinaryExpression(node) && node.operatorToken.kind === ts.SyntaxKind.PlusToken) {
      const left = literal(node.left, seen);
      const right = literal(node.right, seen);
      if (typeof left === 'string' && typeof right === 'string') return left + right;
    }
    if (node.kind === ts.SyntaxKind.TrueKeyword) return true;
    if (node.kind === ts.SyntaxKind.FalseKeyword) return false;
    return undefined;
  }

  function propertyName(name) {
    if (ts.isComputedPropertyName(name)) {
      const value = literal(name.expression);
      return typeof value === 'string' || typeof value === 'number' ? String(value) : undefined;
    }
    return ts.isIdentifier(name) || ts.isStringLiteralLike(name) || ts.isNumericLiteral(name) ? name.text : undefined;
  }

  function objectEntries(expression, seen = new Set()) {
    const node = resolve(expression);
    if (!node || !ts.isObjectLiteralExpression(node) || seen.has(node) || seen.size > 40) return null;
    seen = new Set(seen).add(node);
    const entries = new Map();
    let complete = true;
    for (const property of node.properties) {
      if (ts.isSpreadAssignment(property)) {
        const spread = objectEntries(property.expression, seen);
        if (!spread?.complete) {
          // An unknown later spread may overwrite any earlier property.
          entries.clear();
          complete = false;
          unknown('Cannot resolve object spread statically; earlier keys may be overwritten.', property);
        }
        for (const [name, value] of spread?.entries || []) entries.set(name, value);
      } else {
        const name = propertyName(property.name);
        if (typeof name !== 'string') {
          entries.clear();
          complete = false;
          unknown('Cannot resolve computed property name; earlier keys may be overwritten.', property);
          continue;
        }
        if (ts.isGetAccessorDeclaration(property) || ts.isSetAccessorDeclaration(property)) {
          entries.delete(name);
          complete = false;
          unknown('Accessor is not evaluated during source analysis.', property);
        } else {
          entries.set(name, ts.isPropertyAssignment(property) ? property.initializer :
            ts.isShorthandPropertyAssignment(property) ? property.name : property);
        }
      }
    }
    return { entries, complete };
  }

  function arrayItems(expression, seen = new Set()) {
    const node = resolve(expression);
    if (!node || !ts.isArrayLiteralExpression(node) || seen.has(node) || seen.size > 40) return null;
    seen = new Set(seen).add(node);
    const items = [];
    let complete = true;
    for (const item of node.elements) {
      if (ts.isSpreadElement(item)) {
        const spread = arrayItems(item.expression, seen);
        if (spread) items.push(...spread.items);
        if (!spread?.complete) {
          complete = false;
          unknown('Cannot resolve array spread statically; some names or constructor types may be missing.', item);
        }
      } else items.push(item);
    }
    return { items, complete };
  }

  function isVueRequire(node) {
    if (!node || !ts.isCallExpression(node) || !ts.isIdentifier(node.expression) || node.expression.text !== 'require') return false;
    const declaration = checker.getSymbolAtLocation(node.expression)?.valueDeclaration;
    if (isSource(declaration)) return false; // A local function called require is not Node's loader.
    return node.arguments.length === 1 && literal(node.arguments[0]) === 'vue';
  }

  function isDefineComponent(expression, seen = new Set()) {
    if (!expression || seen.has(expression) || seen.size > 40) return false;
    seen = new Set(seen).add(expression);
    if (ts.isParenthesizedExpression(expression)) return isDefineComponent(expression.expression, seen);
    if (ts.isBinaryExpression(expression) && expression.operatorToken.kind === ts.SyntaxKind.CommaToken &&
        ts.isNumericLiteral(expression.left) && expression.left.text === '0') return isDefineComponent(expression.right, seen);
    const symbol = symbolAt(expression);
    if (symbol?.name === 'defineComponent' && symbol.declarations?.some(node =>
      /\/node_modules\/(?:vue|@vue\/runtime-core)\//.test(node.getSourceFile().fileName.replaceAll('\\', '/')))) return true;
    if (ts.isPropertyAccessExpression(expression) && expression.name.text === 'defineComponent') {
      if (isVueRequire(resolve(expression.expression))) return true;
    }
    const declaration = symbol?.valueDeclaration;
    if (declaration && ts.isBindingElement(declaration) && ts.isObjectBindingPattern(declaration.parent) &&
        (declaration.propertyName?.text || declaration.name.getText()) === 'defineComponent' &&
        isVueRequire(declaration.parent.parent.initializer)) return true;
    const value = expressionOf(declaration);
    return !!value && isDefineComponent(value, seen);
  }

  function staticMember(type, name) {
    const symbol = type.getProperty(name);
    const declarations = symbol?.declarations?.filter(isSource) || [];
    if (!declarations.length) return { present: false, value: null };
    const values = declarations.map(expressionOf);
    if (values.length !== 1 || !values[0]) {
      unknown(`Multiple or non-top-level assignments to ${name} are not statically resolved.`, declarations[0]);
      return { present: true, value: null };
    }
    return { present: true, value: values[0] };
  }

  function props(expression) {
    const items = arrayItems(expression);
    if (items) return [...new Set(items.items.map(item => {
      const name = literal(item);
      if (typeof name !== 'string') unknown('Cannot resolve a runtime prop name statically.', item);
      return name;
    }).filter(name => typeof name === 'string'))].map(name => ({ name, required: false, constructor: null }));
    const object = objectEntries(expression);
    if (!object) {
      unknown('Cannot resolve runtime props statically; the generated contract may be incomplete.', expression);
      return [];
    }
    return [...object.entries].map(([name, node]) => {
      const options = objectEntries(node);
      const requiredNode = options?.entries.get('required');
      const required = requiredNode ? literal(requiredNode) : false;
      if (requiredNode && typeof required !== 'boolean') unknown(`Cannot resolve required flag for prop '${name}'; it is exposed as optional.`, requiredNode);
      return { name, required: required === true, constructor: options ? options.entries.get('type') || null : node };
    });
  }

  function events(expression) {
    const items = arrayItems(expression);
    if (items) return [...new Set(items.items.map(item => {
      const name = literal(item);
      if (typeof name !== 'string') unknown('Cannot resolve an event name statically.', item);
      return name;
    }).filter(name => typeof name === 'string'))].map(name => ({ name, validator: null }));
    const object = objectEntries(expression);
    if (!object) {
      unknown('Cannot resolve runtime emits statically; the generated contract may be incomplete.', expression);
      return [];
    }
    return [...object.entries].map(([name, validator]) => ({ name, validator }));
  }

  function inspect(symbol, type, shape) {
    const declaration = symbol.valueDeclaration || symbol.declarations?.find(isSource);
    if (!isSource(declaration)) return null;
    const root = resolve(expressionOf(declaration));
    const mutated = mutations.has(symbol) && root && (ts.isObjectLiteralExpression(root) || ts.isArrayLiteralExpression(root));
    if (mutated) unknown('Component options are mutated after initialization; props and emits require declarations.', mutations.get(symbol));
    let options = root;
    let kind = shape?.kind;
    let factory = false;
    if (root && ts.isCallExpression(root) && isDefineComponent(root.expression)) {
      kind = 'stateful';
      factory = true;
      const first = resolve(root.arguments[0]);
      options = first && ts.isFunctionLike(first) ? root.arguments[1] : first;
    }
    const object = mutated ? null : objectEntries(options);
    const member = name => object?.entries.has(name) || (object && !object.complete)
      ? { present: true, value: object.entries.get(name) } : staticMember(type, name);
    const prop = member('props');
    const emit = member('emits');
    if (root && ts.isFunctionLike(root) && (prop.present || emit.present)) kind = 'functional';
    if (!kind) {
      if (root && ts.isCallExpression(root)) unknown('Unrecognized component factory; its JavaScript is not executed. Supply declarations for this export.', root);
      return null;
    }
    if (!root || (!object && kind !== 'functional' && !factory)) unknown('Component implementation cannot be fully resolved statically; using inferred types where available.', declaration);
    for (const inherited of ['extends', 'mixins']) {
      if (object?.entries.has(inherited)) unknown(`Source ${inherited} is not expanded; inherited props and events require declarations.`, object.entries.get(inherited));
    }
    const setup = root && ts.isCallExpression(root) && factory && ts.isFunctionLike(resolve(root.arguments[0]))
      ? resolve(root.arguments[0]) : resolve(object?.entries.get('setup'));
    return { kind, file: declaration.getSourceFile().fileName,
      props: mutated || !root ? [] : prop.present ? prop.value ? props(prop.value) : [] : null,
      events: mutated || !root ? [] : emit.present ? emit.value ? events(emit.value) : [] : null,
      exposed: mutated || !root || kind === 'functional' ? [] : publicMembers(object, setup),
    };
  }

  function publicMembers(options, setup) {
    let members = new Map(objectEntries(options?.entries.get('methods'))?.entries || []);
    if (setup && ts.isFunctionLike(setup) && setup.body) {
      const context = setup.parameters[1]?.name;
      const exposeBinding = context && ts.isObjectBindingPattern(context) && context.elements.find(item =>
        (item.propertyName?.text || item.name.getText()) === 'expose');
      const exposeSymbol = exposeBinding && symbolAt(exposeBinding.name);
      const contextSymbol = context && ts.isIdentifier(context) && symbolAt(context);
      const calls = [];
      function visit(node) {
        if (node !== setup.body && ts.isFunctionLike(node)) return;
        if (ts.isCallExpression(node)) {
          const target = unwrap(node.expression);
          if ((exposeSymbol && symbolAt(target) === exposeSymbol) ||
              (contextSymbol && ts.isPropertyAccessExpression(target) && target.name.text === 'expose' &&
                symbolAt(target.expression) === contextSymbol)) calls.push(node);
        }
        ts.forEachChild(node, visit);
      }
      visit(setup.body);
      if (calls.length) {
        const call = calls[0];
        if (calls.length !== 1 || !ts.isExpressionStatement(call.parent) || call.parent.parent !== setup.body) {
          unknown('Conditional or repeated expose() calls need an explicit public-instance declaration.', call);
          return [];
        }
        if (!call.arguments.length) return [];
        const exposed = objectEntries(call.arguments[0]);
        if (!exposed?.complete) {
          unknown('Cannot resolve the full expose() object; public members are not inferred.', call);
          return [];
        }
        return [...exposed.entries].map(([name, node]) => ({ name, node }));
      }
      const returns = ts.isBlock(setup.body) ? setup.body.statements.filter(ts.isReturnStatement) : [{ expression: setup.body }];
      if (returns.length === 1) {
        const returned = objectEntries(returns[0].expression);
        for (const [name, node] of returned?.entries || []) members.set(name, node);
      }
    }
    if (options?.entries.has('expose')) {
      const list = arrayItems(options.entries.get('expose'));
      const names = list?.complete && list.items.map(item => literal(item));
      if (!names || names.some(name => typeof name !== 'string')) {
        unknown('Cannot resolve the expose option; public members are not inferred.', options.entries.get('expose'));
        return [];
      }
      members = new Map([...members].filter(([name]) => names.includes(name)));
    }
    return [...members].map(([name, node]) => ({ name, node }));
  }

  return { inspect, resolve, arrayItems };
};
