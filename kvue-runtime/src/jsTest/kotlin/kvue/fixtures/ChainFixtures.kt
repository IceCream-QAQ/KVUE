package kvue.fixtures

import kvue.*
import kvue.annotation.*
import kvue.vdom.RenderFun
import kvue.vdom.knode.*

internal object ChainProbe {
    val log = mutableListOf<String>()
    var visible = ref(true)
    var model = ref(0)
    var revision = ref(0)
    lateinit var child: ComponentRef<ChainChildInstance>
    lateinit var sibling: ComponentRef<ChainChildInstance>
}

@VueComponent
internal class ChainChild : Component() {
    @Prop private val modelValue by property(0)
    @Emit(name = "update:modelValue") private val update: (Int) -> Unit by event()
    @Emit private val changed: (Int) -> Unit by event()
    @Expose var count by ref(0)
    @Expose(name = "reset-value") fun reset(value: Int): Int {
        count = value
        return count
    }
    private fun hidden() = 42

    override val body = RenderFun {
        addElement(ElementNode("button").apply {
            property("data-chain", "")
            property("class", "base")
            property("onClick", {
                count++
                update(modelValue + 1)
                changed(count)
            })
            slot("default") { addElement(StringKNode(count.toString())) }
        })
    }
}

@VueComponent
internal class ChainHost : Component() {
    private val child = ChainChildVue.ref().also { ChainProbe.child = it }
    private val sibling = ChainChildVue.ref().also { ChainProbe.sibling = it }
    private var model by ChainProbe.model
    private val visible by ChainProbe.visible
    private val revision by ChainProbe.revision
    override val body = RenderFun {
        if (visible) {
            ChainChild(vModel = model, key = "first", onChanged = { ChainProbe.log += "argument:$it:$revision" })
                .onChanged { ChainProbe.log += "chain1:$it" }
                .css { +"extra"; add("conditional", true) }
                .style { this["color"] = "red" }
                .onChanged { ChainProbe.log += "chain2:$it" }
                .onUpdateModelValue { ChainProbe.log += "model:$model" }
                .ref(child)
        }
        ChainChild(key = "second").ref(sibling)
    }
}
