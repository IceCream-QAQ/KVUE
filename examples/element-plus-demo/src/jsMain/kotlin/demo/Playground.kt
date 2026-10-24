package demo

import demo.elementplus.ElAlert
import demo.elementplus.ElButton
import demo.elementplus.ElCard
import demo.elementplus.ElInput
import demo.elementplus.ElProgress
import demo.elementplus.ElSwitch
import demo.elementplus.ElTag
import kvue.Component
import kvue.annotation.VueComponent
import kvue.provided
import kvue.vdom.RenderFun

@VueComponent
class Playground : Component() {
    private var name by ref("Kotlin")
    private var count by ref(0)
    private var enabled by ref(true)

    override val body = RenderFun {
        tag("main", "playground") {
            tag("header", "hero") {
                tag("div", "eyebrow") { text("KVUE  /  COMPONENT PLAYGROUND") }
                tag("h1") { text("Kotlin 写组件，Vue 来渲染。") }
                tag("p", "intro") { text("试试输入、开关和按钮。这里的界面由 Element Plus 与 Kotlin class 组件共同组成。") }
                tag("div", "badges") {
                    ElTag(type = "success", effect = "plain", round = true) { text("Kotlin + Vue 3") }
                    ElTag(type = "primary", effect = "plain", round = true) { text("Element Plus 2.14.7") }
                }
            }

            tag("div", "grid") {
                ElCard(header = "01 / 输入与状态", shadow = "never") {
                    tag("label", "field-label") { text("你的名字") }
                    ElInput(
                        modelValue = provided(name),
                        placeholder = "输入名字，看看下方的问候",
                        clearable = true,
                        size = "large",
                        onUpdateModelValue = { name = it },
                    )
                    tag("div", "switch-row") {
                        tag("span") { text("启用计数器") }
                        ElSwitch(
                            modelValue = enabled,
                            ariaLabel = "启用计数器",
                            onUpdateModelValue = { enabled = it as Boolean },
                        )
                    }
                    ElAlert(
                        title = if (enabled) "交互已启用" else "计数器已暂停",
                        type = if (enabled) "success" else "info",
                        showIcon = true,
                        closable = false,
                    )
                }

                ElCard(header = "02 / 事件与渲染", shadow = "never") {
                    tag("div", "counter-value") { text(count.toString()) }
                    tag("p", "muted") { text("每次点击都由 Kotlin 回调更新 Vue 响应式状态。") }
                    tag("div", "button-row") {
                        ElButton(
                            type = "primary",
                            size = "large",
                            disabled = !enabled || count >= 10,
                            onClick = { count++ },
                        ) { text("加一") }
                        ElButton(size = "large", disabled = count == 0, onClick = { count = 0 }) {
                            text("重置")
                        }
                    }
                    ElProgress(percentage = count * 10.0, strokeWidth = 10.0, color = "#3578e5")
                }
            }

            // 自己声明的 class，也由插件提供同名 RenderGroup 扩展函数。
            GreetingCard(name = name.ifBlank { "朋友" }, onReset = { count = 0 })
            tag("footer", "footer") { text("KVUE · 一个独立的 Kotlin/JS + Element Plus 工程") }
        }
    }
}
