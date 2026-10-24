package kvui.fixtures

import kvue.Component
import kvue.annotation.VueComponent
import kvue.provided
import kvue.vdom.RenderFun
import kvui.*

@VueComponent
class DomStructureHost : Component() {
    private var changed by ref(false)
    private var eventLog by ref("")

    override val body = RenderFun {
        main(id = "dom-root", className = "page", style = "color: rgb(10, 20, 30)") {
            h1 { text("<b>plain text</b>") }
            div(id = "attributes", className = if (changed) "after" else "before", hidden = changed,
                title = if (changed) null else "hint", attributes = mapOf("data-probe" to "custom", "aria-label" to "accessible", "class" to "fallback"))
            label(htmlFor = "plain-input") { text("Name") }
            input(id = "plain-input", value = "uncontrolled", disabled = changed, readOnly = false)
            button(id = "change-dom", type = "button", onClick = { event ->
                eventLog = event.type
                changed = !changed
            }) { text("Change") }
            form(id = "dom-form", onSubmit = { event -> event.preventDefault(); eventLog = event.type }) {
                button(type = "submit") { text("Submit") }
            }
            p(id = "dom-event") { text(eventLog) }
            ul {
                for (item in if (changed) listOf("b", "a") else listOf("a", "b")) {
                    li(key = item, attributes = mapOf("data-key" to item)) { text(item) }
                }
            }
            img(id = "dom-image", alt = "description", width = 24, height = 16)
            br()
            hr()
            a(id = "dom-link", href = "#dom-root") { text("Back") }
            element("kvui-example", attributes = mapOf("data-custom" to "yes")) { text("custom element") }
        }
    }
}

@VueComponent
class DomTextHost : Component() {
    private var message by ref<String?>(null)
    private var inputEvents by ref(0)

    override val body = RenderFun {
        input(id = "bound-text", vModel = message, placeholder = "Type", onInput = { inputEvents++ })
        textarea(id = "bound-textarea", bindModelValue = message, rows = 3)
        p(id = "text-model") { text(message ?: "<null>") }
        p(id = "input-events") { text(inputEvents.toString()) }
        button(id = "replace-text", onClick = { message = "parent" }) { text("Replace") }
        button(id = "clear-text", onClick = { message = null }) { text("Clear") }
    }
}

@VueComponent
class DomChoiceHost : Component() {
    private var checked by ref(false)
    private var choice by ref("a")
    private var single by ref("first")
    private var multi by ref(arrayOf("first"))
    private var checks by ref(arrayOf("a"))

    override val body = RenderFun {
        input(id = "bound-checkbox", type = "checkbox", vModel = checked)
        input(id = "radio-a", type = "radio", name = "choice", value = "a", vModel = choice)
        input(id = "radio-b", type = "radio", name = "choice", value = "b", bindModelValue = choice)
        input(id = "check-a", type = "checkbox", value = "a", vModel = checks)
        input(id = "check-b", type = "checkbox", value = "b", vModel = checks)
        select(id = "single-select", vModel = single) {
            option(value = "first") { text("First") }
            option(value = "second") { text("Second") }
        }
        select(id = "multi-select", multiple = true, bindModelValue = multi) {
            option(value = "first") { text("First") }
            option(value = "second") { text("Second") }
        }
        p(id = "choice-model") { text("$checked|$choice|$single|${multi.joinToString()}|${checks.joinToString()}") }
        button(id = "reset-choices", onClick = {
            checked = false; choice = "a"; single = "first"; multi = arrayOf("first"); checks = arrayOf("a")
        }) { text("Reset") }
    }
}

@VueComponent
class DomModifiersHost : Component() {
    private var lazyText by ref("seed")
    private var numeric by ref<Any?>(2.0)
    private var direct by ref("direct")
    private var replacement by ref(false)

    override val body = RenderFun {
        input(id = "lazy-text", vModel = lazyText, modelModifiers = ModelModifiers(lazy = true, trim = true))
        input(id = "numeric-text", type = "number", vModel = numeric)
        textarea(id = "direct-text", modelValue = provided(direct), onUpdateModelValue = if (replacement) {
            { value -> direct = "new:$value" }
        } else {
            { value -> direct = "old:$value" }
        })
        button(id = "replace-handler", onClick = { replacement = true }) { text("Replace handler") }
        p(id = "modifier-model") { text("$lazyText|$numeric|${numeric is Number}|$direct") }
    }
}
