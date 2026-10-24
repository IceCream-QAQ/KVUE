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
import kvue.vdom.RenderFun
import kvue.watch
import kvui.*

@VueComponent
class Playground : Component() {
    private var name by ref("Kotlin")
    private var count by ref(0)
    private var enabled by ref(true)
    private val percentage by computed { count * 10.0 }
    private var countNotice by ref("每次点击都由 Kotlin 回调更新 Vue 响应式状态。")

    init {
        watch(::count) { value, old -> countNotice = "watch 检测到计数变化：$old → $value" }
    }

    override val body = RenderFun {
        main(className = "playground") {
            header(className = "hero") {
                div(className = "eyebrow") { text("KVUE  /  COMPONENT PLAYGROUND") }
                h1 { text("Kotlin 写组件，Vue 来渲染。") }
                p(className = "intro") { text("试试输入、开关和按钮。这里的界面由 KVUI、Element Plus 与 Kotlin class 组件共同组成。") }
                div(className = "badges") {
                    ElTag(type = "success", effect = "plain", round = true) { text("Kotlin + Vue 3") }
                    ElTag(type = "primary", effect = "plain", round = true) { text("Element Plus 2.14.7") }
                }
            }

            div(className = "grid") {
                ElCard(header = "01 / 输入与状态", shadow = "never") {
                    label(className = "field-label") { text("你的名字") }
                    ElInput(
                        vModel = name,
                        placeholder = "输入名字，看看下方的问候",
                        clearable = true,
                        size = "large",
                    )
                    div(className = "switch-row") {
                        span { text("启用计数器") }
                        ElSwitch(
                            bindModelValue = enabled,
                            ariaLabel = "启用计数器",
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
                    div(className = "counter-value") { text(count.toString()) }
                    p(className = "muted") { text(countNotice) }
                    div(className = "button-row") {
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
                    ElProgress(percentage = percentage, strokeWidth = 10.0, color = "#3578e5")
                }
            }

            // 自己声明的 class，也由插件提供同名 RenderGroup 扩展函数。
            GreetingCard(bindName = name, onReset = { count = 0 })
            footer(className = "footer") { text("KVUE · KVUI 原生元素 + Element Plus") }
        }
    }
}
