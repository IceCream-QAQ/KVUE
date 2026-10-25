package kvue.importer.fixtures

import kvue.importer.fixtures.foreign.ForeignCommonJs
import kvue.importer.fixtures.foreign.ForeignCounter
import kvue.importer.fixtures.foreign.ForeignDefault
import kvue.importer.fixtures.foreign.ForeignFunctional
import kvue.importer.fixtures.foreign.ForeignOptions
import kvue.importer.fixtures.elementplus.ElButton
import kvue.importer.fixtures.elementplus.ElInput
import kvue.importer.fixtures.elementplus.ElInputVue
import kvue.importer.fixtures.elementplus.ElInputInstance
import kvue.importer.fixtures.foreign.ForeignCounterVue
import kvue.importer.fixtures.foreign.ForeignCounterInstance
import kvue.importer.fixtures.source.SourceCounterVue
import kvue.importer.fixtures.source.SourceCounterInstance
import kvue.importer.fixtures.source.SourceArrayVue
import kvue.importer.fixtures.source.SourceArrayInstance
import kvue.ComponentRef
import kvue.importer.fixtures.source.SourceArray
import kvue.importer.fixtures.source.SourceCommonJs
import kvue.importer.fixtures.source.SourceCounter
import kvue.importer.fixtures.source.SourceFunctional
import kvue.Component
import kvue.annotation.VueComponent
import kvue.provided
import kvue.vdom.RenderFun
import kvui.button
import kvui.div
import kvui.text
import kvue.vdom.knode.style

internal object ImportedRefProbe {
    lateinit var input: ComponentRef<ElInputInstance>
    lateinit var foreign: ComponentRef<ForeignCounterInstance>
    lateinit var source: ComponentRef<SourceCounterInstance>
    lateinit var options: ComponentRef<SourceArrayInstance>
    val events = mutableListOf<String>()
}

@VueComponent
internal class ForeignHost : Component() {
    private val child = ForeignCounterVue.ref().also { ImportedRefProbe.foreign = it }
    private var label by ref("left")
    private var eventLog by ref("")
    private var modelValue by ref(0.0)
    private var bulkLog by ref("")
    private var accepted by ref(0.0)

    override val body = RenderFun {
        ForeignCounter(
            label = label,
            start = 2.0,
            note = provided(null),
            contentProp = "a prop named content",
            format = { values -> "format:${values.size}:${values[1]}" },
            onChange = { value, source -> eventLog = "$source:${value.toInt()}" },
            onUpdateModelValue = { modelValue = it },
            onBulk = { values -> bulkLog = "${values.size}:${values[1]}:${values[2]}" },
            key = "left",
        ) { div { text("default slot") }.property("data-foreign-slot", "") }
            .ref(child).onChange { value, _ -> ImportedRefProbe.events += "changed:$value" }
            .slot("header") { div { text("named slot") }.property("data-foreign-header", "") }
        ForeignCounter(label = "right", key = "right")
        ForeignFunctional(label = "functional")
        ForeignDefault()
        ForeignCommonJs()
        ForeignOptions(label = "options", onAccepted = { accepted = it })
        button(onClick = { label = "renamed" }) { text("rename") }.property("data-action", "rename")
        div { text(eventLog) }.property("data-foreign-event", "")
        div { text(modelValue.toInt().toString()) }.property("data-foreign-model", "")
        div { text(bulkLog) }.property("data-foreign-bulk", "")
        div { text(accepted.toInt().toString()) }.property("data-foreign-accepted", "")
    }
}

@VueComponent
internal class ElementPlusHost : Component() {
    private val input = ElInputVue.ref().also { ImportedRefProbe.input = it }
    private var text by ref("seed")
    private var clicks by ref(0)
    private var disabled by ref(false)

    override val body = RenderFun {
        ElButton(type = "primary", disabled = disabled) {
            div { text("Element Plus button") }
        }.style { this["marginRight"] = "8px" }.onClick { clicks++ }
        ElInput(vModel = text, placeholder = "Type here").ref(input)
            .onUpdateModelValue { ImportedRefProbe.events += "model:$text" }
        button(onClick = { disabled = true }) { text("disable") }.property("data-action", "disable")
        div { text(text) }.property("data-el-value", "")
        div { text(clicks.toString()) }.property("data-el-count", "")
    }
}

@VueComponent
internal class SourceHost : Component() {
    private val child = SourceCounterVue.ref().also { ImportedRefProbe.source = it }
    private val array = SourceArrayVue.ref().also { ImportedRefProbe.options = it }
    private var label by ref("source")
    private var changed by ref("")
    private var selected by ref("")
    private var model by ref("")
    private var accepted by ref(0.0)

    override val body = RenderFun {
        SourceCounter(
            title = label,
            note = provided(null),
            onChanged = { value, title -> changed = "$title:${value.toInt()}" },
            onUpdateModelValue = { values -> model = "${values.size}:${values[1]}" },
        ).ref(child)
        SourceArray(caption = provided("array caption"), onSelected = { values -> selected = "${values[0]}:${values[1]}" }).ref(array)
        SourceCommonJs(onAccepted = { accepted = it })
        SourceFunctional(label = "JSDoc functional")
        button(onClick = { label = "renamed source" }) { text("rename source") }.property("data-action", "rename-source")
        div { text(changed) }.property("data-source-changed", "")
        div { text(selected) }.property("data-source-selected", "")
        div { text(model) }.property("data-source-model", "")
        div { text(accepted.toInt().toString()) }.property("data-source-accepted", "")
    }
}
