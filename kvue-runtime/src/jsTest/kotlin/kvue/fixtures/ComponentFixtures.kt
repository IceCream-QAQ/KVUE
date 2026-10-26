package kvue.fixtures

import kvue.Component
import kvue.annotation.Emit
import kvue.annotation.Prop
import kvue.annotation.VueComponent
import kvue.provided
import kvue.vdom.RenderFun
import kvue.vdom.RenderGroup
import kvue.vdom.knode.ElementNode
import kvue.vdom.knode.StringKNode

// Raw nodes keep runtime fixtures independent of the higher-level KVUI module.
private fun RenderGroup.text(value: String) = addElement(StringKNode(value))
private fun RenderGroup.div(content: RenderFun): ElementNode = ElementNode("div").apply {
    slot("default", content)
    addElement(this)
}
private fun RenderGroup.header(content: RenderFun): ElementNode = ElementNode("header").apply {
    slot("default", content)
    addElement(this)
}
private fun RenderGroup.button(onClick: () -> Unit, content: RenderFun): ElementNode = ElementNode("button").apply {
    property("onClick", onClick)
    slot("default", content)
    addElement(this)
}

internal typealias ChangeHandler = (Int, String) -> Unit
internal typealias NullableText = String?

@VueComponent
internal class BoundEditor : Component() {
    @Prop(required = true) private val modelValue by property<String?>()
    @Prop private val visible by property(false)
    @Emit(name = "update:modelValue") private val update: (String?) -> Unit by event()
    @Emit(name = "update:visible") private val updateVisible: (Boolean) -> Unit by event()
    override val body = RenderFun {
        button(onClick = {
            update(if (modelValue == null) "updated" else null)
            updateVisible(!visible)
        }) { text("$modelValue|$visible") }.property("data-bound-child", "")
    }
}

@VueComponent
internal class BindingHost : Component() {
    private var text by ref<String?>(null)
    private var shown by ref(false)
    override val body = RenderFun {
        BoundEditor(vModel = text, bindVisible = shown)
        div { text("$text|$shown") }.property("data-bound-parent", "")
        button(onClick = { text = "parent"; shown = true }) { text("parent") }.property("data-bound-change-parent", "")
    }
}

@VueComponent
internal class Counter : Component() {
    @Prop(required = true)
    private val id by property<String>()

    @Prop
    private val initial by property(7)

    @Prop(name = "step-size")
    private val step by property(1)

    @Emit
    private val changed: ChangeHandler by event()

    @Emit(name = "update:modelValue")
    private val modelChanged: (Int) -> Unit by event()

    @Emit
    private val reset: () -> Unit by event()

    private var count by ref(initial)

    override val body = RenderFun {
        button(onClick = {
            count += step
            changed(count, id)
            modelChanged(count)
        }) { text("$id:$count:$step") }.property("data-counter", id)
        button(onClick = {
            count = initial
            reset()
        }) { text("reset") }.property("data-reset", id)
        slot()
        slot("header")
    }
}

@VueComponent
internal class CounterHost : Component() {
    private var step by ref(1)
    private var showLeft by ref(true)
    private var replaceHandler by ref(false)
    private var slotText by ref("first")
    private var eventLog by ref("")
    private var modelValue by ref(0)
    private var resets by ref(0)

    override val body = RenderFun {
        if (showLeft) {
            Counter(
                id = "left",
                initial = 2,
                step = step,
                onChanged = if (replaceHandler) {
                    { value, id -> eventLog = "new:$id:$value" }
                } else {
                    { value, id -> eventLog = "old:$id:$value" }
                },
                onUpdateModelValue = { modelValue = it },
                onReset = { resets++ },
                key = "left",
            ) {
                div { text(slotText) }.property("data-slot", "left")
            }.slot("header") {
                div { text("header") }.property("data-header", "left")
            }
        }
        Counter(id = "right", initial = 10, key = "right")
        button(onClick = { step = 5 }) { text("step") }.property("data-action", "step")
        button(onClick = { replaceHandler = true }) { text("handler") }.property("data-action", "handler")
        button(onClick = { showLeft = !showLeft }) { text("toggle") }.property("data-action", "toggle")
        button(onClick = { slotText = "second" }) { text("slot") }.property("data-action", "slot")
        div { text(eventLog) }.property("data-event", "")
        div { text(modelValue.toString()) }.property("data-model", "")
        div { text(resets.toString()) }.property("data-resets", "")
    }
}

