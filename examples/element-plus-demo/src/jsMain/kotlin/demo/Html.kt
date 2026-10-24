package demo

import kvue.vdom.RenderFun
import kvue.vdom.RenderGroup
import kvue.vdom.knode.ElementNode
import kvue.vdom.knode.StringKNode

fun RenderGroup.text(value: String) = addElement(StringKNode(value))

fun RenderGroup.tag(name: String, className: String? = null, content: RenderFun) {
    addElement(ElementNode(name).apply {
        className?.let { property("class", it) }
        slot("default", content)
    })
}
