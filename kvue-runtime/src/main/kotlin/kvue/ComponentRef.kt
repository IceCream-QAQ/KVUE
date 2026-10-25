package kvue

import kvue.reactive.ReadRef

/** Create once during setup. Vue fills it on mount and clears it on unmount. */
class ComponentRef<T>(private val adapt: (dynamic) -> T) : ReadRef<T?>(vue.shallowRef<T?>(null)) {
    private var previous: dynamic = null

    internal val callback: (dynamic) -> Unit = { instance ->
        val next = if (instance == null) null else instance
        if (previous !== next) {
            previous = next
            vueRef.unsafeCast<vue.Ref<T?>>().value = if (next == null) null else adapt(next)
        }
    }
}

/** A typed facade over Vue's public/exposed proxy, never its internal component instance. */
open class ComponentInstance(protected val rawInstance: dynamic) {
    /** Explicit escape hatch for APIs that cannot be described by the imported contract. */
    fun asDynamic(): dynamic = rawInstance

    protected fun invokeExposed(name: String, arguments: Array<out Any?>): dynamic {
        val function = rawInstance[name]
        check(jsTypeOf(function) == "function") { "Component does not expose callable '$name'." }
        val values = arguments.toMutableList()
        while (values.lastOrNull() === OptionalProp.Omitted) values.removeAt(values.lastIndex)
        val forwarded = values.map {
            when (it) {
                OptionalProp.Omitted -> js("undefined")
                is OptionalProp.Value<*> -> it.value
                else -> it
            }
        }.toTypedArray()
        val result = function.apply(rawInstance, forwarded)
        return if (jsTypeOf(result) == "undefined") null else result
    }

    protected fun readExposed(name: String): dynamic {
        val result = rawInstance[name]
        return if (jsTypeOf(result) == "undefined") null else result
    }

    protected fun writeExposed(name: String, value: Any?) { rawInstance[name] = value }
}

/** Generated native bridges use accessors so exposed properties remain live. */
fun defineExposedProperty(target: dynamic, name: String, get: () -> Any?, set: ((Any?) -> Unit)? = null) {
    val descriptor: dynamic = js("({ enumerable: true, configurable: true })")
    descriptor.get = get
    if (set != null) descriptor.set = set
    js("Object.defineProperty")(target, name, descriptor)
}
