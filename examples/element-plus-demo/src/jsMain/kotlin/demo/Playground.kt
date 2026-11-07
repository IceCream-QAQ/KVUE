package demo

import demo.elementplus.ElAlert
import demo.elementplus.ElButton
import demo.elementplus.ElCard
import demo.elementplus.ElInput
import demo.elementplus.ElInputVue
import demo.elementplus.ElProgress
import demo.elementplus.ElSwitch
import demo.elementplus.ElTag
import demo.elementplus.ElTable
import demo.elementplus.ElTableColumn
import kvue.Component
import kvue.`fun`.jso
import kvue.annotation.VueComponent
import kvue.vdom.RenderFun
import kvue.vdom.Transition
import kvue.vdom.knode.css
import kvue.vdom.knode.style
import kvue.watch
import kvue.show
import kvui.*

@VueComponent
class Playground : Component() {
    private val nameInput = ElInputVue.ref()
    private val greeting = GreetingCardVue.ref()
    private var name by ref("Kotlin")
    private var count by ref(0)
    private var enabled by ref(true)
    private var greetingVisible by ref(true)
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
                ElCard(shadow = "never") {
                    slot("header") { text("01 / 输入与状态") }
                    label(className = "field-label") { text("你的名字") }
                    ElInput(
                        vModel = name,
                        placeholder = "输入名字，看看下方的问候",
                        clearable = true,
                        size = "large",
                    ).ref(nameInput)
                    div(className = "button-row") {
                        ElButton(plain = true) { text("聚焦输入框") }
                            .onClick { nameInput.value?.focus() }
                        ElButton(plain = true) { text("调用子组件 greet") }
                            .onClick { greeting.value?.greet("链式 KVUE") }
                    }
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

                ElCard(shadow = "never") {
                    slots.header { text("02 / 事件与渲染") }
                    slots.footer { text("slots.header / slots.footer 与普通内容共同组成组件插槽。") }
                    div(className = "counter-value") { text(count.toString()) }
                    p(className = "muted") { text(countNotice) }
                    div(className = "button-row") {
                        ElButton(
                            type = "primary",
                            size = "large",
                            disabled = !enabled || count >= 10,
                        ) { text("加一") }
                            .css { +"counter-increment" }
                            .style { this["fontWeight"] = "600" }
                            .onClick { count++ }
                        ElButton(size = "large", disabled = count == 0, onClick = { count = 0 }) {
                            text("重置")
                        }
                    }
                    ElProgress(percentage = percentage, strokeWidth = 10.0, color = "#3578e5")
                }

                ElCard(shadow = "never") {
                    slots.header { text("03 / 作用域插槽") }
                    ElTable(data = arrayOf<Any>(jso("name" to name, "count" to count))) {
                        ElTableColumn(label = "名字") {
                            slots.default {
                                val currentName = this.row.name as String
                                span { text(currentName) }
                            }
                        }
                        ElTableColumn(label = "计数") {
                            slots.default {
                                val value = (row.count as Number).toInt()
                                val position = index.toInt()
                                span { text("第 $position 行：$value") }
                            }
                        }
                    }
                }

                ElCard(shadow = "never") {
                    slots.header { text("04 / 显示与过渡") }
                    p(className = "muted") { text("切换下方问候卡片的显示状态。隐藏后仍可调用 greet，卡片会保留当前内容。") }
                    ElButton(plain = true, onClick = { greetingVisible = !greetingVisible }) {
                        text(if (greetingVisible) "隐藏问候卡片" else "显示问候卡片")
                    }
                }
            }

            // 自己声明的 class，也由插件提供同名 RenderGroup 扩展函数。
            Transition(name = "greeting", persisted = true) {
                GreetingCard(bindName = name).show(greetingVisible).ref(greeting)
                    .onReset { count = 0 }
            }
            footer(className = "footer") { text("KVUE · KVUI 原生元素 + Element Plus") }
        }
    }
}