@VueComponent
internal class NullableCard : Component() {
    @Prop(required = true)
    private val id by property<String>()

    @Prop(required = true)
    private val required by property<String?>()

    @Prop
    private val text: NullableText by property<NullableText>("fallback")

    override val body = RenderFun {
        div { text("$text|$required") }.property("data-nullable", id)
    }
}

@VueComponent
internal class EmptyComponent : Component() {
    override val body = RenderFun { div { text("empty") }.property("data-empty", "") }
}

internal abstract class GenericBase<T> : Component() {
    @Prop(required = true)
    protected val value by property<T>()
}

@VueComponent
internal class InheritedComponent : GenericBase<String>() {
    override val body = RenderFun { div { text(value) }.property("data-inherited", "") }
}

@VueComponent
internal class DefaultsHost : Component() {
    override val body = RenderFun {
        NullableCard(id = "omitted", required = null)
        NullableCard(id = "null", required = null, text = provided(null))
        NullableCard(id = "value", required = "present", text = provided("custom"))
        Counter(id = "default")
        Counter(id = "zero", initial = 0, step = 0)
        EmptyComponent()
        InheritedComponent(value = "inherited")
    }
}

internal object SlotProbe {
    var calls = 0
}

internal object InlineSlotProbe {
    var defaults = 0
}

@VueComponent
@kvue.annotation.SlotNames("header", "footer")
internal class InlinePanel : Component() {
    @Prop private val showDefault by property(false)
    @Emit private val ping: () -> Unit by event()
    override val body = RenderFun {
        div {
            slot("header")
            if (showDefault) slot()
            slot("footer")
            slot("extra")
            button(onClick = { ping() }) { text("ping") }.property("data-inline-ping", "")
        }
    }
}

@VueComponent
internal class InlineSlotHost : Component() {
    private var label by ref("first")
    private var shown by ref(false)
    private var pings by ref(0)
    override val body = RenderFun {
        InlinePanel(showDefault = shown) {
            slot("header") {
                div { text("head:$label") }.property("data-inline-header", "")
            }
            InlineSlotProbe.defaults++
            header { text("HTML header") }.property("data-inline-html", "")
            div { text("body:$label") }.property("data-inline-default", "")
            slots.footer {
                div { text("foot:$label") }.property("data-inline-footer", "")
            }
        }.slot("extra") {
            div { text("extra") }.property("data-inline-extra", "")
        }.onPing { pings++ }
        button(onClick = { label = "second" }) { text("rename") }.property("data-inline-rename", "")
        button(onClick = { shown = true }) { text("show") }.property("data-inline-show", "")
        div { text(pings.toString()) }.property("data-inline-pings", "")
    }
}

@VueComponent
internal class SlotGate : Component() {
    @Prop
    private val visible by property(false)

    override val body = RenderFun {
        if (visible) slot()
    }
}

@VueComponent
internal class SlotHost : Component() {
    private var visible by ref(false)
    private var unrelated by ref(0)

    override val body = RenderFun {
        SlotGate(visible = visible) {
            SlotProbe.calls++
            div { text("lazy") }.property("data-lazy", "")
        }
        button(onClick = { visible = true }) { text("show") }.property("data-action", "show")
        button(onClick = { unrelated++ }) { text("unrelated:$unrelated") }.property("data-action", "unrelated")
    }
}

@VueComponent
internal class FailingComponent : Component() {
    init {
        error("Expected constructor failure")
    }

    override val body = RenderFun { }
}
