package kvue.vdom

import kvue.`fun`.push
import kvue.vdom.knode.KNode

value class RenderContext(private val elements: Array<KNode> = arrayOf()) {
    internal fun result() = elements
    fun addElement(element: KNode) {
        elements.push(element)
    }
}
