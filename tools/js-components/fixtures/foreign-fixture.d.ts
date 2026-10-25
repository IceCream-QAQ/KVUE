import type { ComponentOptionsMixin, DefineComponent, FunctionalComponent, VNode } from 'vue';

export declare const ForeignCounter: DefineComponent<{
    label: string;
    start?: number;
    note?: string | null;
    content?: string;
    format?: (...values: unknown[]) => string;
    nullableFormat?: ((value: string) => string | null) | null;
}, {
    count: number;
    reset: (value?: number | null) => number;
    describe: (suffix: string) => string;
    arity: (...values: unknown[]) => number;
}, {}, {}, {}, ComponentOptionsMixin, ComponentOptionsMixin, {
    change: (value: number, label: string) => boolean;
    'update:modelValue': (value: number) => boolean;
    bulk: (...values: unknown[]) => boolean;
}>;

export declare const ForeignFunctional: FunctionalComponent<{ label: string }>;

export declare const ForeignOptions: {
    props: {
        label: { type: StringConstructor; required: true };
        count: { type: NumberConstructor; default: number };
    };
    emits: { accepted: (value: number) => boolean };
    setup: (props: { label: string; count: number }, context: { emit: Function }) => () => VNode;
};

export declare const installer: { install(app: unknown): void; setup(): void };
export declare function utility(value: number): number;
