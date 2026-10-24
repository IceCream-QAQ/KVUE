package demo

import demo.elementplus.ElButton
import demo.elementplus.ElCard
import kvue.Component
import kvue.annotation.Emit
import kvue.annotation.Prop
import kvue.annotation.VueComponent
import kvue.vdom.RenderFun

@VueComponent
class GreetingCard : Component() {
    @Prop
    private val name by property("朋友")

    @Emit
    private val reset: () -> Unit by event()

    override val body = RenderFun {
        ElCard(header = "03 / Kotlin class 组件", shadow = "never") {
            tag("div", "greeting-row") {
                tag("div") {
                    tag("h2") { text("你好，$name。") }
                    tag("p", "muted") { text("这是独立的 GreetingCard，接收 name 属性，并发出 reset 事件。") }
                }
                ElButton(plain = true, onClick = { reset() }) { text("从子组件重置") }
            }
        }
    }
}
