import { h } from 'vue';
export default { props: { label: { type: String, default: 'esm-default' } }, setup: props => () => h('span', { 'data-default': '' }, props.label) };
