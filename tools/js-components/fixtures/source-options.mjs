import { defineComponent as component, h, ref } from 'vue';
import { sharedProps, changed } from './source-props.mjs';

const extraEvents = ['bulk'];
export const SourceCounter = component({
    props: { ...sharedProps, mixed: [String, Number], note: { type: [String, null], default: 'fallback' } },
    emits: { changed, 'update:modelValue': null },
    setup(props, { emit }) {
        const count = ref(props.initial);
        return () => h('button', {
            'data-source-counter': props.title,
            onClick() { count.value++; emit('changed', count.value, props.title); emit('update:modelValue', count.value, 'extra'); },
        }, `${props.title}:${count.value}:${props.enabled}:${props.note}`);
    },
});

export const SourceArray = {
    props: ['caption'],
    emits: ['selected', ...extraEvents],
    setup(props, { emit }) {
        return () => h('button', { 'data-source-array': '', onClick: () => emit('selected', props.caption, 9) }, props.caption);
    },
};

/** @param {{ label: string }} props */
export const SourceFunctional = props => h('span', { 'data-source-functional': '' }, props.label);
SourceFunctional.props = ['label'];
SourceFunctional.emits = ['picked'];

export const installer = { install() {}, setup() {} };
export const utility = value => value + 1;
