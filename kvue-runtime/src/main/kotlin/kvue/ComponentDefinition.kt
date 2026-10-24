package kvue

import kvue.`fun`.jso
import kvue.vdom.buildVNodes
import vue.VueRenderFun
import vue.VueSetupFun

data class ComponentProp(val name: String, val required: Boolean = false, val propertyName: String = name)

/** Context belongs to one Vue instance, including callbacks invoked after setup has returned. */
class ComponentContext internal constructor(
    val props: dynamic,
    internal val setupContext: dynamic,
    internal val propNames: Map<String, String>,
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
) : VueComponentType {
    override val vue: dynamic

    init {
        val vueProps = jso()
        props.forEach { vueProps[it.name] = jso("required" to it.required) }
        val propNames = props.associate { prop ->
            prop.propertyName to prop.name.replace(Regex("-(\\w)")) { it.groupValues[1].uppercase() }
        }

        val setup: VueSetupFun = { inputProps, setupContext ->
            val context = ComponentContext(inputProps, setupContext, propNames)
            context.events = bindEvents(context)
            val instance = ComponentConstruction.create(context, factory)
            val render: VueRenderFun = { instance.body.buildVNodes() }
            render
        }

        vue = jso("name" to name, "props" to vueProps, "emits" to emits, "setup" to setup)
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
