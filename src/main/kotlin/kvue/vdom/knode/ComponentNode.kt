package kvue.vdom.knode

import kvue.VueComponentType
import vue.VNode
import vue.createVNode

class ComponentNode(
    private val component: VueComponentType
) : KNode {

    override fun buildVNode(): VNode =
        createVNode(component.vue, null, null)
}
