const { defineComponent: component, h } = require('vue');

module.exports = component({
    props: { caption: { type: String, default: 'source-commonjs' } },
    emits: {
        /** @param {number} value */
        accepted(value) { return typeof value === 'number'; },
    },
    setup(props, { emit }) {
        return () => h('button', { 'data-source-commonjs': '', onClick: () => emit('accepted', 12) }, props.caption);
    },
});
