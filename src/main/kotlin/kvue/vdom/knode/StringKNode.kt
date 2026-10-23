package kvue.vdom.knode

import vue.VNode

class StringKNode(private val str: String) : KNode {

    override fun buildVNode(): VNode = str as VNode

}