package kvue.reactive

import kvue.Component
import vue.Ref
import kotlin.reflect.KProperty

class RefValue<T>(val defaultValue: T) {

    lateinit var ref: Ref<*>

    operator fun getValue(thisRef: Component, property: KProperty<*>): T {
        return ref.value as T
    }

    operator fun setValue(thisRef: Component, property: KProperty<*>, value: T) {
        ref.value = value
    }
}