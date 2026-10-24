package kvue.reactive

import kvue.Component
import kotlin.reflect.KProperty

class EventValue<F> {
    @Suppress("UNCHECKED_CAST")
    operator fun getValue(thisRef: Component, property: KProperty<*>): F =
        checkNotNull(thisRef.instanceContext.events[property.name]) {
            "Event '${property.name}' has no generated binding. Add @Emit to its declaration."
        } as F
}
