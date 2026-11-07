package kvue

import kvue.`fun`.jso
import kotlin.js.Promise

data class AsyncComponentOptions(
    val loadingComponent: VueComponentType? = null,
    val errorComponent: VueComponentType? = null,
    val delay: Int? = null,
    val timeout: Int? = null,
    val suspensible: Boolean = true,
    val onError: ((error: Any?, retry: () -> Unit, fail: () -> Unit, attempts: Int) -> Unit)? = null,
) {
    init {
        require(delay == null || delay >= 0) { "Async component delay must be non-negative." }
        require(timeout == null || timeout >= 0) { "Async component timeout must be non-negative." }
    }
}

/** Create once, outside render, so Vue can cache the loader and preserve component identity. */
fun defineAsyncComponent(
    options: AsyncComponentOptions = AsyncComponentOptions(),
    loader: () -> Promise<VueComponentType>,
): JsComponentDefinition {
    val native = jso("loader" to { loader().then { it.vue } }, "suspensible" to options.suspensible)
    options.loadingComponent?.let { native.loadingComponent = it.vue }
    options.errorComponent?.let { native.errorComponent = it.vue }
    options.delay?.let { native.delay = it }
    options.timeout?.let { native.timeout = it }
    options.onError?.let { native.onError = it }
    return JsComponentDefinition(vue.defineAsyncComponent(native), "async component")
}
