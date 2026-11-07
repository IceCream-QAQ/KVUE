package kvue.vdom

import kvue.VueComponentType
import kvue.annotation.SlotDeclaration
import kvue.`fun`.jso
import kvue.vdom.knode.ElementNode
import org.w3c.dom.Element

/** An explicit group retains its identity when its number of children changes. */
fun RenderGroup.Fragment(key: Any? = null, content: RenderFun): ElementNode =
    builtIn(vue.Fragment, mapOf("key" to key), content)

fun RenderGroup.Teleport(
    to: String,
    disabled: Boolean = false,
    defer: Boolean = false,
    key: Any? = null,
    content: RenderFun,
): ElementNode = builtIn(vue.Teleport, mapOf("to" to to, "disabled" to disabled, "defer" to defer, "key" to key), content)

fun RenderGroup.Teleport(
    to: Element,
    disabled: Boolean = false,
    defer: Boolean = false,
    key: Any? = null,
    content: RenderFun,
): ElementNode = builtIn(vue.Teleport, mapOf("to" to to, "disabled" to disabled, "defer" to defer, "key" to key), content)

class KeepAliveFilter private constructor(internal val value: Any) {
    companion object {
        fun names(vararg names: String): KeepAliveFilter = KeepAliveFilter(names)
        /** Uses native JavaScript regular-expression syntax and flags. */
        fun pattern(pattern: String, flags: String = ""): KeepAliveFilter = KeepAliveFilter(js("new RegExp(pattern, flags)"))
        fun anyOf(vararg filters: KeepAliveFilter): KeepAliveFilter = KeepAliveFilter(filters.flatMap {
            val value = it.value
            if (value is Array<*>) value.asList() else listOf(value)
        }.toTypedArray())
    }
}

fun RenderGroup.KeepAlive(
    include: KeepAliveFilter? = null,
    exclude: KeepAliveFilter? = null,
    max: Int? = null,
    key: Any? = null,
    content: RenderFun,
): ElementNode {
    require(max == null || max > 0) { "KeepAlive max must be positive." }
    return builtIn(vue.KeepAlive, mapOf("include" to include?.value, "exclude" to exclude?.value, "max" to max, "key" to key), content)
}

enum class TransitionMode(internal val value: String) { Default("default"), InOut("in-out"), OutIn("out-in") }
enum class TransitionType(internal val value: String) { Transition("transition"), Animation("animation") }

data class TransitionDuration(val enter: Int, val leave: Int = enter) {
    init { require(enter >= 0 && leave >= 0) { "Transition durations must be non-negative." } }
}

data class TransitionClasses(
    val enterFrom: String? = null,
    val enterActive: String? = null,
    val enterTo: String? = null,
    val appearFrom: String? = null,
    val appearActive: String? = null,
    val appearTo: String? = null,
    val leaveFrom: String? = null,
    val leaveActive: String? = null,
    val leaveTo: String? = null,
)

/** Enter, leave and appear callbacks with a done argument must call it when their animation finishes. */
data class TransitionHooks(
    val onBeforeEnter: ((Element) -> Unit)? = null,
    val onEnter: ((Element, () -> Unit) -> Unit)? = null,
    val onAfterEnter: ((Element) -> Unit)? = null,
    val onEnterCancelled: ((Element) -> Unit)? = null,
    val onBeforeLeave: ((Element) -> Unit)? = null,
    val onLeave: ((Element, () -> Unit) -> Unit)? = null,
    val onAfterLeave: ((Element) -> Unit)? = null,
    val onLeaveCancelled: ((Element) -> Unit)? = null,
    val onBeforeAppear: ((Element) -> Unit)? = null,
    val onAppear: ((Element, () -> Unit) -> Unit)? = null,
    val onAfterAppear: ((Element) -> Unit)? = null,
    val onAppearCancelled: ((Element) -> Unit)? = null,
)

fun RenderGroup.Transition(
    name: String? = null,
    mode: TransitionMode? = null,
    appear: Boolean = false,
    css: Boolean = true,
    type: TransitionType? = null,
    duration: TransitionDuration? = null,
    classes: TransitionClasses = TransitionClasses(),
    hooks: TransitionHooks = TransitionHooks(),
    persisted: Boolean = false,
    key: Any? = null,
    content: RenderFun,
): ElementNode = builtIn(vue.Transition,
    transitionProps(name, appear, css, type, duration, classes, hooks) +
        mapOf("mode" to mode?.value, "persisted" to persisted, "key" to key), content)

