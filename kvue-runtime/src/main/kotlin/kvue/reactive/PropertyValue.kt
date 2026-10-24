package kvue.reactive

import kvue.Component
import kotlin.reflect.KProperty

class PropertyValue<T> private constructor(
    private val hasDefault: Boolean,
    private val defaultValue: T?,
) {
    constructor(defaultValue: T) : this(true, defaultValue)
    constructor() : this(false, null)

    operator fun getValue(thisRef: Component, property: KProperty<*>): T {
        val value = thisRef.vueProps[thisRef.instanceContext.propNames[property.name] ?: property.name]
        if (jsTypeOf(value) != "undefined") return value.unsafeCast<T>()
        check(hasDefault) { "Required prop '${property.name}' was not provided." }
        return defaultValue.unsafeCast<T>()
    }
}
