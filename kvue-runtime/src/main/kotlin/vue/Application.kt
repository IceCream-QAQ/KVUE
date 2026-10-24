@file:JsModule("vue")
@file:JsNonModule

package vue

external val version: String

external interface AppConfig {
    var performance: Boolean
    var errorHandler: ((error: Any?, instance: dynamic, info: String) -> Unit)?
    var warnHandler: ((message: String, instance: dynamic, trace: String) -> Unit)?
    var globalProperties: dynamic
    var compilerOptions: dynamic
    var optionMergeStrategies: dynamic
}

external interface App {
    val version: String
    val config: AppConfig
    fun mount(container: dynamic): dynamic
    fun unmount()
    fun onUnmount(callback: () -> Unit)
    fun use(plugin: dynamic, vararg options: Any?): App
    fun mixin(options: dynamic): App
    fun component(name: String): dynamic
    fun component(name: String, definition: dynamic): App
    fun directive(name: String): Directive?
    fun directive(name: String, directive: Directive): App
    fun <T> provide(key: InjectionKey<T>, value: T): App
    fun provide(key: String, value: Any?): App
    fun <T> runWithContext(block: () -> T): T
}

/** Component options and async loader options keep their native JS shape. */
external fun defineComponent(options: dynamic): dynamic
external fun defineAsyncComponent(loaderOrOptions: dynamic): dynamic
