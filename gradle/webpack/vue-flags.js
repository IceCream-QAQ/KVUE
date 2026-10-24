// Shared by the modules' Karma/Webpack test bundles.
config.plugins.push(new (require("webpack").DefinePlugin)({
    __VUE_OPTIONS_API__: JSON.stringify(true),
    __VUE_PROD_DEVTOOLS__: JSON.stringify(false),
    __VUE_PROD_HYDRATION_MISMATCH_DETAILS__: JSON.stringify(false),
}));
