package kvue.vdom.knode

import vue.VNode

interface KNode {
    fun buildVNode(): VNode
}