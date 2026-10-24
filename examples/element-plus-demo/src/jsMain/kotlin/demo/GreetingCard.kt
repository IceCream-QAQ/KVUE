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

    @Emit(name = "update:name")
    private val updateName: (String) -> Unit by event()

    override val body = RenderFun {
        ElCard(header = "03 / Kotlin class 组件", shadow = "never") {
            tag("div", "greeting-row") {
                tag("div") {
                    tag("h2") { text("你好，${name.ifBlank { "朋友" }}。") }
                    tag("p", "muted") { text("name 通过 bindName 双向同步，reset 事件单独处理。") }
                }
                tag("div", "button-row") {
                    ElButton(plain = true, onClick = { updateName("KVUE") }) { text("名字设为 KVUE") }
                    ElButton(plain = true, onClick = { reset() }) { text("从子组件重置") }
                }
            }
        }
    }
}
