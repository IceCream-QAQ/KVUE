const { h } = require('vue');
module.exports = { props: { label: { type: String, default: 'commonjs-default' } }, setup: props => () => h('span', { 'data-commonjs': '' }, props.label) };
