package kvue.vdom.knode

import kvue.VueComponentType
import kvue.`fun`.toJavaScriptObject
import kvue.vdom.RenderFun
import kvue.vdom.buildVNodes
import vue.VNode
import vue.createVNode

class ElementNode(
    private val type: dynamic
) : KNode {

    private val properties = linkedMapOf<String, Any?>()
    private val children = linkedMapOf<String, MutableList<RenderFun>>()

    fun property(name: String, value: Any?) {
        properties[name] = value
    }

    fun onClick(value: () -> Unit) {
        properties["onClick"] = value
    }

    fun slot(name: String, renderFun: RenderFun) {
        children.getOrPut(name) { mutableListOf() }.add(renderFun)
    }


    override fun buildVNode(): VNode {
        val vueChildren: dynamic = if (type is String) {
            check(children.keys.all { it == "default" }) { "HTML elements only have a default slot." }
            children["default"]?.flatMap { it.buildVNodes().asList() }?.toTypedArray()
        } else {
            children.mapValues { (_, blocks) ->
                { blocks.flatMap { it.buildVNodes().asList() }.toTypedArray() }
            }.toJavaScriptObject()
        }
        return createVNode(
            if (type is VueComponentType) type.vue else type,
            properties.toJavaScriptObject(),
            vueChildren,
        )
    }

}
