# KVUE

用 Kotlin class 声明 Vue 组件，由 KSP 生成 Vue 组件定义和类型安全的渲染 DSL。

运行库位于 `kvue-runtime`，标准 HTML 元素封装位于 `kvui`，共享生成规则位于 `kvue-codegen-core`。
根项目只管理插件版本、模块和检查任务汇总；源码及测试随各自模块存放。交互示例位于 `examples/element-plus-demo`。
可选的 `kvue-idea` 插件提供 K2 编辑期虚拟声明，解决生成前的组件解析与导入。
Ctrl+点击虚拟组件可回到 Kotlin class，或第三方库的 `.d.ts` / JS 声明。
组件 DSL 支持带类型的链式事件、样式和实例 ref；Kotlin `@Expose` 与第三方公开方法的用法见 [运行时 API](kvue-runtime/README.md#链式事件样式与组件实例)。
模块依赖、Gradle 接入、IDE 插件构建和安装见 [IDE 与构建集成](docs/ide-and-build.md)。

## 构建

使用 JDK 21，并将 `JAVA_HOME` 指向对应目录：

```powershell
.\gradlew.bat check
```

浏览器测试需要 Chrome；不在默认安装位置时设置 `CHROME_BIN`。
演示工程的本地依赖发布、运行和生产打包见 [Element Plus 示例](examples/element-plus-demo/README.md)。
测试可分别运行：

| 范围 | 测试位置 | Gradle 任务 |
| --- | --- | --- |
| 组件、响应式监听、生命周期和注入 | `kvue-runtime/src/jsTest` | `:kvue-runtime:jsBrowserTest` |
| 原生 DOM、表单及标签声明 | `kvui/src/jsTest`、`kvui/tools` | `:kvui:check` |
| JS/TS 静态分析、第三方组件运行 | `tools/js-components` | `:js-component-importer:check` |
| 共享生成规则 | `kvue-codegen-core/src/test` | `:kvue-codegen-core:test` |
| 绑定编译器 | `kvue-compiler-plugin/src/test` | `:kvue-compiler-plugin:test` |

根 `check` 汇总上述模块检查。Gradle 和 IDEA 插件的独立构建验证见集成文档。

`kvue-ksp` 是 JVM 注解处理器模块，分别通过 `kspJs` 和 `kspJsTest` 处理主源码和测试源码；KVUE Gradle 插件配置这些处理器依赖。
生成的 Kotlin 文件位于 `build/generated/ksp/js/jsMain/kotlin` 和
`build/generated/ksp/js/jsTest/kotlin`，不需要手工维护或提交。

`kvue { exposeGeneratedSources.set(false) }` 可让 KVUE 生成源仅参与构建，不进入 IDE 源目录。
无 IDEA 插件时默认打开；安装插件后，IDE 同步和构建会注入关闭的默认值。显式 DSL、用户环境变量或
`-Pkvue.exposeGeneratedSources=true` 可覆盖，完整优先级见上述集成说明。

## Vue API 与响应式监听

`kvue-runtime` 提供 Kotlin 风格的 ref、computed、watch、生命周期、provide/inject 和 effect scope，并在 `vue` 包保留原生 JS API 入口。

```kotlin
private var count by ref(0)
private val doubled by computed { count * 2 }

init {
    watch(::count) { value, old -> println("$old -> $value") }
    watch({ doubled }, WatchOptions(immediate = true)) { value, _ ->
        onCleanup { /* 清理这一轮副作用 */ }
    }
    onMounted { /* 已挂载 */ }
}
```

上述 API 从 `kvue` 导入。监听委托属性时传 getter 或属性引用；持有 ref 对象时也可直接 `watch(ref对象)`。
完整入口、回调语义和简化后的泛型边界见 [运行时与 Vue API](kvue-runtime/README.md)。

## 标准 DOM 元素

添加 `org.kvue:kvui:0.1.0-SNAPSHOT` 依赖并导入 `kvui.*`，即可使用 `div`、`input`、`button` 等 111 个标准 HTML 标签函数：

```kotlin
override val body = RenderFun {
    div(className = "editor") {
        input(vModel = name, placeholder = "你的名字")
        button(type = "button", onClick = { name = "KVUI" }) { text("更新") }
        p { text("你好，$name") }
    }
}
```

其中 `name` 是 `private var name by ref("")`。标签直接渲染原生元素；属性、DOM 事件、`key` 和尾随内容 lambda 均可使用。
`input`、`textarea`、`select` 通过 Vue 原生表单指令支持 `vModel` / `bindModelValue`。完整写法、类型边界和依赖说明见 [KVUI](kvui/README.md)。

## 自动导入第三方 JS 组件

在 `kvue.components.json` 中配置 npm 模块、目标 Kotlin 包和导出选择，构建时优先读取 `.d.ts`，没有声明时静态分析 JS 源码，再通过 KSP 生成 `RenderGroup` 扩展函数。
演示工程的配置会批量生成 Element Plus 的组件接口，无需逐个手写 Kotlin props/emits 声明。
导入器模块在自身 `fixtures/components.json` 中配置测试夹具及用到的 `ElButton`、`ElInput`；JS/TS 资源也位于同一目录。

生成接口位于 `build/generated/kvue/js-components/kotlin`，转换报告位于该目录上一层的 `report.json`。
支持命名导出、默认导出、CommonJS 导出、函数式组件和 options 对象。使用同一个 TypeScript AST/类型检查器读取 JS、TS 和 JSDoc；可通过 `source` 指定源码入口。
无法精确表达的类型和无法静态确定的元数据会写入报告，分析过程不执行组件库代码。

Vue 如何判断组件对象、接入配置、调用示例和类型边界见 [Vue 与第三方组件导入](docs/vue-js-components.md)。

## 双向绑定

属性与 `update:属性名` 事件配对时，生成 `bindXxx` 参数；`modelValue` 另提供 `vModel` 别名：

```kotlin
private var text by ref("")
private var visible by ref(false)

override val body = RenderFun {
    ElInput(vModel = text)
    ElDialog(bindModelValue = visible)
    // 自己的组件也支持同样的配对，例如 @Prop visible / @Emit(name = "update:visible")。
    // Popup(bindVisible = visible)
}
```

直接传入可写变量或属性。`org.kvue` 自动接入编译器插件，将其展开为读取和 update 回调中的赋值。
只读值、不可访问的 setter、普通表达式和冲突参数会在构建期报错；KVUE IDEA 插件也会标红。
详细规则见 [IDE 与构建集成](docs/ide-and-build.md#双向绑定的编译器展开)。

## 声明和调用组件

以下示例使用 KVUI 的标准元素函数：

```kotlin
package demo

import kvue.Component
import kvue.annotation.Emit
import kvue.annotation.Prop
import kvue.annotation.VueComponent
import kvue.vdom.RenderFun
import kvui.button
import kvui.div
import kvui.text

@VueComponent
class Counter : Component() {
    @Prop
    private val initial by property(0)

    @Emit
    private val changed: (Int) -> Unit by event()

    private var count by ref(initial)

    override val body = RenderFun {
        button(onClick = {
            count++
            changed(count)
        }) { text("Count: $count") }
        slot()
    }
}
```

KSP 为它生成两项 API：

- `CounterVue.definition`：持有稳定的 Vue 定义对象，每次 `setup` 创建一个新的 `Counter`。
- `fun RenderGroup.Counter(...)`：接收 props、事件回调、`key` 和默认插槽，将节点加入当前渲染组。

在另一个组件的 `body` 中调用：

```kotlin
override val body = RenderFun {
    Counter(initial = 2, onChanged = { value -> println(value) }, key = "left") {
        div { text("默认插槽") }
    }
    Counter(initial = 10, key = "right")
}
```

两个节点共享组件定义，各自保存计数。父组件重新渲染不会重新创建 Kotlin 实例；节点卸载后再次挂载会创建新实例。
props 和事件上下文在成员初始化之前就已绑定，因此 `ref(initial)` 可以读取传入值。

根组件通过生成的定义挂载：

```kotlin
createApp(CounterVue.definition.vue).mount("#app")
```

不要在渲染代码中手工构造实例后再转换。直接在生成的 `setup` 之外调用组件构造函数会报错。
在 `RenderGroup` 作用域中，同名调用会解析为生成的扩展函数；从其他包使用时导入对应组件名。

## Props

属性使用 `val` 和 `property(...)` 委托，`@Prop` 提供生成所需的元数据：

| 声明 | 生成参数 | 行为 |
| --- | --- | --- |
| `@Prop(required = true)`，类型 `T` | `value: T` | 必须提供，`T` 可以是可空类型 |
| `@Prop`，类型 `T` 非空 | `value: T? = null` | `null` 表示未提供，使用组件内默认值 |
| `@Prop`，类型 `T?` | `value: OptionalProp<T?> = OptionalProp.Omitted` | 区分未提供和显式 `null` |

必填属性可写为 `private val title by property<String>()`，并标记 `@Prop(required = true)`。
可选属性应使用带默认值的 `property(defaultValue)`。KSP 不读取初始化表达式；默认值在每个真实组件实例中计算，不会被复制到生成函数里。

例如组件声明 `@Prop private val text by property<String?>("fallback")` 后：

```kotlin
NullableCard()                         // 不传 text，读取 fallback
NullableCard(text = provided(null))    // 显式传入 null
NullableCard(text = provided("hello"))
```

`provided` 位于 `kvue` 包。组件读取 props 时保留 Vue 的响应式访问，不复制一份失去响应性的值。

## Events

事件必须是带 `@Emit` 的委托 `val`，类型为返回 `Unit` 的函数。支持无参数和多参数事件：

```kotlin
@Emit
private val saved: () -> Unit by event()

@Emit
private val changed: (Int, String) -> Unit by event()

@Emit(name = "update:modelValue")
private val modelChanged: (String) -> Unit by event()
```

生成的参数分别是 `onSaved`、`onChanged`、`onUpdateModelValue`，均为可省略的类型化回调。
事件名称同时写入 Vue 的 `emits`；组件内部的调用通过当前实例的 `setupContext.emit` 发出。
父组件替换回调后，后续事件会使用新的监听器。

配对的属性与 update 事件同时提供直接绑定可写变量的 `bindXxx` / `vModel` 参数，见上文双向绑定。

## Slots 和节点属性

尾随 lambda 是默认插槽，由子组件在渲染时调用 `slot()` 执行。
生成函数返回 `ElementNode`，可继续设置透传属性或具名插槽：

```kotlin
Counter(initial = 1).apply {
    property("class", "counter")
    slot("header") { div { text("标题") } }
}
```

子组件通过 `slot("header")` 渲染具名插槽。插槽保持惰性，每次调用构建新的 VNode。
当前未生成作用域插槽的类型化参数接口。

## 当前约束

- 组件必须是 public/internal、非抽象、无泛型参数的顶层 class，继承 `Component`；可以继承已具体化的泛型基类，生成参数会替换为子类对应的类型。
- 必须存在可无参数调用的 public/internal 构造函数；带默认参数的构造函数也可以。
- 在 `@VueComponent` class 中，`@Prop`、`@Emit` 只用于委托 `val`，分别配合 `property(...)`、`event()` 使用；导入器生成的 `@JsComponent` 接口使用抽象属性描述外部组件。
- 事件函数不能是 suspend、可空或带接收者的函数。
- `key`、`content` 是生成函数的保留参数名；prop、事件监听参数和生成对象的命名冲突会在 KSP 阶段报错。
- 生成器不改写组件源码。第三方自动导入覆盖识别出的组件接口，普通工具函数、插件和完整的 TypeScript 类型系统不在生成范围内。

渲染 DSL 使用 `RenderGroup`。运行时不扫描编译后的 `$delegate` 字段，生产构建可正常使用成员名压缩。
