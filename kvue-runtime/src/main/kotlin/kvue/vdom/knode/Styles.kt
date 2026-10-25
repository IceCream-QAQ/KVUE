package kvue.vdom.knode

import kvue.`fun`.toJavaScriptObject
import kvue.vdom.KVueDsl

@KVueDsl
class CssClasses {
    internal val names = mutableListOf<String>()
    operator fun String.unaryPlus() { if (isNotBlank()) names += this }
    fun add(name: String, condition: Boolean = true) { if (condition && name.isNotBlank()) names += name }
}

@KVueDsl
class InlineStyle {
    internal val values = linkedMapOf<String, Any?>()
    operator fun set(name: String, value: Any?) { values[name] = value }
}

/** Adds CSS class names, including conditional names. Preserves the concrete node type. */
fun <N : ElementNode> N.css(block: CssClasses.() -> Unit): N = apply {
    mergeProperty("class", CssClasses().apply(block).names.toTypedArray())
}

/** Merges inline CSS properties; later values of the same property win. */
fun <N : ElementNode> N.style(block: InlineStyle.() -> Unit): N = apply {
    mergeProperty("style", InlineStyle().apply(block).values.toJavaScriptObject())
}
