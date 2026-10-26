package kvue.vdom

import kvue.annotation.SlotDeclaration
import kvue.vdom.knode.ElementNode

/** Component bodies have their own receiver; HTML and slot render bodies still use RenderGroup. */
fun interface ComponentContent<in S : ComponentContentScope> {
    operator fun S.invoke()
}

@KVueDsl
open class ComponentContentScope : RenderGroup() {
    internal var target: ElementNode? = null
    internal var scopeFactory: (() -> ComponentContentScope)? = null
    internal val declaredSlots = mutableSetOf<String>()

    @SlotDeclaration
    fun slot(name: String, content: RenderFun) {
        require(name.isNotBlank()) { "A slot name cannot be empty." }
        val node = checkNotNull(target) {
            "Inline slot declarations need the KVUE compiler plugin and must be directly inside component content."
        }
        declaredSlots += name
        node.slot(name, content)
    }
}

private class PreparedComponentContent<S : ComponentContentScope>(
    private val block: ComponentContent<S>,
) : ComponentContent<S> {
    override fun S.invoke() {
        val receiver = this
        with(block) { receiver.invoke() }
    }
}

/** Used by generated DSL implementations; a plain body remains an entirely lazy default slot. */
fun <S : ComponentContentScope> bindComponentContent(
    node: ElementNode,
    content: ComponentContent<S>?,
    factory: () -> S,
) {
    if (content == null) return
    if (content is PreparedComponentContent<*>) {
        val scope = factory().apply {
            target = node
            scopeFactory = factory
        }
        with(content) { scope.invoke() }
    } else {
        bindDefaultContent(node, content, factory)
    }
}

/** Compiler entry point: the block contains slot registrations, never eager default rendering. */
fun <S : ComponentContentScope> preparedComponentContent(content: ComponentContent<S>): ComponentContent<S> =
    PreparedComponentContent(content)

/** Compiler entry point for the ordinary statements remaining after named slots are extracted. */
fun <S : ComponentContentScope> componentDefaultContent(scope: S, content: ComponentContent<S>) {
    check("default" !in scope.declaredSlots) { "Use either an explicit default slot or ordinary default content, not both." }
    @Suppress("UNCHECKED_CAST")
    val factory = checkNotNull(scope.scopeFactory) as () -> S
    bindDefaultContent(checkNotNull(scope.target), content, factory)
}

private fun <S : ComponentContentScope> bindDefaultContent(
    node: ElementNode,
    content: ComponentContent<S>,
    factory: () -> S,
) {
    node.slot("default", RenderFun {
        val scope = factory()
        with(content) { scope.invoke() }
        scope.result().forEach(::addElement)
    })
}

/** Adapts a reusable legacy render block without changing its lazy behavior. */
fun <S : ComponentContentScope> RenderFun.asComponentContent(): ComponentContent<S> {
    val block = this
    return ComponentContent { with(block) { invoke() } }
}
