package kvui

import kvue.OptionalProp
import kvue.vdom.RenderFun
import kvue.vdom.RenderGroup
import kvue.vdom.knode.ElementNode
import kvue.vdom.knode.StringKNode

/** Vue's native form modifiers; number conversion may still produce a String for empty/invalid input. */
data class ModelModifiers(val lazy: Boolean = false, val trim: Boolean = false, val number: Boolean = false)

/** Adds an escaped text node, never HTML markup. */
fun RenderGroup.text(value: String) = addElement(StringKNode(value))

/** Escape hatch for custom elements and attributes not covered by the standard tag functions. */
fun RenderGroup.element(
    tag: String,
    attributes: Map<String, Any?> = emptyMap(),
    key: Any? = null,
    content: RenderFun? = null,
): ElementNode = htmlElement(tag, attributes, arrayOf("key" to key), content)

private val voidElements = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")

internal fun RenderGroup.htmlElement(
    tag: String,
    attributes: Map<String, Any?>,
    properties: Array<out Pair<String, Any?>>,
    content: RenderFun?,
    modelValue: OptionalProp<Any?> = OptionalProp.Omitted,
    onUpdateModelValue: ((Any?) -> Unit)? = null,
    modelModifiers: ModelModifiers = ModelModifiers(),
): ElementNode {
    require(tag.isNotBlank()) { "An HTML tag name must not be blank." }
    require(content == null || tag.lowercase() !in voidElements) { "<$tag> cannot have children." }
    // Explicit non-null parameters take precedence; null entries in attributes are preserved.
    val props = attributes.toMutableMap()
    for ((name, value) in properties) if (value != null) props[name] = value
    require(modelValue != OptionalProp.Omitted || onUpdateModelValue == null) {
        "onUpdateModelValue requires modelValue; use vModel or bindModelValue for a writable property."
    }
    return ElementNode(tag).apply {
        for ((name, value) in props) property(name, value)
        if (modelValue is OptionalProp.Value) {
            require(tag != "input" || props["type"]?.toString()?.lowercase() != "file") {
                "File inputs do not support vModel; read the files in onChange instead."
            }
            // This callback is consumed by the directive, not registered as a DOM event listener.
            property("onUpdate:modelValue", onUpdateModelValue ?: { _: Any? -> })
            directive(vModelDynamic, modelValue.value, modifiers = mapOf(
                "lazy" to modelModifiers.lazy, "trim" to modelModifiers.trim, "number" to modelModifiers.number,
            ))
        }
        content?.let { slot("default", it) }
        addElement(this)
    }
}
