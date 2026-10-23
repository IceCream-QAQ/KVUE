export const titleProp = { type: String, required: true };
export const sharedProps = {
    title: titleProp,
    initial: { type: Number, default: 4 },
    enabled: Boolean,
};

/** @param {number} value @param {string} label */
export const changed = (value, label) => typeof value === 'number' && typeof label === 'string';
