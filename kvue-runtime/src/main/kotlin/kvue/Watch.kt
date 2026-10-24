package kvue

import kvue.`fun`.jso
import kvue.reactive.ReadRef
import kotlin.reflect.KProperty0

enum class WatchFlush(internal val jsName: String) { Pre("pre"), Post("post"), Sync("sync") }
typealias DebuggerEvent = vue.DebuggerEvent

data class WatchOptions(
    val immediate: Boolean = false,
    val deep: Boolean? = null,
    val depth: Int? = null,
    val flush: WatchFlush = WatchFlush.Pre,
    val once: Boolean = false,
    val onTrack: ((DebuggerEvent) -> Unit)? = null,
    val onTrigger: ((DebuggerEvent) -> Unit)? = null,
) {
    init {
        require(depth == null || depth >= 0) { "Watch depth must be non-negative." }
        require(deep == null || depth == null) { "Specify either deep or depth, not both." }
    }

    internal fun toVue(): dynamic {
        val options = debugOptions(flush, onTrack, onTrigger)
        options.immediate = immediate
        options.once = once
        if (deep != null) options.deep = deep
        if (depth != null) options.deep = depth
        return options
    }
}

data class WatchEffectOptions(
    val flush: WatchFlush = WatchFlush.Pre,
    val onTrack: ((DebuggerEvent) -> Unit)? = null,
    val onTrigger: ((DebuggerEvent) -> Unit)? = null,
) {
    internal fun toVue(): dynamic = debugOptions(flush, onTrack, onTrigger)
}

private fun debugOptions(
    flush: WatchFlush,
    onTrack: ((DebuggerEvent) -> Unit)?,
    onTrigger: ((DebuggerEvent) -> Unit)?,
): dynamic {
    val options = jso("flush" to flush.jsName)
    if (onTrack != null) options.onTrack = onTrack
    if (onTrigger != null) options.onTrigger = onTrigger
    return options
}

/** isInitial distinguishes an immediate first invocation from a real previous null value. */
class WatchContext internal constructor(
    val isInitial: Boolean,
    private val registerCleanup: (() -> Unit) -> Unit,
) {
    fun onCleanup(callback: () -> Unit) = registerCleanup(callback)
}

class WatchHandle internal constructor(private val handle: vue.WatchHandle) {
    fun stop() = handle.stop()
    fun pause() = handle.pause()
    fun resume() = handle.resume()
    operator fun invoke() = stop()
}

/** Read a delegated property inside the getter so Vue can collect its dependency. */
fun <T> watch(
    source: () -> T,
    options: WatchOptions = WatchOptions(),
    callback: WatchContext.(value: T, oldValue: T?) -> Any?,
): WatchHandle = watchNative({ source() }, options, callback)

fun <T> watch(
    source: KProperty0<T>,
    options: WatchOptions = WatchOptions(),
    callback: WatchContext.(value: T, oldValue: T?) -> Any?,
): WatchHandle = watchNative({ source.get() }, options, callback)

/** Pass the actual ref to Vue, preserving shallowRef/triggerRef force-notification semantics. */
fun <T> watch(
    source: ReadRef<T>,
    options: WatchOptions = WatchOptions(),
    callback: WatchContext.(value: T, oldValue: T?) -> Any?,
): WatchHandle = watchNative(source.vueRef, options, callback)

fun <T> watch(
    source: vue.Ref<T>,
    options: WatchOptions = WatchOptions(),
    callback: WatchContext.(value: T, oldValue: T?) -> Any?,
): WatchHandle = watchNative(source, options, callback)

/** Explicit object overload avoids accidentally accepting snapshots such as watch(count). */
fun <T : Any> watchReactive(
    source: T,
    options: WatchOptions = WatchOptions(),
    callback: WatchContext.(value: T, oldValue: T?) -> Any?,
): WatchHandle {
    require(vue.isReactive(source)) { "watchReactive requires a reactive object; use watch with a getter or ref otherwise." }
    return watchNative(source, options, callback)
}

/** Heterogeneous source tuples are represented as lists; each source is a getter/property reference. */
fun watchMany(
    vararg sources: () -> Any?,
    options: WatchOptions = WatchOptions(),
    callback: WatchContext.(values: List<Any?>, oldValues: List<Any?>?) -> Any?,
): WatchHandle {
    require(sources.isNotEmpty()) { "watchMany requires at least one source." }
    val getters = sources.map { source -> { source() } }.toTypedArray()
    return watchNative<Array<Any?>>(getters, options) { values, oldValues ->
        callback(values.toList(), oldValues?.toList())
    }
}

private fun <T> watchNative(
    source: dynamic,
    options: WatchOptions,
    callback: WatchContext.(T, T?) -> Any?,
): WatchHandle {
    var first = true
    return WatchHandle(vue.watch(source, { value, oldValue, onCleanup ->
        val initial = first && options.immediate
        first = false
        callback(WatchContext(initial, onCleanup), value.unsafeCast<T>(), if (initial) null else oldValue.unsafeCast<T>())
    }, options.toVue()))
}

fun watchEffect(
    options: WatchEffectOptions = WatchEffectOptions(),
    effect: WatchContext.() -> Any?,
): WatchHandle {
    var first = true
    return WatchHandle(vue.watchEffect({ onCleanup ->
        val context = WatchContext(first, onCleanup)
        first = false
        effect(context)
    }, options.toVue()))
}

fun watchPostEffect(effect: WatchContext.() -> Any?): WatchHandle =
    watchEffect(WatchEffectOptions(flush = WatchFlush.Post), effect)

fun watchSyncEffect(effect: WatchContext.() -> Any?): WatchHandle =
    watchEffect(WatchEffectOptions(flush = WatchFlush.Sync), effect)

fun onWatcherCleanup(failSilently: Boolean = false, callback: () -> Unit) =
    vue.onWatcherCleanup(callback, failSilently)
