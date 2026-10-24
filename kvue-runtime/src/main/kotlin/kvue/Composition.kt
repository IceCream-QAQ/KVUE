package kvue

import kvue.`fun`.toJavaScriptObject
import kvue.reactive.ReadonlyRef

typealias InjectionKey<T> = vue.InjectionKey<T>

fun <T> injectionKey(description: String? = null): InjectionKey<T> =
    (if (description == null) js("Symbol")() else js("Symbol")(description)).unsafeCast<InjectionKey<T>>()

fun <T> provide(key: InjectionKey<T>, value: T) = vue.provide(key, value)
fun provide(key: String, value: Any?) = vue.provide(key, value)

fun <T> inject(key: InjectionKey<T>): T? {
    // Optional lookup has an explicit undefined default, so an absent key does not warn.
    val value = vue.inject(key.unsafeCast<InjectionKey<Any?>>(), js("undefined"))
    return if (jsTypeOf(value) == "undefined") null else value.unsafeCast<T>()
}
fun <T> inject(key: InjectionKey<T>, defaultValue: T): T = vue.inject(key, defaultValue)
fun <T> injectFactory(key: InjectionKey<T>, factory: () -> T): T = vue.inject(key, factory, true)
fun <T> requireInject(key: InjectionKey<T>): T {
    val missing = Any()
    val value = vue.inject(key.unsafeCast<InjectionKey<Any?>>(), missing)
    check(value !== missing) { "Required injection was not provided." }
    return value.unsafeCast<T>()
}
fun hasInjectionContext(): Boolean = vue.hasInjectionContext()

fun onBeforeMount(callback: () -> Any?) = vue.onBeforeMount(callback)
fun onMounted(callback: () -> Any?) = vue.onMounted(callback)
fun onBeforeUpdate(callback: () -> Any?) = vue.onBeforeUpdate(callback)
fun onUpdated(callback: () -> Any?) = vue.onUpdated(callback)
fun onBeforeUnmount(callback: () -> Any?) = vue.onBeforeUnmount(callback)
fun onUnmounted(callback: () -> Any?) = vue.onUnmounted(callback)
fun onActivated(callback: () -> Any?) = vue.onActivated(callback)
fun onDeactivated(callback: () -> Any?) = vue.onDeactivated(callback)
fun onServerPrefetch(callback: () -> Any?) = vue.onServerPrefetch(callback)
fun onErrorCaptured(callback: (error: Any?, instance: dynamic, info: String) -> Boolean?) = vue.onErrorCaptured(callback)
fun onRenderTracked(callback: (DebuggerEvent) -> Unit) = vue.onRenderTracked(callback)
fun onRenderTriggered(callback: (DebuggerEvent) -> Unit) = vue.onRenderTriggered(callback)

fun getCurrentInstance(): dynamic = vue.getCurrentInstance()
fun useAttrs(): dynamic = vue.useAttrs()
fun useSlots(): dynamic = vue.useSlots()
fun useId(): String = vue.useId()
fun <T> useTemplateRef(key: String): ReadonlyRef<T?> = ReadonlyRef(vue.useTemplateRef<T>(key))

fun createApp(component: VueComponentType, props: Map<String, Any?> = emptyMap()): vue.App =
    vue.createApp(component.vue, props.toJavaScriptObject())
