import { defineComponent } from 'vue';
throw new Error('Public API analysis must not execute this module');

export const Closed = defineComponent({
    setup(_, { expose }) {
        function secret() { return 1; }
        expose();
        return { secret };
    },
});

export const Conditional = defineComponent({
    setup(props, context) {
        if (props.enabled) context.expose({ maybe() { return 1; } });
        return () => null;
    },
});

export const Unknown = defineComponent({
    setup(props, { expose }) {
        expose(props.api);
        return () => null;
    },
});

export const Returned = defineComponent({
    setup() {
        /** @param {string} value */
        const echo = value => value;
        return { echo };
    },
});
