@file:JsModule("vue")
@file:JsNonModule

package vue

/** A phantom type over a native JavaScript Symbol. Create one with kvue.injectionKey<T>(). */
external interface InjectionKey<T>

external fun <T> provide(key: InjectionKey<T>, value: T)
external fun provide(key: String, value: Any?)
external fun <T> inject(key: InjectionKey<T>): T?
external fun <T> inject(key: InjectionKey<T>, defaultValue: T): T
external fun <T> inject(key: InjectionKey<T>, defaultFactory: () -> T, treatDefaultAsFactory: Boolean): T
external fun inject(key: String, defaultValue: dynamic = definedExternally, treatDefaultAsFactory: Boolean = definedExternally): dynamic
external fun hasInjectionContext(): Boolean

external fun onBeforeMount(callback: () -> Any?)
external fun onMounted(callback: () -> Any?)
external fun onBeforeUpdate(callback: () -> Any?)
external fun onUpdated(callback: () -> Any?)
external fun onBeforeUnmount(callback: () -> Any?)
external fun onUnmounted(callback: () -> Any?)
external fun onActivated(callback: () -> Any?)
external fun onDeactivated(callback: () -> Any?)
external fun onServerPrefetch(callback: () -> Any?)
external fun onErrorCaptured(callback: (error: Any?, instance: dynamic, info: String) -> Boolean?)
external fun onRenderTracked(callback: (DebuggerEvent) -> Unit)
external fun onRenderTriggered(callback: (DebuggerEvent) -> Unit)

external fun getCurrentInstance(): dynamic
external fun useAttrs(): dynamic
external fun useSlots(): dynamic
external fun useId(): String
external fun <T> useTemplateRef(key: String): Ref<T?>
external fun <T> useModel(props: dynamic, name: String, options: dynamic = definedExternally): Ref<T>
external fun useSSRContext(): dynamic
