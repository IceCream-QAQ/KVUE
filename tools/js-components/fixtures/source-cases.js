import * as Vue from 'vue';

// Analysis must never evaluate this file, its factories, or its accessors.
throw new Error('JS component source must not be executed by the importer');

const title = 'title';
const required = true;
const initial = { [title]: { type: String, required }, count: Number };
export const Ordered = {
    props: { ...initial, count: { type: String, default: 'last wins' }, date: Date },
    emits: { ['accept' + 'ed']: null },
    render() {},
};

const dynamic = () => { throw new Error('not a static expression'); };
export const Partial = {
    props: { before: String, ...dynamic(), after: Boolean },
    emits: ['known', ...dynamic()],
    render() {},
};
export const UnknownConstructors = { props: { item: [String, ...dynamic()] }, render() {} };
export const DynamicProps = { props: dynamic(), emits: dynamic(), render() {} };

export const Mutated = { props: { title: String }, emits: { saved(value = 0) {} }, render() {} };
Mutated.props = dynamic();

const shared = { title: { type: String, required: true } };
shared.title.required = false;
export const MutatedAlias = { props: shared, render() {} };
export const MutatedExport = Mutated;
const props = ({ title: String });
props.title = Number;
export const MutatedShorthand = { props, render() {} };

const circular = { ...circular };
export const Circular = { props: circular, render() {} };

function defineComponent(value) { return value; }
export const Fake = defineComponent({ props: { secret: String }, render() {} });
export const Getter = { get props() { throw new Error('no getters'); }, render() {} };

export const Setup = Vue.defineComponent(() => () => Vue.h('span'), { props: { value: String }, emits: ['saved'] });
export const OptionalValidator = { emits: { changed(value = 0) {} }, render() {} };
export default Vue.defineComponent({ props: ['caption'], template: '<span />' });