fun RenderGroup.TransitionGroup(
    name: String? = null,
    tag: String? = null,
    moveClass: String? = null,
    appear: Boolean = false,
    css: Boolean = true,
    type: TransitionType? = null,
    duration: TransitionDuration? = null,
    classes: TransitionClasses = TransitionClasses(),
    hooks: TransitionHooks = TransitionHooks(),
    key: Any? = null,
    content: RenderFun,
): ElementNode = builtIn(vue.TransitionGroup,
    transitionProps(name, appear, css, type, duration, classes, hooks) +
        mapOf("tag" to tag, "moveClass" to moveClass, "key" to key), content)

class SuspenseContent internal constructor() : ComponentContentScope() {
    val slots: SuspenseSlots = SuspenseSlots(this)
}

class SuspenseSlots internal constructor(private val scope: SuspenseContent) {
    @SlotDeclaration("default")
    fun default(content: SlotContent<SlotScope>) = scope.slot("default", content)

    @SlotDeclaration("fallback")
    fun fallback(content: SlotContent<SlotScope>) = scope.slot("fallback", content)
}

fun RenderGroup.Suspense(
    timeout: Int? = null,
    suspensible: Boolean = false,
    onPending: (() -> Unit)? = null,
    onResolve: (() -> Unit)? = null,
    onFallback: (() -> Unit)? = null,
    key: Any? = null,
    content: ComponentContent<SuspenseContent>,
): ElementNode = ElementNode(vue.Suspense).apply {
    configure(mapOf("timeout" to timeout, "suspensible" to suspensible, "onPending" to onPending,
        "onResolve" to onResolve, "onFallback" to onFallback, "key" to key))
    bindComponentContent(this, content, ::SuspenseContent)
    addElement(this)
}

/** Renders a reusable Kotlin, imported, or async definition selected at runtime. */
fun RenderGroup.component(
    type: VueComponentType,
    props: Map<String, Any?> = emptyMap(),
    key: Any? = null,
    content: ComponentContent<ComponentContentScope>? = null,
): ElementNode = componentNode(type.vue, props, key, content)

/** Resolves an application-registered component name, or a native tag, in the current render. */
fun RenderGroup.component(
    name: String,
    props: Map<String, Any?> = emptyMap(),
    key: Any? = null,
    content: ComponentContent<ComponentContentScope>? = null,
): ElementNode = componentNode(vue.resolveDynamicComponent(name), props, key, content)

private fun RenderGroup.componentNode(
    type: dynamic,
    props: Map<String, Any?>,
    key: Any?,
    content: ComponentContent<ComponentContentScope>?,
): ElementNode = ElementNode(type).apply {
    // Unlike optional built-in options, explicit null props must be retained.
    props.forEach { (name, value) -> property(name, value) }
    if (key != null) property("key", key)
    bindComponentContent(this, content, ::ComponentContentScope)
    addElement(this)
}

private fun RenderGroup.builtIn(type: dynamic, props: Map<String, Any?>, content: RenderFun): ElementNode =
    ElementNode(type).apply {
        configure(props)
        slot("default", content)
        addElement(this)
    }

private fun ElementNode.configure(props: Map<String, Any?>) {
    props.forEach { (name, value) -> if (value != null) property(name, value) }
}

private fun transitionProps(
    name: String?, appear: Boolean, css: Boolean, type: TransitionType?, duration: TransitionDuration?,
    classes: TransitionClasses, hooks: TransitionHooks,
): Map<String, Any?> = mapOf(
    "name" to name, "appear" to appear, "css" to css, "type" to type?.value,
    "duration" to duration?.let { jso("enter" to it.enter, "leave" to it.leave) },
    "enterFromClass" to classes.enterFrom, "enterActiveClass" to classes.enterActive, "enterToClass" to classes.enterTo,
    "appearFromClass" to classes.appearFrom, "appearActiveClass" to classes.appearActive, "appearToClass" to classes.appearTo,
    "leaveFromClass" to classes.leaveFrom, "leaveActiveClass" to classes.leaveActive, "leaveToClass" to classes.leaveTo,
    "onBeforeEnter" to hooks.onBeforeEnter, "onEnter" to hooks.onEnter, "onAfterEnter" to hooks.onAfterEnter,
    "onEnterCancelled" to hooks.onEnterCancelled, "onBeforeLeave" to hooks.onBeforeLeave, "onLeave" to hooks.onLeave,
    "onAfterLeave" to hooks.onAfterLeave, "onLeaveCancelled" to hooks.onLeaveCancelled,
    "onBeforeAppear" to hooks.onBeforeAppear, "onAppear" to hooks.onAppear, "onAfterAppear" to hooks.onAfterAppear,
    "onAppearCancelled" to hooks.onAppearCancelled,
)
