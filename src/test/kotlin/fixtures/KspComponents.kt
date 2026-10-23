package fixtures

import kvue.Component
import kvue.annotation.Emit
import kvue.annotation.Prop
import kvue.annotation.VueComponent
import kvue.provided
import kvue.vdom.RenderFun
import test.button
import test.tdiv

internal typealias ChangeHandler = (Int, String) -> Unit
internal typealias NullableText = String?

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
        button("$id:$count:$step") {
            count += step
            changed(count, id)
            modelChanged(count)
        }.property("data-counter", id)
        button("reset") {
            count = initial
            reset()
        }.property("data-reset", id)
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
                tdiv(slotText).property("data-slot", "left")
            }.slot("header") {
                tdiv("header").property("data-header", "left")
            }
        }
        Counter(id = "right", initial = 10, key = "right")
        button("step") { step = 5 }.property("data-action", "step")
        button("handler") { replaceHandler = true }.property("data-action", "handler")
        button("toggle") { showLeft = !showLeft }.property("data-action", "toggle")
        button("slot") { slotText = "second" }.property("data-action", "slot")
        tdiv(eventLog).property("data-event", "")
        tdiv(modelValue.toString()).property("data-model", "")
        tdiv(resets.toString()).property("data-resets", "")
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
        tdiv("$text|$required").property("data-nullable", id)
    }
}

@VueComponent
internal class EmptyComponent : Component() {
    override val body = RenderFun { tdiv("empty").property("data-empty", "") }
}

internal abstract class GenericBase<T> : Component() {
    @Prop(required = true)
    protected val value by property<T>()
}

@VueComponent
internal class InheritedComponent : GenericBase<String>() {
    override val body = RenderFun { tdiv(value).property("data-inherited", "") }
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
            tdiv("lazy").property("data-lazy", "")
        }
        button("show") { visible = true }.property("data-action", "show")
        button("unrelated:$unrelated") { unrelated++ }.property("data-action", "unrelated")
    }
}

@VueComponent
internal class FailingComponent : Component() {
    init {
        error("Expected constructor failure")
    }

    override val body = RenderFun { }
}
