package kvue

import kvue.`fun`.jso
import kvue.vdom.buildVNodes
import vue.VueRenderFun
import vue.VueSetupFun
import vue.cloneVNode
import vue.createCommentVNode
import vue.createVNode
import vue.Fragment

data class ComponentProp(val name: String, val required: Boolean = false, val propertyName: String = name)

/** Context belongs to one Vue instance, including callbacks invoked after setup has returned. */
class ComponentContext internal constructor(
    val props: dynamic,
    internal val setupContext: dynamic,
    internal val propNames: Map<String, String>,
    internal val requiredProps: Set<String>,
) {
    internal var events: Map<String, Any> = emptyMap()

    fun emit(name: String, vararg arguments: Any?) {
        setupContext.emit.apply(setupContext, arrayOf(name, *arguments))
    }
}

/** One generated definition can be reused by any number of independent Vue instances. */
class ComponentDefinition<T : Component>(
    name: String,
    props: Array<ComponentProp> = emptyArray(),
    emits: Array<String> = emptyArray(),
    factory: () -> T,
    bindEvents: (ComponentContext) -> Map<String, Any> = { emptyMap() },
    expose: ((T) -> dynamic)? = null,
) : VueComponentType {
    override val vue: dynamic

    init {
        val vueProps = jso()
        props.forEach { vueProps[it.name] = jso("required" to it.required) }
        val propNames = props.associate { prop ->
            prop.propertyName to prop.name.replace(Regex("-(\\w)")) { it.groupValues[1].uppercase() }
        }
        val requiredProps = props.filter { it.required }.mapTo(mutableSetOf()) { it.propertyName }

        val setup: VueSetupFun = { inputProps, setupContext ->
            val context = ComponentContext(inputProps, setupContext, propNames, requiredProps)
            context.events = bindEvents(context)
            val instance = ComponentConstruction.create(context, factory)
            if (expose != null) setupContext.expose(expose(instance))
            val render: VueRenderFun = {
                val nodes = instance.body.buildVNodes()
                // A single root must remain a real VNode so Vue can apply directives and transitions.
                // Callers needing a stable group across root-count changes can render Fragment explicitly.
                if (nodes.size == 1 && (nodes[0].asDynamic().shapeFlag.unsafeCast<Int>() and 7) != 0) {
                    val attrs = setupContext.attrs
                    val forwarded = jso()
                    js("Object.keys")(attrs).unsafeCast<Array<String>>().forEach { key ->
                        if (!key.startsWith("onUpdate:") || key.removePrefix("onUpdate:") !in propNames.values) {
                            forwarded[key] = attrs[key]
                        }
                    }
                    nodes[0] = cloneVNode(nodes[0], forwarded)
                }
                when (nodes.size) {
                    0 -> createCommentVNode()
                    1 -> nodes[0]
                    else -> createVNode(Fragment, null, nodes)
                }
            }
            render
        }

        vue = jso("name" to name, "props" to vueProps, "emits" to emits, "setup" to setup, "inheritAttrs" to false)
    }
}

internal object ComponentConstruction {
    private var current: ComponentContext? = null

    fun requireContext(): ComponentContext = checkNotNull(current) {
        "Create components through their generated RenderGroup function or XxxVue.definition."
    }

    fun <T : Component> create(context: ComponentContext, factory: () -> T): T {
        val previous = current
        current = context
        return try {
            factory()
        } finally {
            current = previous
        }
    }
}
