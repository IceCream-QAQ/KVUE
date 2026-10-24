package kvue.reactive

import kvue.Component
import kotlin.reflect.KProperty

class RefValue<T>(value: T) {
    private val ref = vue.ref(value)

    operator fun getValue(thisRef: Component, property: KProperty<*>): T {
        return ref.value.unsafeCast<T>()
    }

    operator fun setValue(thisRef: Component, property: KProperty<*>, value: T) {
        ref.value = value
    }
}
