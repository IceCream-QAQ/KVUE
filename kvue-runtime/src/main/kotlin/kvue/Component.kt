package kvue

import kvue.`fun`.toJavaScriptObject
import kvue.reactive.EventValue
import kvue.reactive.PropertyValue
import kvue.reactive.RefValue
import kvue.reactive.ReadonlyRef
import kvue.vdom.RenderFun
import kvue.vdom.RenderGroup
import kvue.vdom.SlotContent
import kvue.vdom.SlotScope
import kvue.vdom.buildVNodes
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

    /**
     * Invokes a Vue slot during this component's render, preserving lazy evaluation.
     * Props reach the slot function as one plain JS object, as with Vue's renderSlot.
     */
    protected fun RenderGroup.slot(
        name: String = "default",
        props: Map<String, Any?> = emptyMap(),
        fallback: RenderFun? = null,
    ) {
        val slotFunction = instanceContext.setupContext.slots[name]
        val nodes = if (jsTypeOf(slotFunction) == "function")
            slotFunction(props.toJavaScriptObject()).unsafeCast<Array<VNode>>() else emptyArray()
        if (fallback == null || nodes.any(::hasSlotContent)) nodes.forEach(::addVNode)
        else fallback.buildVNodes().forEach(::addVNode)
    }

    /** Keeps a misplaced slot declaration from resolving to this component's own slot outlet. */
    @Deprecated(
        "slot(name) { ... } declares a slot for a child component and must be written directly in that " +
            "component's content block. Call slot(name) without a block to render this component's own slot.",
        level = DeprecationLevel.ERROR,
    )
    protected fun RenderGroup.slot(name: String, content: SlotContent<SlotScope>): Unit =
        error("Slot '$name' must be declared directly in component content.")
}

/** Like Vue's slot fallback check, comments and empty fragments do not count as content. */
private fun hasSlotContent(node: VNode): Boolean = when (node.asDynamic().type) {
    vue.Comment -> false
    vue.Fragment -> {
        val children: dynamic = node.asDynamic().children
        children is Array<*> && children.unsafeCast<Array<VNode>>().any(::hasSlotContent)
    }
    else -> true
}


