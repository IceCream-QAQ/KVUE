const { h, ref } = require('vue');

exports.ForeignCounter = {
    name: 'ForeignCounter',
    props: {
        label: { type: String, required: true },
        start: { type: Number, default: 7 },
        note: { type: String, default: 'fallback' },
        content: { type: String, default: 'content-default' },
        format: Function,
        nullableFormat: { type: Function, default: null },
    },
    emits: ['change', 'update:modelValue', 'bulk'],
    setup(props, { emit, slots, expose }) {
        const count = ref(props.start);
        expose({
            count,
            reset(value = props.start) { count.value = value === null ? 0 : value; return count.value; },
            describe(suffix) { return this.count + ':' + suffix; },
            arity(...values) { return values.length; },
        });
        return () => h('section', { 'data-foreign': props.label }, [
            h('button', { onClick() { count.value++; emit('change', count.value, props.label); emit('update:modelValue', count.value); emit('bulk', count.value, props.label, null); } }, `${props.label}:${count.value}`),
            h('span', { 'data-note': '' }, String(props.note)),
            h('span', { 'data-content': '' }, props.content),
            props.format ? h('span', { 'data-format': '' }, props.format(count.value, props.label)) : null,
            slots.default?.(),
            slots.header?.(),
        ]);
    },
};

exports.ForeignFunctional = props => h('span', { 'data-functional': '' }, props.label);
exports.ForeignFunctional.props = ['label'];
exports.ForeignOptions = {
    props: { label: { type: String, required: true }, count: { type: Number, default: 3 } },
    emits: ['accepted'],
    setup(props, { emit }) { return () => h('button', { 'data-options': '', onClick: () => emit('accepted', props.count) }, `${props.label}:${props.count}`); },
};
exports.installer = { install() {}, setup() {} };
exports.utility = value => value + 1;
