package test

import kvue.Component
import kvue.annotation.Prop
import kvue.annotation.VueComponent
import kvue.vdom.RenderContext
import kvue.vdom.RenderFun
import kvue.vdom.knode.ElementNode
import kvui.div
import kvui.hr
import kvui.input
import kvui.text
import kvui.button as domButton

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

        input(vModel = text)

        for (i in 1..num)
            tdiv("SubComponent: $i, $text")
    }

}

// Compatibility helpers used by the older component fixtures.
fun RenderContext.tdiv(value: String): ElementNode = div { text(value) }

fun RenderContext.button(value: String, onClick: (() -> Unit)? = null): ElementNode =
    domButton(onClick = onClick?.let { handler -> { _ -> handler() } }) { text(value) }
