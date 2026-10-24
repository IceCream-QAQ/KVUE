# KVUE

用 Kotlin class 声明 Vue 组件，由 KSP 生成 Vue 组件定义和类型安全的渲染 DSL。

运行库位于 `kvue-runtime`，共享生成规则位于 `kvue-codegen-core`。根项目是示例应用，通过
`org.kvue` Gradle 插件配置构建。可选的 `kvue-idea` 插件提供 K2 编辑期虚拟声明，解决生成前的组件解析与导入。
模块依赖、Gradle 接入、IDE 插件构建和安装见 [IDE 与构建集成](docs/ide-and-build.md)。

## 构建

使用 JDK 21，并将 `JAVA_HOME` 指向对应目录：

```powershell
.\gradlew.bat check jsBrowserDistribution
.\gradlew.bat jsBrowserDevelopmentRun
```

浏览器测试需要 Chrome；不在默认安装位置时设置 `CHROME_BIN`。生产产物位于
`build/dist/js/productionExecutable`。

`kvue-ksp` 是 JVM 注解处理器模块，分别通过 `kspJs` 和 `kspJsTest` 处理主源码和测试源码；KVUE Gradle 插件配置这些处理器依赖。
生成的 Kotlin 文件位于 `build/generated/ksp/js/jsMain/kotlin` 和
`build/generated/ksp/js/jsTest/kotlin`，不需要手工维护或提交。

`kvue { exposeGeneratedSources.set(false) }` 可让 KVUE 生成源仅参与构建，不进入 IDE 源目录。
无 IDEA 插件时默认打开；安装插件后，IDE 同步和构建会注入关闭的默认值。显式 DSL、用户环境变量或
`-Pkvue.exposeGeneratedSources=true` 可覆盖，完整优先级见上述集成说明。

## 自动导入第三方 JS 组件

在 `kvue.components.json` 中配置 npm 模块、目标 Kotlin 包和导出选择，构建时优先读取 `.d.ts`，没有声明时静态分析 JS 源码，再通过 KSP 生成 `RenderGroup` 扩展函数。
当前配置会批量生成 Element Plus 的组件接口，无需逐个手写 Kotlin props/emits 声明。

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

以下示例放在现有示例的 `test` 包中，复用 `button`、`tdiv` 等 HTML helper：

```kotlin
package test

import kvue.Component
import kvue.annotation.Emit
import kvue.annotation.Prop
import kvue.annotation.VueComponent
import kvue.vdom.RenderFun

@VueComponent
class Counter : Component() {
    @Prop
    private val initial by property(0)

    @Emit
    private val changed: (Int) -> Unit by event()

    private var count by ref(initial)

    override val body = RenderFun {
        button("Count: $count") {
            count++
            changed(count)
        }
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
        tdiv("默认插槽")
    }
    Counter(initial = 10, key = "right")
}
```

两个节点共享组件定义，各自保存计数。父组件重新渲染不会重新创建 Kotlin 实例；节点卸载后再次挂载会创建新实例。
props 和事件上下文在成员初始化之前就已绑定，因此 `ref(initial)` 可以读取传入值。

根组件通过生成的定义挂载：

```kotlin
createApp(AppVue.definition.vue).mount("#app")
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

这里只生成事件和 prop 的映射，尚未生成 `model = ::property` 形式的双向绑定语法。

## Slots 和节点属性

尾随 lambda 是默认插槽，由子组件在渲染时调用 `slot()` 执行。
生成函数返回 `ElementNode`，可继续设置透传属性或具名插槽：

```kotlin
Counter(initial = 1).apply {
    property("class", "counter")
    slot("header") { tdiv("标题") }
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

现有 `RenderContext` 保留为 `RenderGroup` 的类型别名。运行时不再扫描编译后的 `$delegate` 字段，生产构建可正常使用成员名压缩。
