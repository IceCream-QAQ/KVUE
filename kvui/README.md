# KVUI

KVUE 的标准 HTML 元素封装。独立 Kotlin/JS 模块，Maven 坐标为 `org.kvue:kvui:0.1.0-SNAPSHOT`，Kotlin 包名为 `kvui`。
当前开发版本在本地发布，尚未上传公共 Maven 仓库。

## 接入

```kotlin
kotlin {
    sourceSets {
        jsMain.dependencies {
            implementation("org.kvue:kvui:0.1.0-SNAPSHOT")
        }
    }
}
```

仓库内可使用 `implementation(project(":kvui"))`。KVUI 传递依赖 `kvue-runtime` 和 Kotlin DOM API。
标签扩展函数是包内已编译的普通 Kotlin API，IDE 可以直接解析、补全和导航，无需先生成 KSP 声明。
`vModel` / `bindModelValue` 需要应用已有的 `org.kvue` Gradle 插件，使用与组件绑定相同的编译期展开和 IDEA 检查。

## 写法

```kotlin
import kvue.Component
import kvue.annotation.VueComponent
import kvue.vdom.RenderFun
import kvui.*

@VueComponent
class Editor : Component() {
    private var name by ref("")
    private var enabled by ref(true)

    override val body = RenderFun {
        div(className = "editor", attributes = mapOf("data-testid" to "editor")) {
            label(htmlFor = "name") { text("名字") }
            input(id = "name", vModel = name, placeholder = "请输入")
            label {
                input(type = "checkbox", bindModelValue = enabled)
                text("允许保存")
            }
            button(type = "button", disabled = !enabled, onClick = { name = "KVUI" }) {
                text("保存：$name")
            }
        }
    }
}
```

目前包含 111 个 HTML 标签函数：布局和文本、列表、表格、表单、图片、音视频、文档元数据等。
例如 `div`、`span`、`p`、`h1`–`h6`、`main`、`section`、`ul`、`li`、`table`、`tr`、`td`、`input`、`button`、`textarea`、`select`、`option`、`img`。
与 Kotlin 关键字同名的标签写作 `` `object` `` 和 `` `var` ``。完整声明见 [Html.kt](src/main/kotlin/kvui/Html.kt)。

这些函数直接创建字符串类型的 Vue VNode，不额外创建组件实例。尾随 lambda 构建子元素，返回值为 `ElementNode`，可以继续使用 `.property(...)`。
`text(...)` 创建文本节点，字符串中的 HTML 标记按文本显示。`input`、`img`、`br` 等空元素没有 `content` 参数；`textarea` 通过 `value` 或绑定设置内容。

## 属性和事件

- 通用参数包括 `id`、`className`、`style`、`title`、`hidden`、`role`、`tabIndex`、`key` 和常用事件；`style` 接受 CSS 字符串。
- 标签还提供对应属性，例如 `a(href = ...)`、`img(src = ..., alt = ...)`、`input(disabled = ..., maxLength = ...)`、`label(htmlFor = ...)`。
- `onClick` 接收 `MouseEvent`；键盘事件接收 `KeyboardEvent`；焦点事件接收 `FocusEvent`；`onInput`、`onChange`、`onSubmit` 等接收 DOM `Event`。可直接调用 `preventDefault()` / `stopPropagation()`。
- 可选属性的 `null` 表示省略；`false`、`0` 和空字符串会正常传入。`attributes: Map<String, Any?>` 接收未列出的原生属性、`data-*`、`aria-*` 或事件。非 null 的显式参数优先于同名 `attributes` 条目；`attributes` 中的 null 保留给 Vue 处理。
- `key` 交给 Vue 管理节点身份。按钮类型、表单提交等保留浏览器默认行为，需要普通按钮时显式写 `type = "button"`。

自定义元素或尚未提供的标签可以使用：

```kotlin
element("my-widget", attributes = mapOf("caption" to "Hello")) {
    text("Content")
}
```

## 原生表单双向绑定

`input`、`textarea`、`select` 均提供 `vModel` 和 `bindModelValue`，两者是同一个绑定的别名，不能同时使用。
也可以手动传入 `modelValue = provided(value)` 和 `onUpdateModelValue = { ... }`；`provided(null)` 表示明确的空值。
只有 update 回调而没有 modelValue 会报告参数错误。省略绑定时，它们就是普通 DOM 元素，可以自行组合 `value`、`checked`、`onInput` 和 `onChange`。

底层通过 Vue 的 `withDirectives` 应用 `vModelDynamic`，沿用原生表单语义：

| 元素 | 常用状态类型 | 行为 |
| --- | --- | --- |
| 文本 `input`、`textarea` | `String` / `String?` | input 更新，组合输入结束后再更新绑定值，null 显示为空字符串 |
| 单个 checkbox | `Boolean` | checked / change |
| 多个 checkbox | `Array<String>` 等 JS 数组 | 根据各输入框的 value 添加、移除选项 |
| radio | 与 value 对应的类型 | 选中后写入该选项的 value |
| 单选 select | 与 option value 对应的类型 | change 更新选中值 |
| 多选 select | `Array<String>` 等 JS 数组 | 写入选中值数组；使用 Kotlin `Array`，而不是 `List` / Kotlin `Set` |

原生属性 `value` 在 radio / checkbox 中表示选项值，与 `modelValue` 有不同用途。绑定后的表单状态以 modelValue 为准。
`type = "file"` 不支持 model 绑定，应通过 `onChange` 读取文件。

```kotlin
input(vModel = name, modelModifiers = ModelModifiers(lazy = true, trim = true))
select(vModel = selected, multiple = true) {
    option(value = "kotlin") { text("Kotlin") }
    option(value = "vue") { text("Vue") }
}
```

`ModelModifiers` 支持 `lazy`、`trim`、`number`，按 Vue 对各元素的规则生效。`type = "number"` 自带数字转换；清空输入或无法转换时仍可能返回字符串，应选择能容纳这些值的状态类型，例如 `Any?`。
由于原生 model 会随 input 类型、选项值和 multiple 改变，绑定参数类型是 `Any?`，回写时由编译器转换为目标变量类型。编译器和 IDE 能检查可写性、setter 可见性和参数冲突，但不能静态保证每个原生表单的返回值类型。

Vue 的行为依据：[表单输入绑定](https://vuejs.org/guide/essentials/forms.html)、[withDirectives](https://vuejs.org/api/render-function.html#withdirectives)。

## 维护

`kvui/tools/generate.cjs` 维护标签、属性与事件清单，生成并提交 `Html.kt`。修改后在仓库根目录运行：

```powershell
node kvui/tools/generate.cjs
```

`:kvui:checkKvuiElements` 检查生成结果是否过期，`:kvui:check` 及根 `check` 会执行它。生成器仅用于本仓库维护，消费方构建不执行此脚本。
浏览器测试位于本模块的 `src/jsTest/kotlin/kvui/DomTest.kt`，通过 `:kvui:jsBrowserTest` 单独运行，覆盖原生属性/事件、文本转义、key、组合输入、单双向更新、表单类型和修饰符。
