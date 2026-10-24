package kvue

import kvue.reactive.EventValue
import kvue.reactive.PropertyValue
import kvue.reactive.RefValue
import kvue.vdom.RenderFun
import kvue.vdom.RenderGroup
import vue.VNode

abstract class Component {
    internal val instanceContext = ComponentConstruction.requireContext()

    val vueProps: dynamic get() = instanceContext.props

    fun <T> ref(value: T) = RefValue(value)

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


