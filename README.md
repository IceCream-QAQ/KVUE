# KVUE

用 Kotlin 编写 Vue 组件，保留 Vue 的响应式更新、组件生命周期和第三方生态。

KVUE 以 Kotlin 类声明组件，通过 KSP 生成可复用的 Vue 组件定义和类型安全的渲染函数。你可以使用 Kotlin 委托维护状态，以嵌套函数描述界面，并在同一个渲染作用域中组合原生 HTML 元素、Kotlin 组件和第三方 Vue 组件。

当前开发版本为 `0.1.0-SNAPSHOT`。下面的体验流程使用工作区内的本地依赖仓库。

## 主要能力

- **声明组件**：使用 `@VueComponent`、`@Prop`、`@Emit` 和 `@Expose` 描述组件、输入属性、事件和公开接口。
- **响应式状态**：使用 `by ref`、`by computed`、`watch`、生命周期回调及依赖注入。
- **双向绑定**：通过 `vModel` 或 `bindXxx` 直接绑定可写变量，由编译器检查并展开读写操作。
- **链式配置**：组件专属节点提供带类型的事件方法，并可与 `.css {}`、`.style {}` 混合使用。
- **访问实例**：通过 `XxxVue.ref()` 获取组件公开接口，调用 Kotlin 或第三方组件导出的方法。
- **标准元素**：独立的 KVUI 模块提供 `div`、`input`、`button` 等标准 HTML 元素封装。
- **第三方组件导入**：优先读取 TypeScript 声明，也支持静态分析 JavaScript 与 JSDoc，批量生成 Kotlin 接口。
- **编辑器支持**：可选的 IDEA 插件在首次构建前提供组件解析、补全、错误提示和来源跳转。

## 快速体验

### 环境要求

| 环境 | 用途 |
| --- | --- |
| JDK 21 | 主工程、运行库和演示应用的构建 |
| Chrome | 浏览器测试；必要时通过 `CHROME_BIN` 指定路径 |
| IntelliJ IDEA 2026.2.3／262 | 当前 IDEA 插件的适配版本 |
| JDK 25 | IDEA 插件开发；测试同时需要主工程的 JDK 21 工具链 |
| 可通过 `node` 命令运行的 Node.js | IDEA 中的第三方组件静态分析 |

仓库已提供 Gradle 包装脚本，普通构建所需的 Node.js 和 npm 依赖由 Gradle 管理。当前工程使用 Kotlin 2.4.21、KSP 2.3.12、Gradle 9.7.1 和 Vue 3.5.44。

以下命令以 PowerShell 为例。其他终端请使用对应的 `./gradlew` 脚本，并调整多行命令的续行方式。

### 获取工程并验证

将 `JAVA_HOME` 指向 JDK 21，然后执行：

```powershell
git clone https://github.com/IceCream-QAQ/KVUE.git
Set-Location KVUE
./gradlew.bat check
```

根目录的 `check` 汇总运行库、原生元素、组件导入器、共享生成器和绑定编译器的检查。

### 运行交互示例

在仓库根目录将开发依赖发布到工作区内的 `.gradle/kvue-demo-repository`。发布时显式开启生成源：

```powershell
./gradlew.bat --init-script tools/publish-demo.gradle '-Pkvue.exposeGeneratedSources=true' `
    :kvue-codegen-core:publishAllPublicationsToKvueDemoRepository `
    :kvue-ksp:publishAllPublicationsToKvueDemoRepository `
    :kvue-runtime:publishAllPublicationsToKvueDemoRepository `
    :kvui:publishAllPublicationsToKvueDemoRepository `
    :kvue-compiler-plugin:publishAllPublicationsToKvueDemoRepository `
    :kvue-gradle-plugin:publishAllPublicationsToKvueDemoRepository

Set-Location examples/element-plus-demo
./gradlew.bat jsBrowserDevelopmentRun --continuous
```

