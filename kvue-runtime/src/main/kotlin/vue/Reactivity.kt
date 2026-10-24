@file:JsModule("vue")
@file:JsNonModule

package vue

/** These bindings preserve T; Vue's conditional UnwrapRef / DeepReadonly types are not reproduced. */
external fun <T> shallowRef(value: T): Ref<T>
external fun triggerRef(ref: Ref<*>)
external fun <T> computed(getter: () -> T): Ref<T>
external fun <T> computed(options: dynamic): Ref<T>
external fun <T : Any> reactive(target: T): T
external fun <T : Any> shallowReactive(target: T): T
external fun <T : Any> readonly(target: T): T
external fun <T : Any> shallowReadonly(target: T): T
external fun isRef(value: Any?): Boolean
external fun isReactive(value: Any?): Boolean
external fun isReadonly(value: Any?): Boolean
external fun isShallow(value: Any?): Boolean
external fun isProxy(value: Any?): Boolean
external fun <T> toRaw(value: T): T
external fun <T : Any> markRaw(value: T): T
external fun <T> unref(value: dynamic): T
external fun <T> toValue(value: dynamic): T
external fun <T> toRef(value: dynamic, key: String = definedExternally, defaultValue: dynamic = definedExternally): Ref<T>
external fun toRefs(target: Any): dynamic
external fun <T> customRef(factory: (track: () -> Unit, trigger: () -> Unit) -> dynamic): Ref<T>

external interface DebuggerEvent {
    val target: Any
    val type: String
    val key: Any?
    val newValue: Any?
    val oldValue: Any?
}

external interface WatchHandle {
    fun stop()
    fun pause()
    fun resume()
}

external fun watch(
    source: dynamic,
    callback: (value: dynamic, oldValue: dynamic, onCleanup: (callback: () -> Unit) -> Unit) -> Any?,
    options: dynamic = definedExternally,
): WatchHandle

external fun watchEffect(effect: (onCleanup: (callback: () -> Unit) -> Unit) -> Any?, options: dynamic = definedExternally): WatchHandle
external fun watchPostEffect(effect: (onCleanup: (callback: () -> Unit) -> Unit) -> Any?, options: dynamic = definedExternally): WatchHandle
external fun watchSyncEffect(effect: (onCleanup: (callback: () -> Unit) -> Unit) -> Any?, options: dynamic = definedExternally): WatchHandle
external fun onWatcherCleanup(callback: () -> Unit, failSilently: Boolean = definedExternally)

external interface EffectScope {
    val active: Boolean
    fun <T> run(block: () -> T): T?
    fun stop()
    fun pause()
    fun resume()
}

external fun effectScope(detached: Boolean = definedExternally): EffectScope
external fun getCurrentScope(): EffectScope?
external fun onScopeDispose(callback: () -> Unit, failSilently: Boolean = definedExternally)
