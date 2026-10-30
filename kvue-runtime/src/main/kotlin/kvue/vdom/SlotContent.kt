package kvue.vdom

import vue.VNode

/** A fresh receiver is created for every invocation of a Vue slot. */
fun interface SlotContent<in S : SlotScope> {
    operator fun S.invoke()
}

@KVueDsl
open class SlotScope(props: Any?) : RenderGroup() {
    /** The original JS slot-props object, including fields without a static declaration. */
    val slotProps: dynamic = props

    fun <T> slotProp(name: String): T {
        val value: Any? = if (slotProps == null) null else slotProps[name]
        // JS optional fields are undefined. Kotlin nullable fields should expose null.
        return if (value == null) null.unsafeCast<T>() else value.unsafeCast<T>()
    }
}

/** Invoked inside Vue's slot callback, so reads stay part of the child's render effect. */
internal fun <S : SlotScope> SlotContent<S>.buildSlotVNodes(props: Any?, factory: (Any?) -> S): Array<VNode> {
    val scope = factory(props)
    with(this) { scope.invoke() }
    return scope.result().map { it.buildVNode() }.toTypedArray()
}
