// Maintainer-only generator. Consumers compile the checked-in Kotlin declarations directly.
const fs = require('node:fs');
const path = require('node:path');

const output = path.resolve(__dirname, '../src/main/kotlin/kvui/Html.kt');
const tags = `a abbr address area article aside audio b base bdi bdo blockquote body br button
canvas caption cite code col colgroup data datalist dd del details dfn dialog div dl dt em embed
fieldset figcaption figure footer form h1 h2 h3 h4 h5 h6 head header hgroup hr html i iframe img
input ins kbd label legend li link main map mark menu meta meter nav noscript object ol optgroup
option output p picture pre progress q rp rt ruby s samp script search section select small source
span strong style sub summary sup table tbody td template textarea tfoot th thead time title tr track
u ul var video wbr`.split(/\s+/);
const voidTags = new Set('area base br col embed hr img input link meta source track wbr'.split(' '));
const modelTags = new Set(['input', 'textarea', 'select']);
const prop = (name, type = 'String', wire = name.toLowerCase()) => ({ name, type, wire });
const stringProps = (...names) => names.map(name => prop(name));
const booleanProps = (...names) => names.map(name => prop(name, 'Boolean'));
const intProps = (...names) => names.map(name => prop(name, 'Int'));
const event = (name, type = 'Event') => prop(name, `((${type}) -> Unit)`, name);
const common = [
  prop('id'), prop('className', 'String', 'class'), prop('style'), prop('title'), prop('hidden', 'Boolean'),
  prop('role'), prop('tabIndex', 'Int', 'tabindex'), event('onClick', 'MouseEvent'),
  event('onKeyDown', 'KeyboardEvent'), event('onKeyUp', 'KeyboardEvent'), event('onFocus', 'FocusEvent'), event('onBlur', 'FocusEvent'),
];
const extras = {
  a: stringProps('href', 'target', 'rel', 'download', 'hrefLang', 'type'),
  area: [...stringProps('alt', 'coords', 'shape', 'href', 'target', 'rel', 'download')],
  audio: [...stringProps('src', 'preload', 'crossOrigin'), ...booleanProps('controls', 'autoPlay', 'loop', 'muted'), event('onPlay'), event('onPause'), event('onEnded')],
  base: stringProps('href', 'target'),
  blockquote: stringProps('cite'),
  button: [...stringProps('type', 'name', 'value', 'form'), ...booleanProps('disabled', 'autoFocus')],
  canvas: intProps('width', 'height'),
  col: intProps('span'),
  colgroup: intProps('span'),
  data: [prop('value', 'Any')],
  del: stringProps('cite', 'dateTime'),
  details: [prop('open', 'Boolean'), prop('name'), event('onToggle')],
  dialog: [prop('open', 'Boolean'), event('onClose'), event('onCancel')],
  embed: [...stringProps('src', 'type'), ...intProps('width', 'height')],
  fieldset: [...booleanProps('disabled'), ...stringProps('name', 'form')],
  form: [...stringProps('action', 'method', 'encType', 'target', 'autoComplete', 'name'), ...booleanProps('noValidate'), event('onSubmit'), event('onReset')],
  html: stringProps('lang', 'dir'),
  iframe: [...stringProps('src', 'srcDoc', 'name', 'sandbox', 'allow', 'loading', 'referrerPolicy'), ...booleanProps('allowFullscreen'), ...intProps('width', 'height'), event('onLoad')],
  img: [...stringProps('src', 'alt', 'srcSet', 'sizes', 'loading', 'decoding', 'crossOrigin', 'referrerPolicy'), ...intProps('width', 'height'), event('onLoad'), event('onError')],
  input: [
    ...stringProps('type', 'name'), prop('value', 'Any'), ...booleanProps('checked', 'disabled', 'readOnly', 'required', 'multiple', 'autoFocus'),
    ...stringProps('placeholder', 'autoComplete', 'accept', 'min', 'max', 'step', 'pattern', 'inputMode', 'list', 'form'),
    ...intProps('minLength', 'maxLength', 'size'), event('onInput'), event('onChange'),
  ],
  ins: stringProps('cite', 'dateTime'),
  label: [prop('htmlFor', 'String', 'for')],
  li: intProps('value'),
  link: stringProps('href', 'rel', 'type', 'media', 'sizes', 'crossOrigin', 'integrity'),
  map: stringProps('name'),
  meta: [...stringProps('name', 'content', 'charset'), prop('httpEquiv', 'String', 'http-equiv')],
  meter: ['value', 'min', 'max', 'low', 'high', 'optimum'].map(name => prop(name, 'Double')),
  object: [...stringProps('data', 'type', 'name', 'form'), ...intProps('width', 'height')],
  ol: [prop('start', 'Int'), prop('reversed', 'Boolean'), prop('type')],
  optgroup: [prop('label'), prop('disabled', 'Boolean')],
  option: [prop('value', 'Any'), prop('label'), ...booleanProps('disabled', 'selected')],
  output: [prop('htmlFor', 'String', 'for'), ...stringProps('name', 'form')],
  progress: [prop('value', 'Double'), prop('max', 'Double')],
  q: stringProps('cite'),
  script: [...stringProps('src', 'type', 'crossOrigin', 'integrity', 'nonce'), ...booleanProps('async', 'defer', 'noModule')],
  select: [...stringProps('name', 'form'), ...booleanProps('disabled', 'required', 'multiple', 'autoFocus'), prop('size', 'Int'), event('onInput'), event('onChange')],
  source: [...stringProps('src', 'type', 'srcSet', 'sizes', 'media'), ...intProps('width', 'height')],
  style: stringProps('media', 'nonce'),
  td: [...intProps('colSpan', 'rowSpan'), prop('headers')],
  textarea: [...stringProps('name', 'value', 'placeholder', 'autoComplete', 'wrap', 'form'), ...booleanProps('disabled', 'readOnly', 'required', 'autoFocus'), ...intProps('rows', 'cols', 'minLength', 'maxLength'), event('onInput'), event('onChange')],
  th: [...intProps('colSpan', 'rowSpan'), ...stringProps('headers', 'scope', 'abbr')],
  time: stringProps('dateTime'),
  track: [...stringProps('src', 'kind', 'srcLang', 'label'), prop('defaultTrack', 'Boolean', 'default')],
  video: [...stringProps('src', 'poster', 'preload', 'crossOrigin'), ...booleanProps('controls', 'autoPlay', 'loop', 'muted', 'playsInline'), ...intProps('width', 'height'), event('onPlay'), event('onPause'), event('onEnded')],
};

