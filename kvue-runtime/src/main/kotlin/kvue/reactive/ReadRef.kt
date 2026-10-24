package kvue.reactive

import kotlin.reflect.KProperty

/** A ref handle that can also back a Kotlin val, including local and top-level properties. */
abstract class ReadRef<out T> internal constructor(internal val vueRef: vue.Ref<*>) {
    init {
        // Vue must not proxy this Kotlin facade and auto-unwrap its internal native ref field.
        vue.markRaw(this)
    }

    open val value: T get() = vueRef.value.unsafeCast<T>()

    operator fun getValue(thisRef: Any?, property: KProperty<*>): T = value

    /** Explicit escape hatch for native Vue / third-party JavaScript interop. */
    open fun asVueRef(): vue.Ref<out T> = vueRef.unsafeCast<vue.Ref<T>>()
}

class ReadonlyRef<T> internal constructor(ref: vue.Ref<T>) : ReadRef<T>(ref)
