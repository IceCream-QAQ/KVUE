package kvue

import kvue.reactive.EventValue
import kvue.reactive.PropertyValue
import kvue.reactive.RefValue
import kvue.reactive.ReadonlyRef
import kvue.vdom.RenderFun
import kvue.vdom.RenderGroup
import vue.VNode

abstract class Component {
    internal val instanceContext = ComponentConstruction.requireContext()

    val vueProps: dynamic get() = instanceContext.props

    fun <T> ref(value: T): RefValue<T> = kvue.ref(value)

    fun <T> shallowRef(value: T): RefValue<T> = kvue.shallowRef(value)

    fun <T> computed(getter: () -> T): ReadonlyRef<T> = kvue.computed(getter)

    fun <T> computed(get: () -> T, set: (T) -> Unit): RefValue<T> = kvue.computed(get, set)

    fun <T> property(defaultValue: T): PropertyValue<T> = PropertyValue(defaultValue)

    fun <T> property(): PropertyValue<T> = PropertyValue()

    fun <F> event(): EventValue<F> = EventValue()

    abstract val body: RenderFun

    /** Invokes a Vue slot during this component's render, preserving lazy evaluation. */
    protected fun RenderGroup.slot(name: String = "default", props: dynamic = null) {
        val slotFunction = instanceContext.setupContext.slots[name]
        if (jsTypeOf(slotFunction) == "function") {
            val nodes = slotFunction(props).unsafeCast<Array<VNode>>()
            nodes.forEach { addVNode(it) }
        }
    }
}


