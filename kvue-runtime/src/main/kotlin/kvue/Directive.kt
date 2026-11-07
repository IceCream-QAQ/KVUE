package kvue

import kvue.`fun`.jso
import kvue.vdom.KVueDsl
import kvue.vdom.knode.ElementNode
import org.w3c.dom.Element
import vue.VNode

/** A reusable directive definition. Create it once, outside render callbacks. */
class Directive<T> internal constructor(private val native: vue.Directive) {
    fun asVueDirective(): vue.Directive = native
}

class DirectiveBinding<T> internal constructor(private val native: dynamic) {
    val value: T get() = native.value.unsafeCast<T>()
    val oldValue: T? get() = if (native.oldValue == null) null else native.oldValue.unsafeCast<T>()
    val argument: String? get() = if (native.arg == null) null else native.arg.unsafeCast<String>()
    val modifiers: Map<String, Boolean> get() = js("Object.keys")(native.modifiers)
        .unsafeCast<Array<String>>().associateWith { native.modifiers[it] == true }
    val instance: dynamic get() = native.instance
}

typealias DirectiveHook<T> = (element: Element, binding: DirectiveBinding<T>, vnode: VNode, previousVNode: VNode?) -> Unit

@KVueDsl
class DirectiveHooks<T> internal constructor() {
    var deep: Boolean = false
    var created: DirectiveHook<T>? = null
    var beforeMount: DirectiveHook<T>? = null
    var mounted: DirectiveHook<T>? = null
    var beforeUpdate: DirectiveHook<T>? = null
    var updated: DirectiveHook<T>? = null
    var beforeUnmount: DirectiveHook<T>? = null
    var unmounted: DirectiveHook<T>? = null
}

fun <T> directive(block: DirectiveHooks<T>.() -> Unit): Directive<T> {
    val hooks = DirectiveHooks<T>().apply(block)
    val native = jso("deep" to hooks.deep)
    fun register(name: String, hook: DirectiveHook<T>?) {
        if (hook != null) {
            native[name] = { element: Element, binding: dynamic, vnode: VNode, previous: dynamic ->
                hook(element, DirectiveBinding(binding), vnode, if (previous == null) null else previous.unsafeCast<VNode>())
            }
        }
    }
    register("created", hooks.created)
    register("beforeMount", hooks.beforeMount)
    register("mounted", hooks.mounted)
    register("beforeUpdate", hooks.beforeUpdate)
    register("updated", hooks.updated)
    register("beforeUnmount", hooks.beforeUnmount)
    register("unmounted", hooks.unmounted)
    return Directive(native.unsafeCast<vue.Directive>())
}

/** Preserves generated node methods such as onChanged() and ref(). */
fun <N : ElementNode, T> N.directive(
    directive: Directive<T>,
    value: T,
    argument: String? = null,
    modifiers: Map<String, Boolean> = emptyMap(),
): N = apply { directive(directive.asVueDirective(), value, argument, modifiers) }

fun <N : ElementNode> N.directive(directive: Directive<Unit>): N = directive(directive, Unit)

fun <T> vue.App.directive(name: String, directive: Directive<T>): vue.App = directive(name, directive.asVueDirective())

/** Vue v-show retains DOM and component state, and restores the element's display style. */
fun <N : ElementNode> N.show(visible: Boolean): N = apply { directive(vue.vShow, visible) }
