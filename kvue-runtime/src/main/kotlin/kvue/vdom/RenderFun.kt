package kvue.vdom

import vue.VNode

fun interface RenderFun {
    operator fun RenderGroup.invoke()
}

fun RenderFun.buildVNodes(): Array<VNode> =
    run { RenderGroup().apply { invoke() } }.result().map { it.buildVNode() }.toTypedArray()