function declaration(tag) {
  const hasContent = !voidTags.has(tag) && tag !== 'textarea';
  const props = [...common, ...(extras[tag] || [])];
  const names = props.map(p => p.name);
  if (new Set(names).size !== names.length) throw new Error(`Duplicate parameter in ${tag}`);
  const parameters = props.map(p => `    ${p.name}: ${p.type}? = null,`);
  if (modelTags.has(tag)) parameters.push(
    '    modelValue: OptionalProp<Any?> = OptionalProp.Omitted,',
    '    onUpdateModelValue: ((Any?) -> Unit)? = null,',
    '    @VBind(prop = "modelValue", event = "onUpdateModelValue")',
    '    bindModelValue: Any? = omittedBinding(),',
    '    @VBind(prop = "modelValue", event = "onUpdateModelValue")',
    '    vModel: Any? = omittedBinding(),',
    '    modelModifiers: ModelModifiers = ModelModifiers(),',
  );
  parameters.push('    attributes: Map<String, Any?> = emptyMap(),', '    key: Any? = null,');
  if (hasContent) parameters.push('    content: RenderFun? = null,');
  const functionName = ['object', 'var'].includes(tag) ? `\`${tag}\`` : tag;
  const body = [
    ...(modelTags.has(tag) ? ['    checkBindingArguments(bindModelValue, vModel)'] : []),
    `    return htmlElement("${tag}", attributes, arrayOf(`,
    ...props.map(p => `        "${p.wire}" to ${p.name},`),
    '        "key" to key,',
    `    ), ${hasContent ? 'content' : 'null'}${modelTags.has(tag) ? ', modelValue, onUpdateModelValue, modelModifiers' : ''})`,
  ];
  return `/** Renders a native \`<${tag}>\` element${hasContent ? '' : ' without child content'}. */\nfun RenderGroup.${functionName}(\n${parameters.join('\n')}\n): ElementNode {\n${body.join('\n')}\n}`;
}

const result = `// Generated by kvui/tools/generate.cjs. Edit the generator, then run it with Node.js.
package kvui

import kvue.OptionalProp
import kvue.annotation.VBind
import kvue.checkBindingArguments
import kvue.omittedBinding
import kvue.vdom.RenderFun
import kvue.vdom.RenderGroup
import kvue.vdom.knode.ElementNode
import org.w3c.dom.events.Event
import org.w3c.dom.events.FocusEvent
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent

${tags.map(declaration).join('\n\n')}
`;
if (process.argv.includes('--check')) {
  if (!fs.existsSync(output) || fs.readFileSync(output, 'utf8').replace(/\r\n/g, '\n') !== result) {
    console.error('KVUI declarations are stale. Run: node kvui/tools/generate.cjs');
    process.exitCode = 1;
  }
} else {
  fs.mkdirSync(path.dirname(output), { recursive: true });
  fs.writeFileSync(output, result);
  console.log(`Generated ${tags.length} HTML tag functions in kvui/Html.kt`);
}
