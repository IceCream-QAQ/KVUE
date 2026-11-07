package kvue.vdom.knode

import kvue.VueComponentType
import kvue.`fun`.toJavaScriptObject
import kvue.vdom.SlotContent
import kvue.vdom.SlotScope
import kvue.vdom.buildSlotVNodes
import vue.VNode
import vue.Directive
import vue.createVNode
import vue.withDirectives

open class ElementNode(
    private val type: dynamic
) : KNode {

    private val properties = linkedMapOf<String, Any?>()
    private val children = linkedMapOf<String, MutableList<(Any?) -> Array<VNode>>>()
    private val directives = mutableListOf<Array<Any?>>()

    fun property(name: String, value: Any?) {
        properties[name] = value
    }

    /** Listeners belong to this render's node; repeated registrations preserve their order. */
    fun listener(name: String, callback: Any) {
        val previous = properties[name]
        properties[name] = when (previous) {
            null -> callback
            is Array<*> -> previous + callback
            else -> arrayOf(previous, callback)
        }
    }

    fun mergeProperty(name: String, value: Any?) {
        val merged = vue.mergeProps(properties.toJavaScriptObject(), kvue.`fun`.jso(name to value))
        properties[name] = merged[name]
    }

    fun componentRef(target: kvue.ComponentRef<*>) {
        val previous = properties["ref"]
        require(previous == null || previous === target.callback) { "A component node can have only one instance ref." }
        properties["ref"] = target.callback
    }

    open fun slot(name: String, renderFun: SlotContent<SlotScope>): ElementNode = slot(name, ::SlotScope, renderFun)

    open fun <S : SlotScope> slot(name: String, factory: (Any?) -> S, content: SlotContent<S>): ElementNode {
        children.getOrPut(name) { mutableListOf() }.add { props -> content.buildSlotVNodes(props, factory) }
        return this
    }

    /** Applied while the VNode is built inside Vue's current render context. */
    fun directive(
        directive: Directive,
        value: Any? = null,
        argument: String? = null,
        modifiers: Map<String, Boolean> = emptyMap(),
    ) {
        directives.add(arrayOf(directive, value, argument, modifiers.toJavaScriptObject()))
    }

    override fun buildVNode(): VNode {
        val nodeType = if (type is VueComponentType) type.vue else type
        val arrayChildren = nodeType === vue.Fragment || nodeType === vue.Teleport
        val vueChildren: dynamic = if (nodeType is String || arrayChildren) {
            check(children.keys.all { it == "default" }) { "Elements, Fragment and Teleport only accept default content." }
            children["default"]?.flatMap { it(null).asList() }?.toTypedArray()
                ?: if (arrayChildren) emptyArray<VNode>() else null
        } else if (children.isEmpty()) {
            // Vue force-updates a component whenever render-function children are present, even an
            // empty slot object. Without slots, unchanged props let a parent render skip the child.
            null
        } else {
            children.mapValues { (_, blocks) ->
                { props: Any? -> blocks.flatMap { it(props).asList() }.toTypedArray() }
            }.toJavaScriptObject()
        }
        val vnode = createVNode(
            nodeType,
            properties.toJavaScriptObject(),
            vueChildren,
        )
        return if (directives.isEmpty()) vnode else withDirectives(vnode, directives.toTypedArray())
    }

}
