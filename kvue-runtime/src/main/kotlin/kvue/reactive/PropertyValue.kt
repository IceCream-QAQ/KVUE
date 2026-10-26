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
        val context = thisRef.instanceContext
        val value = thisRef.vueProps[context.propNames[property.name] ?: property.name]
        if (jsTypeOf(value) != "undefined") return value.unsafeCast<T>()
        check(hasDefault) {
            if (property.name in context.requiredProps) "Required prop '${property.name}' was not provided."
            else "Optional prop '${property.name}' has no default value. Use property(default), or mark it @Prop(required = true)."
        }
        return defaultValue.unsafeCast<T>()
    }
}
