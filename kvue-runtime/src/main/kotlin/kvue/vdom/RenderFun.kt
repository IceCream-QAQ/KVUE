package kvue.vdom

import vue.VNode

fun interface RenderFun : SlotContent<SlotScope> {
    operator fun RenderGroup.invoke()

    // Reusable render blocks can still be passed to slot APIs without an adapter.
    override fun SlotScope.invoke() { (this as RenderGroup).invoke() }
}

fun RenderFun.buildVNodes(): Array<VNode> =
    run { RenderGroup().apply { invoke() } }.result().map { it.buildVNode() }.toTypedArray()
