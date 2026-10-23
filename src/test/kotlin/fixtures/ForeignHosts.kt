package fixtures

import fixtures.foreign.ForeignCommonJs
import fixtures.foreign.ForeignCounter
import fixtures.foreign.ForeignDefault
import fixtures.foreign.ForeignFunctional
import fixtures.foreign.ForeignOptions
import fixtures.source.SourceArray
import fixtures.source.SourceCommonJs
import fixtures.source.SourceCounter
import fixtures.source.SourceFunctional
import kvue.Component
import kvue.annotation.VueComponent
import kvue.js.elementplus.ElButton
import kvue.js.elementplus.ElInput
import kvue.provided
import kvue.vdom.RenderFun
import test.button
import test.tdiv

@VueComponent
internal class ForeignHost : Component() {
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
        ) { tdiv("default slot").property("data-foreign-slot", "") }
            .slot("header") { tdiv("named slot").property("data-foreign-header", "") }
        ForeignCounter(label = "right", key = "right")
        ForeignFunctional(label = "functional")
        ForeignDefault()
        ForeignCommonJs()
        ForeignOptions(label = "options", onAccepted = { accepted = it })
        button("rename") { label = "renamed" }.property("data-action", "rename")
        tdiv(eventLog).property("data-foreign-event", "")
        tdiv(modelValue.toInt().toString()).property("data-foreign-model", "")
        tdiv(bulkLog).property("data-foreign-bulk", "")
        tdiv(accepted.toInt().toString()).property("data-foreign-accepted", "")
    }
}

@VueComponent
internal class ElementPlusHost : Component() {
    private var text by ref("seed")
    private var clicks by ref(0)
    private var disabled by ref(false)

    override val body = RenderFun {
        ElButton(type = "primary", disabled = disabled, onClick = { clicks++ }) {
            tdiv("Element Plus button")
        }
        ElInput(modelValue = provided(text), placeholder = "Type here", onUpdateModelValue = { text = it })
        button("disable") { disabled = true }.property("data-action", "disable")
        tdiv(text).property("data-el-value", "")
        tdiv(clicks.toString()).property("data-el-count", "")
    }
}

@VueComponent
internal class SourceHost : Component() {
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
        )
        SourceArray(caption = provided("array caption"), onSelected = { values -> selected = "${values[0]}:${values[1]}" })
        SourceCommonJs(onAccepted = { accepted = it })
        SourceFunctional(label = "JSDoc functional")
        button("rename source") { label = "renamed source" }.property("data-action", "rename-source")
        tdiv(changed).property("data-source-changed", "")
        tdiv(selected).property("data-source-selected", "")
        tdiv(model).property("data-source-model", "")
        tdiv(accepted.toInt().toString()).property("data-source-accepted", "")
    }
}
