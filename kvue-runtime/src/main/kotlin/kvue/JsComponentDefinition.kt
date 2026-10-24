package kvue

/** Both generated Kotlin components and imported JS components supply a stable Vue type. */
interface VueComponentType {
    val vue: dynamic
}

class JsComponentDefinition(override val vue: dynamic, description: String) : VueComponentType {
    init {
        require(vue != null && (jsTypeOf(vue) == "object" || jsTypeOf(vue) == "function")) {
            "JS component '$description' did not resolve to an object or function."
        }
        require(!js("Array.isArray")(vue).unsafeCast<Boolean>() && vue.__v_isVNode != true) {
            "JS component '$description' must be a component definition, not a VNode or array."
        }
    }
}

/** Handles both ESM default exports and CommonJS module.exports without cloning the component. */
fun jsDefaultExport(module: dynamic): dynamic =
    if (module != null && jsTypeOf(module.default) != "undefined") module.default else module

/** A TS rest callback receives the original JS argument list as one Kotlin array. */
fun variadicJsCallback(callback: Any?): dynamic {
    if (callback == null) return null
    require(jsTypeOf(callback) == "function") { "A variadic callback must be a function." }
    return js("(function () { return callback(Array.prototype.slice.call(arguments)); })")
}
