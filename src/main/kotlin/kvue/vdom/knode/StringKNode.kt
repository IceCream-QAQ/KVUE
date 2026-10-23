package kvue.vdom.knode

import vue.VNode
import vue.createTextVNode

class StringKNode(private val str: String) : KNode {

    override fun buildVNode(): VNode = createTextVNode(str)

}
