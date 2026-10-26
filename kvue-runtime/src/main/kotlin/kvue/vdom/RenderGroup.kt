package kvue.vdom

import kvue.vdom.knode.KNode
import vue.VNode

@DslMarker
annotation class KVueDsl

@KVueDsl
open class RenderGroup {
    private val elements = mutableListOf<KNode>()

    fun addElement(element: KNode) {
        elements.add(element)
    }

    fun addVNode(vnode: VNode) {
        addElement(object : KNode {
            override fun buildVNode(): VNode = vnode
        })
    }

    internal fun result(): List<KNode> = elements
}
