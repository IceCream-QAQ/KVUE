package kvue.reactive

import kvue.Component
import kotlin.reflect.KProperty

class PropertyValue<T>(private val defaultValue: T) {
    operator fun getValue(thisRef: Component, property: KProperty<*>): T {
        return (thisRef.vueProps[property.name] as? T) ?: defaultValue
    }

    operator fun setValue(thisRef: Component, property: KProperty<*>, value: T) {
        thisRef.vueProps[property.name] as T
    }
}