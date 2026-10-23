package kvue.vdom.knode

import kvue.Component
import kvue.`fun`.toJavaScriptObject
import kvue.vdom.RenderContext
import kvue.vdom.RenderFun
import vue.VNode
import vue.createVNode

class ElementNode(
    private val type: dynamic
) : KNode {

    private val properties = HashMap<String, Any?>()
    private val children = HashMap<String, Array<KNode>>()

    fun property(name: String, value: Any?) {
        properties[name] = value
    }

    fun onClick(value: () -> Unit) {
        properties["onClick"] = value
    }

    fun slot(name: String, renderFun: RenderFun) {
        renderFun.apply { RenderContext(children.getOrPut(name) { arrayOf() }).apply { invoke() } }
    }


    override fun buildVNode(): VNode =
        createVNode(
            if (type is Component) type.buildVueComponent() else type,
            properties.toJavaScriptObject(),
            children
                .asSequence()
                .filter { (_, v) -> v.isNotEmpty() }
                .map { (k, v) -> k to { v.map { it.buildVNode() }.toTypedArray() } }
                .toMap()
                .toJavaScriptObject()
        )

}