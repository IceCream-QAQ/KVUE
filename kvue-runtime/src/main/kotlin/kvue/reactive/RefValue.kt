package kvue.reactive

import kotlin.reflect.KProperty

class RefValue<T> internal constructor(ref: vue.Ref<T>) : ReadRef<T>(ref) {
    constructor(value: T) : this(vue.ref(value))

    override var value: T
        get() = super.value
        set(value) { asVueRef().value = value }

    override fun asVueRef(): vue.Ref<T> = vueRef.unsafeCast<vue.Ref<T>>()

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) { this.value = value }
}
