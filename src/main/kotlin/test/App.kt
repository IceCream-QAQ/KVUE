package test

import kvue.Component
import kvue.annotation.Prop
import kvue.annotation.VueComponent
import kvue.vdom.RenderContext
import kvue.vdom.RenderFun
import kvue.vdom.knode.ElementNode
import kvue.vdom.knode.StringKNode
import kotlin.reflect.KMutableProperty0

@VueComponent
class App : Component() {

    private var num by ref(0)

    override val body = RenderFun {
        tdiv("Hello, world! $num")
        button("点我") {
            num++
        }
        hr()
        SubComponent(num = if (num > 5) num else null)
    }

}

@VueComponent
class SubComponent : Component() {

    @Prop
    private val num by property(0)

    private var text by ref("123456")

    override val body = RenderFun {

        input(::text)

        for (i in 1..num)
            tdiv("SubComponent: $i, $text")
    }

    private fun <T> RenderContext.input(vModel: KMutableProperty0<T>) = ElementNode("input").apply {
        addElement(this)
        property("value", vModel.get())
        property("onInput", { event: dynamic -> vModel.set(event.target.value) })
    }
}

fun RenderContext.tdiv(text: String) = ElementNode("div").apply {
    addElement(this)
    slot("default") { addElement(StringKNode(text)) }
}

fun RenderContext.button(text: String, onClick: (() -> Unit)? = null) = ElementNode("button").apply {
    addElement(this)
    onClick?.let { onClick(it) }
    slot("default") { addElement(StringKNode(text)) }
}

fun RenderContext.hr() = ElementNode("hr").apply { addElement(this) }
