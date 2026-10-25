package kvue.vdom.knode

import kvue.VueComponentType
import kvue.`fun`.toJavaScriptObject
import kvue.vdom.RenderFun
import kvue.vdom.buildVNodes
import vue.VNode
import vue.Directive
import vue.createVNode
import vue.withDirectives

open class ElementNode(
    private val type: dynamic
) : KNode {

    private val properties = linkedMapOf<String, Any?>()
    private val children = linkedMapOf<String, MutableList<RenderFun>>()
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

    fun slot(name: String, renderFun: RenderFun) {
        children.getOrPut(name) { mutableListOf() }.add(renderFun)
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
        val vueChildren: dynamic = if (type is String) {
            check(children.keys.all { it == "default" }) { "HTML elements only have a default slot." }
            children["default"]?.flatMap { it.buildVNodes().asList() }?.toTypedArray()
        } else {
            children.mapValues { (_, blocks) ->
                { blocks.flatMap { it.buildVNodes().asList() }.toTypedArray() }
            }.toJavaScriptObject()
        }
        val vnode = createVNode(
            if (type is VueComponentType) type.vue else type,
            properties.toJavaScriptObject(),
            vueChildren,
        )
        return if (directives.isEmpty()) vnode else withDirectives(vnode, directives.toTypedArray())
    }

}
