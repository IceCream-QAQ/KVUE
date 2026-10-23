package kvue.vdom.knode

import kvue.Component
import vue.VNode
import vue.createVNode

class ComponentNode(
    private val component: Component
) : KNode {

    override fun buildVNode(): VNode =
        createVNode(component, null, null)
}