在浏览器中打开 [本地演示页面](http://127.0.0.1:5173)。页面可以体验输入绑定、计数器、开关、链式事件、输入框聚焦，以及调用 Kotlin 子组件公开方法。

在演示目录执行 `./gradlew.bat jsBrowserDistribution` 可生成生产构建，产物位于 `build/dist/js/productionExecutable`。完整说明见 [Element Plus 演示工程](examples/element-plus-demo/README.md)。

## 声明和使用组件

接入 `org.kvue` Gradle 插件并添加 KVUI 依赖后，可以编写下面的组件。依赖仓库、插件和源码目录配置见 [构建接入说明](docs/ide-and-build.md#gradle-插件做了什么)。

```kotlin
package demo

import kvue.Component
import kvue.annotation.Emit
import kvue.annotation.Expose
import kvue.annotation.Prop
import kvue.annotation.VueComponent
import kvue.createApp
import kvue.vdom.RenderFun
import kvue.vdom.knode.css
import kvue.vdom.knode.style
import kvui.button
import kvui.div
import kvui.p
import kvui.text

@VueComponent
class Counter : Component() {
    @Prop
    private val initial by property(0)

    @Emit
    private val changed: (Int) -> Unit by event()

    private var count by ref(initial)

    @Expose
    fun reset() {
        count = initial
        changed(count)
    }

    override val body = RenderFun {
        div {
            button(onClick = {
                count++
                changed(count)
            }) {
                text("当前计数：$count")
            }
            slot()
        }
    }
}

@VueComponent
class App : Component() {
    private val counter = CounterVue.ref()
    private var latest by ref(0)

    override val body = RenderFun {
        div {
            Counter(initial = 2) {
                text("这是默认插槽")
            }
                .css { +"counter" }
                .style { this["marginTop"] = "8px" }
                .onChanged { latest = it }
                .ref(counter)

            button(onClick = { counter.value?.reset() }) {
                text("重置计数")
            }
            p { text("最近一次事件值：$latest") }
        }
    }
}

fun main() {
    createApp(AppVue.definition).mount("#app")
}
```

页面需要提供 `<div id="app"></div>` 作为挂载点。

以 `Counter` 为例，生成后的主要接口为：

| 接口 | 作用 |
| --- | --- |
| `CounterVue.definition` | 可复用的 Vue 组件定义 |
| `RenderGroup.Counter(...)` | 接收属性、事件、节点标识和插槽的渲染入口 |
| `CounterNode` | 保留组件身份，提供 `onChanged` 等专属事件方法 |
| `CounterVue.ref()` | 创建 `ComponentRef<CounterInstance>` 实例引用 |
| `CounterInstance` | 提供 `reset()` 等显式导出的公开接口 |

组件定义由多个节点共享，每个挂载的组件拥有独立的 Kotlin 实例和状态。父组件重新渲染时，Vue 根据节点类型与 `key` 管理实例复用。

在 `RenderGroup` 作用域中，`Counter(...)` 会解析为生成的渲染函数。不要在该作用域之外手工构造组件实例；实例由 Vue 的组件初始化过程创建。

## 输入属性与事件

输入属性使用 `@Prop` 和 `property(...)` 委托声明，默认值在每个组件实例中计算：

| 属性声明 | 调用规则 |
| --- | --- |
| 必填属性 `@Prop(required = true)` | 必须提供；可配对的双向绑定参数也可以满足该要求 |
| 可选的非空属性 | 省略参数或传 `null` 时使用组件默认值 |
| 可选的可空属性 | 省略时使用默认值，使用 `provided(null)` 显式传入空值 |

事件使用 `@Emit` 和 `event()` 委托声明，支持无参数和多参数回调。事件名 `saved`、`changed` 和 `update:modelValue` 分别生成 `onSaved`、`onChanged` 和 `onUpdateModelValue` 参数及链式方法。

参数监听器在前，链式监听器按调用顺序追加。同名事件不会互相覆盖，也不会覆盖双向绑定的回写逻辑。重新渲染会更新监听配置，不会跨渲染累积订阅。

## 双向绑定与响应式监听

属性与 `update:属性名` 事件配对时，生成 `bindXxx` 参数；`modelValue` 额外提供 `vModel` 别名：

```kotlin
// 组件成员
private var name by ref("")
private var visible by ref(false)

override val body = RenderFun {
    ElInput(vModel = name)
    ElDialog(bindModelValue = visible)
}
```

直接传入可写变量或属性即可。绑定编译器将其展开为属性读取与事件回调中的赋值，并检查只读值、不可访问的设置器、普通表达式和冲突参数。原生表单与自定义组件使用相同的绑定入口。

监听委托属性时，传入属性引用或读取函数：

```kotlin
// 在组件初始化阶段调用，需要导入 kvue.watch。
watch(::count) { value, old ->
    println("计数变化：$old → $value")
}

watch({ count * 2 }) { value, _ ->
    println("两倍计数：$value")
}
```

也可以直接监听持有的响应式引用。不要使用 `watch(count)` 监听已经取出的普通值。
更多响应式、生命周期、依赖注入和作用域接口见 [运行时与 Vue 接口](kvue-runtime/README.md)。

## 组件实例与公开方法

通过 `XxxVue.ref()` 创建引用，在渲染时用 `.ref(...)` 绑定。引用应在组件初始化阶段创建并跨渲染保留。

- 挂载前和卸载后，`value` 为 `null`；异步组件需要等待实际挂载。
- 普通更新保留公开实例包装对象的身份，重新挂载会创建新的包装对象。
- Kotlin 组件只导出带 `@Expose` 的成员，支持可访问的函数、只读属性和可写属性。
- 第三方组件根据公开实例声明或可静态分析的源码生成接口，例如 `ElInputInstance.focus()`。
- 可选的 JavaScript 方法参数使用 `OptionalProp`，通过省略参数或 `provided(...)` 区分未提供与显式值。
- 函数式 JavaScript 组件不生成组件实例引用。未建模的公开接口可以通过 `asDynamic()` 显式访问。

可使用 `by` 读取组件引用，也可通过 `watch` 观察它的变化。完整用法和导出限制见 [链式事件、样式与组件实例](kvue-runtime/README.md#链式事件样式与组件实例)。

## 标准元素、样式与插槽

添加 `org.kvue:kvui:0.1.0-SNAPSHOT` 依赖并导入 `kvui.*`，即可使用标准 HTML 元素：

```kotlin
override val body = RenderFun {
    div(className = "editor") {
        input(vModel = name, placeholder = "请输入你的名字")
        button(type = "button", onClick = { name = "KVUE" }) {
            text("更新名字")
        }
        p { text("你好，$name") }
    }
}
```

`.css {}` 用于添加或按条件添加类名，`.style {}` 用于合并内联样式。两者都会保留具体节点类型，因此后面仍能继续调用组件的事件方法。它们不生成样式表、选择器或伪类规则。

组件调用的尾随函数块是默认插槽，子组件通过 `slot()` 渲染。具名插槽可以用 `节点.slot("header") { ... }` 配置，由子组件调用 `slot("header")` 渲染。插槽按需执行，当前尚未生成带类型参数的作用域插槽接口。

KVUE 保持稳定的片段根结构，并将透传属性合并到唯一的元素或组件根节点；多根组件需要明确选择属性的落点。
原生标签、表单绑定和属性边界见 [KVUI 使用说明](kvui/README.md)。

## 导入第三方 Vue 组件

应用启用 `org.kvue` Gradle 插件后，在 Kotlin/JS 依赖中添加组件库：

```kotlin
implementation(npm("element-plus", "2.14.7"))
```

在项目根目录创建 `kvue.components.json`：

```json
{
  "libraries": [
    {
      "module": "element-plus",
      "package": "demo.elementplus",
      "exports": ["ElButton", "ElInput"]
    }
  ]
}
```

`exports` 可以改为 `"*"`，批量导入所有可识别的组件。导入后即可使用：

```kotlin
import demo.elementplus.ElButton
import demo.elementplus.ElInput
import demo.elementplus.ElInputVue

// 父组件成员
private val input = ElInputVue.ref()

// 渲染作用域内
ElInput(vModel = name).ref(input)
ElButton { text("聚焦输入框") }
    .onClick { input.value?.focus() }
```

构建优先读取 `.d.ts`；没有声明时分析 JavaScript，也可通过 `source` 指定源码入口。导入过程使用 TypeScript 语法树和类型检查器，不执行组件库代码。

生成接口位于 `build/generated/kvue/js-components/kotlin`，类型转换及源码分析报告位于 `build/generated/kvue/js-components/report.json`。
组件库所需的样式与全局初始化仍按其要求配置，例如 Element Plus 的样式入口为 `element-plus/dist/index.css`。

导出选择、源码入口、公开方法和类型转换边界见 [第三方组件导入说明](docs/vue-js-components.md)。

## IDEA 编辑支持

当前插件面向 IntelliJ IDEA 2026.2.3／262 的 Kotlin K2，提供：

- 首次构建前的组件解析、参数提示、自动导入和链式调用补全。
- 双向绑定目标与回调类型的错误提示。
- Kotlin 组件公开实例方法的补全和导航。
- 按住 Ctrl 并用鼠标左键点击虚拟组件，跳转到 Kotlin 类、实际 TypeScript 声明或 JavaScript 来源。

使用 JDK 25，在仓库根目录构建插件。将下面的路径替换为本机 IDEA 安装目录：

```powershell
./gradlew.bat -p kvue-idea '-PideaHome=C:/你的IDEA安装目录' test buildPlugin verifyPluginStructure
```

在 IDEA 的插件设置中，从磁盘安装 `kvue-idea/build/distributions/kvue-idea-0.1.0-SNAPSHOT.zip`，然后完成 Gradle 同步。
编辑器分析第三方组件还需要已安装的 npm 依赖和可用的 `node` 命令。

不安装 IDEA 插件时，生成源默认进入源码目录，构建后即可获得生成接口提示。安装插件后，通过 IDE 同步和构建会默认隐藏 KVUE 生成源，由虚拟声明提供提示，避免重复候选。
可以用 `kvue { exposeGeneratedSources.set(true) }`、用户环境变量或 Gradle 属性主动覆盖。

开发用 `runIde` 会重新同步 Gradle，刷新本地开发版依赖。仅在终端构建或替换插件，不会更新已经导入到 IDEA 的依赖模型。
更多配置见 [IDE 与构建集成](docs/ide-and-build.md)。

## 项目结构

| 路径 | 职责 |
| --- | --- |
| `kvue-runtime` | 组件运行时、响应式封装、Vue 接口、注解和渲染节点 |
| `kvui` | 标准 HTML 元素、表单和原生事件封装 |
| `kvue-codegen-core` | 构建与编辑器共享的组件模型和生成规则 |
| `kvue-ksp` | 验证 Kotlin 组件并生成实际源码 |
| `kvue-compiler-plugin` | 双向绑定检查及读写展开 |
| `kvue-gradle-plugin` | 依赖、生成任务和源码目录的自动接入 |
| `kvue-idea` | K2 虚拟声明、编辑提示、检查和导航 |
| `tools/js-components` | 第三方 JavaScript／TypeScript 组件静态分析 |
| `examples/element-plus-demo` | 使用本地发布依赖的独立交互示例 |
| `docs` | 构建、编辑器集成及第三方组件导入说明 |

根项目只管理模块、插件版本和检查任务；源码与测试归属各自模块。生成文件无需手工维护或提交。

## 开发与验证

| 验证范围 | 命令或任务 |
| --- | --- |
| 主工程汇总检查 | `./gradlew.bat check` |
| 组件、响应式、实例引用与生命周期 | `:kvue-runtime:jsBrowserTest` |
| 原生元素和表单绑定 | `:kvui:check` |
| 第三方声明分析与浏览器接入 | `:js-component-importer:check` |
| 共享生成规则 | `:kvue-codegen-core:test` |
| 绑定编译器 | `:kvue-compiler-plugin:test` |
| Gradle 插件 | `./gradlew.bat -p kvue-gradle-plugin test validatePlugins` |
| IDEA 插件 | 前述 JDK 25 下的 `test buildPlugin verifyPluginStructure` 命令 |

IDEA 测试除虚拟声明解析外，还会构建并加载真实 Kotlin/JS 运行库，检查引用继承、样式接收者及原生与导入组件的调用链。
修改功能时，请将测试放到对应模块，并同步更新相关说明和演示。

## 当前边界

- Kotlin 组件必须是公开或模块内可见、非抽象、无自身泛型参数的顶层类，继承 `Component`，并具有可无参数调用的构造函数。可以继承已经指定具体类型参数的泛型基类。
- Kotlin 组件中的输入属性与事件必须使用委托只读属性；事件函数不能挂起、可空或带接收者。
- 原生 `@Expose` 方法目前不支持泛型、挂起、扩展接收者、默认参数或可变参数；可以提供明确签名的包装方法。
- 复杂 TypeScript 类型会按可表达程度放宽为 `Any`、`Any?` 或参数数组，并在报告中说明。当前不完整复现 TypeScript 类型系统。
- JavaScript 源码分析不会模拟任意执行过程，也没有针对 Vue 单文件组件和所有打包辅助函数的专用解析器。无法静态确认的公开接口需要声明文件补全。
- 编译器与 IDEA 插件依赖对应版本的 Kotlin 内部接口；升级 Kotlin 或 IDEA 时需要同步适配和验证。

## 许可证

本项目采用 [Apache 2.0 许可证](LICENSE)。许可证文件保留 Apache 官方原文。
第三方依赖遵循各自的许可证。
